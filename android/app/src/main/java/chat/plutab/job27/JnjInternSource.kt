package chat.plutab.job27

import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/** The site's own anonymous mobile API; no login, cookies or WeChat client impersonation. */
internal object JnjInternSource {
 const val entryUrl="https://example.invalid"
 const val type="INTERNSHIPRECRUITMENT"
 private const val origin="https://example.invalid"
 fun supports(raw:String)=raw==entryUrl
 fun listUrl(offset:Int):String {require(offset>=0);return "$origin/positionData/listInfo?type=$type&enter=menu&lang=zh_CN&max=20&offset=$offset"}
 fun jobUrl(id:String):String {require(Regex("[a-f0-9]{24}").matches(id));return "$origin/position/detail/$id?type=$type"}
 fun detailUrl(link:JSONObject):String {val id=link.s("officialId");require(link.s("url")==jobUrl(id));return "$origin/positionData/detailDesc/$id?page=listNew&pname="+URLEncoder.encode(link.s("text"),"UTF-8")}
 fun rows(response:JSONObject,offset:Int):Pair<List<JSONObject>,Int>{
  require(response.s("code")=="0"){"强生实习公开接口读取失败"}
  val data=response.getJSONObject("result");val params=data.getJSONObject("params")
  require(data.optBoolean("success")&&params.s("type")==type&&params.optInt("offset",-1)==offset&&params.optInt("max",-1)==20){"强生公开接口未确认实习类型或分页"}
  val total=data.optInt("total",-1);require(total>=0&&data.optJSONArray("positions")!=null){"强生实习官网未返回明确总数或岗位清单"}
  val rows=data.arr("positions").map{r->
   val id=r.s("pid")
   require(id==r.s("id")&&Regex("[a-f0-9]{24}").matches(id)&&r.s("recruitmentType")==type&&r.s("pCompany")=="强生"&&r.s("pName").isNotBlank()){"强生公开接口返回非实习岗位或其他公司，未接收"}
   json("url" to jobUrl(id),"text" to r.s("pName"),"officialId" to id,"externalRequisition" to r.s("taleoId"),"location" to r.s("pCity"),"department" to r.s("pDepartment"),"publishedAt" to r.s("pDate"),"recruitmentType" to "实习招聘","active" to when(r.opt("bStop")){true->"closed";false->"open";else->"unknown"})
  };require(rows.size<=20&&rows.size<=total&&rows.map{it.s("officialId")}.distinct().size==rows.size){"强生实习岗位数量或编号不一致"};return rows to total
 }
 /** A changed total resets pagination once; preserve facts and do not retry the old offset. */
 internal fun commitPage(j:JSONObject,entry:JSONObject,rows:List<JSONObject>,total:Int){
  val previous=entry.optInt("total",-1);val changed=previous>=0&&previous!=total
  try{RefreshJournal.addPage(j,entry,rows,total,rows.isNotEmpty())}catch(e:IllegalStateException){if(changed)throw RefreshPointFailure(e.message?:"强生实习官网总数变化，需重新核对清单",e)else throw e}
 }
 /** Extract the complete JD element, including nested divs; never use the list's summary. */
 fun bodyHtml(html:String):String {
  val start=Regex("<div\\b[^>]*\\sid=[\"']jobDetailContent[\"'][^>]*>",RegexOption.IGNORE_CASE).find(html)?:error(if(PhoneJobs.needsVerification(html))"强生实习详情要求验证"else"强生官网未返回实习岗位完整正文")
  var depth=1
  for(token in Regex("</?div\\b[^>]*>",RegexOption.IGNORE_CASE).findAll(html,start.range.last+1)){
   if(token.value.startsWith("</"))depth-- else depth++
   if(depth==0){val body=html.substring(start.range.last+1,token.range.first);val text=body.replace(Regex("<[^>]+>")," ").trim();require(text.any{it.isLetter()}&&!PhoneJobs.needsVerification(text)){"强生实习岗位正文为空或需要验证"};return body}
  }
  error("强生实习岗位正文未完整返回")
 }
 suspend fun collect(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  require(supports(entry.s("url")))
  if(entry.s("kind")=="reference"){entry.put("previousKind","reference");entry.remove("kind")}
  try{
   while(!entry.optBoolean("done")){
    val offset=(entry.optInt("nextPage",1)-1)*20;stage("listing","读取强生实习第${offset/20+1}页")
    retryRefreshPoint(onRetry={attempt,error->stage("retry","强生实习清单重试 $attempt：$error")}){
     val (rows,total)=rows(PublicRecruitmentSources.request(listUrl(offset)),offset)
     val changed=entry.optInt("total",-1)>=0&&entry.optInt("total")!=total
     require(changed||total==0||rows.isNotEmpty()&&rows.any{RefreshJournal.key(it.s("url")) !in entry.strings("keys")}){"强生实习分页未推进，断点已保存"}
     commitPage(j,entry,rows,total);entry.put("lastReadPage",offset/20+1);entry.remove("retryableError");save()
    }
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"强生实习清单读取失败");save()}
  for((i,key)in entry.strings("keys").withIndex()){
   if(RefreshJournal.facts(j).has(key))continue
   val row=RefreshJournal.queue(j).getJSONObject(key)
   try{retryRefreshPoint(onRetry={attempt,error->stage("retry","强生实习详情重试 $attempt：$error")}){
    stage("details","读取强生实习详情 ${i+1}/${entry.strings("keys").size}")
    val html=PublicRecruitmentSources.publicHtml(detailUrl(row));val body=android.text.Html.fromHtml(bodyHtml(html),android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    require(body.isNotBlank()){"强生实习岗位正文为空"}
    val fact=RefreshEfficiency.shallow(row).put("title",row.s("text")).put("description",body).put("verifiedAt",java.time.Instant.now().toString()).put("sources",row.getJSONArray("sources")).put("evidence","official-anonymous-intern-list-and-complete-detail")
    RefreshJournal.facts(j).put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
   }}catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"强生实习详情读取失败");save()}
  }
 }
}
