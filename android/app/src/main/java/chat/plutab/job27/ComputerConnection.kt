package chat.plutab.job27

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ComputerApiError(val status:Int,message:String):Exception(message)
data class ComputerLink(val serverId:String,val name:String,val addresses:List<String>,val fingerprint:String,val code:String){
 companion object{
  fun parse(raw:String):ComputerLink{
   val uri=Uri.parse(raw.trim());require(uri.scheme=="job27"&&uri.host=="pair"){"请扫描电脑管理网页的校招配对二维码"}
   val payload=uri.getQueryParameter("data")?:error("连接信息缺少配对内容")
   val body=JSONObject(if(payload.startsWith("{"))payload else String(android.util.Base64.decode(payload,android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),Charsets.UTF_8))
   require(body.optInt("version")==1){"连接信息版本不受支持，请更新电脑端"}
   require(body.optLong("expiresAt")>System.currentTimeMillis()){ "二维码已过期或已使用，请在电脑生成新二维码" }
   val id=body.s("serverId");require(Regex("^[a-zA-Z0-9-]{1,100}$").matches(id)){"电脑身份无效"}
   val pin=body.s("fingerprint").lowercase();require(Regex("^[a-f0-9]{64}$").matches(pin)){"缺少电脑安全指纹，请重新扫码"}
   val code=body.s("code");require(Regex("^[0-9]{6}$").matches(code)){"配对凭证无效"}
   val addresses=body.strings("addresses").take(12).map{validateComputerAddress(it)};require(addresses.isNotEmpty()){ "电脑未提供可用网络地址，请检查网络" }
   return ComputerLink(id,body.s("name","我的电脑"),addresses,pin,code)
  }
 }
}
fun isRemoteComputerAddress(raw:String):Boolean=runCatching{
 val host=URI(validateComputerAddress(raw)).host.orEmpty().lowercase()
 val parts=host.split('.').mapNotNull{it.toIntOrNull()}
 host.matches(Regex("^[a-z0-9.-]+\\.ts\\.net$"))||(parts.size==4&&parts[0]==100&&parts[1] in 64..127&&parts.all{it in 0..255})
}.getOrDefault(false)
fun validateComputerAddress(raw:String):String{
 val u=URI(raw.trim());require(u.scheme=="https"&&u.host!=null&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&(u.path.isNullOrEmpty()||u.path=="/")){"电脑地址须为二维码中的 HTTPS 地址"}
 return raw.trim().trimEnd('/')
}
fun computerRequest(base:String,pin:String,token:String,path:String,method:String="GET",body:JSONObject?=null):ByteArray{
 validateComputerAddress(base);require(Regex("^[a-fA-F0-9]{64}$").matches(pin)){"请先扫描二维码确认电脑身份"}
 val trust=object:X509TrustManager{
  override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
  override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?)=throw java.security.cert.CertificateException("不支持客户端证书")
  override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?){
   val cert=chain?.firstOrNull()?:throw java.security.cert.CertificateException("电脑未提供证书");cert.checkValidity()
   val actual=MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(""){"%02x".format(it)}
   if(!MessageDigest.isEqual(actual.toByteArray(),pin.lowercase().toByteArray()))throw java.security.cert.CertificateException("电脑安全指纹不匹配；已拒绝连接")
  }
 }
 val ssl=SSLContext.getInstance("TLS").apply{init(null,arrayOf<TrustManager>(trust),null)}
 val c=URL(base+path).openConnection() as HttpsURLConnection
 c.sslSocketFactory=ssl.socketFactory
 // The certificate is pinned above; the computer may change its LAN IP address.
 c.hostnameVerifier=javax.net.ssl.HostnameVerifier{_,_->true}
 try{
  c.instanceFollowRedirects=false;c.requestMethod=method;c.connectTimeout=2500;c.readTimeout=30000
  if(token.isNotEmpty())c.setRequestProperty("Authorization","Bearer $token")
  if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())}}
  val status=c.responseCode;val bytes=(if(status in 200..299)c.inputStream else c.errorStream)?.use{it.readBytes()}?:ByteArray(0)
  if(status !in 200..299)throw ComputerApiError(status,runCatching{JSONObject(String(bytes)).s("error")}.getOrDefault("电脑请求失败：$status"))
  return bytes
 }finally{c.disconnect()}
}
class ComputerDiscovery(context:Context,private val matches:(String)->Boolean,private val found:(String)->Unit){
 private val manager=context.getSystemService(Context.NSD_SERVICE) as NsdManager
 private var listener:NsdManager.DiscoveryListener?=null
 private val queue=java.util.ArrayDeque<NsdServiceInfo>();private var resolving=false
 @Suppress("DEPRECATION") @Synchronized private fun resolveNext(){
  if(resolving||queue.isEmpty()||listener==null)return
  resolving=true;val service=queue.removeFirst()
  manager.resolveService(service,object:NsdManager.ResolveListener{
   override fun onResolveFailed(info:NsdServiceInfo,errorCode:Int){done()}
   override fun onServiceResolved(info:NsdServiceInfo){
    val id=info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
    if(matches(id)){val host=info.host?.hostAddress;if(host!=null)found("https://"+(if(host.contains(':'))"[$host]" else host)+":"+info.port)}
    done()
   }
   private fun done(){synchronized(this@ComputerDiscovery){resolving=false;resolveNext()}}
  })
 }
 @Synchronized fun start(){if(listener!=null)return
  val l=object:NsdManager.DiscoveryListener{
   override fun onDiscoveryStarted(type:String){}
   override fun onServiceFound(info:NsdServiceInfo){if(info.serviceType.contains("_job27._tcp")){synchronized(this@ComputerDiscovery){if(queue.size<12)queue.add(info);resolveNext()}}}
   override fun onServiceLost(info:NsdServiceInfo){}
   override fun onDiscoveryStopped(type:String){}
   override fun onStartDiscoveryFailed(type:String,errorCode:Int){stop()}
   override fun onStopDiscoveryFailed(type:String,errorCode:Int){}
  };listener=l;runCatching{manager.discoverServices("_job27._tcp.",NsdManager.PROTOCOL_DNS_SD,l)}.onFailure{listener=null}
 }
 @Synchronized fun stop(){val l=listener?:return;listener=null;queue.clear();runCatching{manager.stopServiceDiscovery(l)}}
}
