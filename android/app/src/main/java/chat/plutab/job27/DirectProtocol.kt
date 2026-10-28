package chat.plutab.job27

import org.json.JSONObject
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64

/** Pure protocol checks; no credentials, Android state or network access. */
object DirectProtocol {
 const val issuer="https://auth.openai.com"
 const val resource="https://api.openai.com/v1"
 const val scopes="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
 fun encode(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
 fun decode(raw:String)=Base64.getUrlDecoder().decode(raw)
 fun challenge(verifier:String)=encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
 fun validateIdToken(token:String,keys:JSONObject,client:String,nonce:String?,subject:String?=null,now:Long=System.currentTimeMillis()/1000):JSONObject {
  val p=token.split('.');require(p.size==3){"身份凭证格式无效"}
  val h=JSONObject(String(decode(p[0]),Charsets.UTF_8));require(h.optString("alg")=="RS256"){"不支持的身份签名算法，未保存登录"}
  require(!h.has("crit")&&h.optBoolean("b64",true)){"不支持的身份签名扩展"}
  val array=keys.getJSONArray("keys");val key=(0 until array.length()).map{array.getJSONObject(it)}.firstOrNull{it.optString("kid")==h.optString("kid")&&it.optString("kty")=="RSA"&&it.optString("use","sig")=="sig"&&it.optString("alg","RS256")=="RS256"}?:error("未找到官方身份验证公钥")
  require(h.optString("kid").isNotEmpty()){"身份签名缺少公钥标识"}
  val rsa=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decode(key.getString("n"))),BigInteger(1,decode(key.getString("e")))))
  require(Signature.getInstance("SHA256withRSA").run{initVerify(rsa);update((p[0]+"."+p[1]).toByteArray(Charsets.US_ASCII));verify(decode(p[2]))}){"身份签名验证失败"}
  val claims=JSONObject(String(decode(p[1]),Charsets.UTF_8));require(claims.optString("iss")==issuer){"身份签发方不匹配"}
  val aud=claims.opt("aud");val audiences=if(aud is org.json.JSONArray)(0 until aud.length()).map{aud.getString(it)}else listOf(aud as? String)
  require(client in audiences){"身份凭证不属于本应用"}
  if(audiences.size>1||claims.has("azp"))require(claims.optString("azp")==client){"身份授权方不匹配"}
  require(claims.optLong("exp",0)>now-5&&claims.optLong("iat",0)>0&&claims.optLong("iat")<=now+5&&claims.optLong("nbf",0)<=now+5){"身份凭证已过期或手机时间不正确"}
  require(claims.optString("sub").isNotBlank()){"身份凭证缺少账户标识"}
  if(nonce!=null)require(MessageDigest.isEqual(nonce.toByteArray(),claims.optString("nonce").toByteArray())){"登录验证随机数不匹配"}
  if(subject!=null)require(claims.optString("sub")==subject){"返回账户与所选账户不一致"}
  return claims
 }
 fun canUsePlan(scope:String)="chatgpt.tokens.use.direct" in scope.split(' ')
 fun issuedClient(saved:String?,returned:String?):String {
  if(saved!=null){require(returned.isNullOrBlank()||returned==saved){"返回的应用注册身份发生变化"};return saved}
  require(!returned.isNullOrBlank()&&returned!="dynamic_agent_client"&&returned.length<=300){"官方尚未完成本应用注册，请重新登录"};return returned
 }
 fun request(model:String,prompt:String)=JSONObject().put("model",model).put("input",org.json.JSONArray().put(JSONObject().put("role","user").put("content",prompt))).put("store",false).put("stream",true)
 class StreamResult {
  var failure:JSONObject?=null;private set
  val output=StringBuilder();var completed=false;private set
  private fun responseText(r:JSONObject):String{val out=StringBuilder();val rows=r.optJSONArray("output")?:return "";for(i in 0 until rows.length()){val content=rows.optJSONObject(i)?.optJSONArray("content")?:continue;for(n in 0 until content.length()){val part=content.optJSONObject(n)?:continue;if(part.optString("type")=="output_text")out.append(part.optString("text"))}};return out.toString()}
  /** Header labels alone cannot determine the body format. Preserve a bounded diagnostic on failures. */
  fun consume(reader:java.io.BufferedReader,contentType:String?,audit:JSONObject):String{
   audit.put("contentType",contentType?:JSONObject.NULL);val preview=StringBuilder();var count=0
   fun line():String?{val value=reader.readLine()?:return null;count+=value.length+1;require(count<=4000000){"GPT响应超过本轮容量"};if(preview.length<12000)preview.append(value.take(12000-preview.length)).append('\n');return value}
   try{
    var first:String?;do{first=line()?.removePrefix("\uFEFF")}while(first!=null&&first.isBlank())
    require(first!=null){"GPT返回空响应"};val initial=first!!
    if(initial.startsWith("data:")||initial.startsWith("event:")||initial.startsWith(":")||initial.startsWith("id:")||initial.startsWith("retry:")){
     audit.put("bodyFormat","sse");val event=StringBuilder();var current:String?=initial
     fun dispatch(){if(event.isNotEmpty()){event(event.toString().trimEnd('\n'));event.setLength(0)}}
     while(current!=null){val v=current;if(v.isEmpty()){dispatch();if(completed)break}else if(v.startsWith("data:"))event.append(v.substring(5).removePrefix(" ")).append('\n');current=line()};if(!completed)dispatch()
    }else{
     audit.put("bodyFormat","non-sse");val body=StringBuilder(initial);while(true){val v=line()?:break;body.append('\n').append(v)};val response=runCatching{JSONObject(body.toString())}.getOrNull()
     if(response?.has("error")==true&&!response.isNull("error")){event(JSONObject().put("type","error").put("error",response.get("error")).toString())}
     else if(response?.optString("type")?.startsWith("response.")==true){event(response.toString())}
     else if(response?.optString("object")=="response"&&response.optString("id").startsWith("resp_")&&response.optString("status")=="completed"&&response.optJSONArray("output")!=null){audit.put("bodyFormat","completed-response-json");event(JSONObject().put("type","response.completed").put("response",response).toString())}
     else error("GPT返回非预期响应（"+(contentType?:"未提供内容类型")+"）："+(response?.optJSONObject("error")?.optString("message")?:body.toString().replace(Regex("<[^>]*>")," ").replace(Regex("\\s+")," ").take(180)))
    }
    return finish()
   }finally{audit.put("bodyPreviewTruncated",count>12000).put("bodyPreview",preview.toString().replace(Regex("(?i)Bearer [A-Za-z0-9._~+/-]+"),"Bearer [已隐藏]").replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),"[身份凭据已隐藏]"))}
  }
  fun event(raw:String){if(raw=="[DONE]")return;val e=JSONObject(raw)
   when(e.optString("type")){
    "response.output_text.delta"->{require(!completed);output.append(e.getString("delta"));require(output.length<=2000000){"回复过大，未完成保存"}}
    "response.completed"->{require(failure==null){"GPT已报告失败"};val r=e.optJSONObject("response");if(r!=null)GptQuota.failure(r)?.let{throw it};require(r==null||(!r.has("error")||r.isNull("error"))&&r.optString("status","completed")=="completed"){"GPT完成响应包含错误或未完成状态"};if(output.isEmpty()&&r!=null)output.append(responseText(r));completed=true}
    "error","response.failed","response.incomplete"->{failure=e;val err=e.optJSONObject("response")?.optJSONObject("error")?:e.optJSONObject("error")?:e;val code=err.optString("code",e.optString("type"));val message=err.optString("message",err.optString("detail")).ifBlank{e.opt("error")?.takeIf{it is String}?.toString().orEmpty()};throw (GptQuota.failure(e)?:IllegalStateException("GPT请求未完成：$code · "+message.take(240)))}
   }
  }
  fun finish():String{require(completed&&failure==null){"连接中断，未收到 GPT 完成事件；不会标记为成功"};return output.toString()}
 }
}
