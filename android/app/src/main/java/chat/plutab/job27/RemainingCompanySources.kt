package chat.plutab.job27

import org.json.*
import java.net.URI
import kotlinx.coroutines.CancellationException

/** Public payloads are accepted only when their exact legal company and job IDs match. */
internal object RemainingCompanySources {
 internal fun state(html:String):JSONObject{
  val marker="__INITIAL_STATE__=";val start=html.indexOf(marker)
  require(start>=0){if(PhoneJobs.needsVerification(html))"官网要求安全验证，未返回公开岗位数据"else"官网未返回公开岗位数据"}
  return JSONTokener(html.substring(start+marker.length)).nextValue() as? JSONObject?:error("官网岗位数据格式无效")
 }
 fun bioListing(url:String)=url==CompanySourceIdentity.bioListing
 fun bioRows(html:String):Pair<List<JSONObject>,Int>{
  val value=state(html);val company=value.getJSONObject("companyDetail").getJSONObject("companyBase")
  require(value.s("companyCode")=="CZ817351360"&&company.s("companyNumber")=="CZ817351360"&&company.s("companyName")==CompanySourceIdentity.bioName){"招聘页主体不是天士力生物医药股份有限公司"}
  val total=value.optInt("onlinePositionsCount",-1);require(total>=0&&value.has("onlinePositions")){"生物公司官网未返回职位总数或清单"}
  val rows=value.arr("onlinePositions").map{r->
   val id=r.s("number");require(r.s("companyNumber")=="CZ817351360"&&Regex("CC817351360J[0-9]+").matches(id)&&r.s("name").isNotBlank()){"清单包含其他公司或无编号岗位"}
   json("url" to "https://example.invalid","text" to r.s("name"),"officialId" to id)
  };require(rows.size<=total){"生物公司清单数量不一致"};return rows to total
 }
 fun bioDetail(html:String,url:String):JSONObject{
  val u=URI(url);val id=Regex("/jobdetail/(CC817351360J[0-9]+)[.]htm").matchEntire(u.path)?.groupValues?.get(1)
  require(u.scheme=="https"&&u.host=="www.zhaopin.com"&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&id!=null){"不是天士力生物独立岗位地址"}
  val value=state(html);val detail=value.getJSONObject("jobDetail");val company=detail.getJSONObject("detailedCompany");val row=detail.getJSONObject("detailedPosition")
  require(value.s("jobNumber")==id&&row.s("positionNumber")==id&&row.s("companyNumber")=="CZ817351360"&&company.s("companyName")==CompanySourceIdentity.bioName&&company.s("companyNumber")=="CZ817351360"){"岗位或公司主体与天士力生物不一致"}
  require(row.s("description").isNotBlank()&&row.s("positionName").isNotBlank()){"官网未返回生物岗位完整正文"}
  return json("url" to url,"title" to row.s("positionName"),"officialId" to id,"description" to row.s("description"),"location" to row.s("positionWorkCity"),"department" to "","jobCategory" to row.s("subJobTypeLevelName"),"active" to "unknown","employer" to CompanySourceIdentity.bioName,"evidence" to "official-public-company-and-job-payload")
 }
 suspend fun collectBio(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  require(bioListing(entry.s("url")))
  try{
   if(!entry.optBoolean("done")){
    stage("listing","核实天士力生物独立公司清单")
    val (rows,total)=bioRows(PublicRecruitmentSources.publicHtml(entry.s("url")))
    RefreshJournal.addPage(j,entry,rows,total,false);entry.put("lastReadPage",1);save()
    require(rows.size==total){"生物公司官网仅返回${rows.size}/${total}条公开岗位，完整清单尚未确认"}
   }
   for((i,key)in entry.strings("keys").withIndex()){
    if(RefreshJournal.facts(j).has(key))continue
    val row=RefreshJournal.queue(j).getJSONObject(key)
    try{retryRefreshPoint(onRetry={attempt,error->stage("retry","生物岗位详情重试 $attempt：$error")}){
     stage("details","读取天士力生物详情 ${i+1}/${entry.strings("keys").size}")
     val fact=bioDetail(PublicRecruitmentSources.publicHtml(row.s("url")),row.s("url")).put("sources",row.getJSONArray("sources")).put("verifiedAt",java.time.Instant.now().toString())
     RefreshJournal.facts(j).put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
    }}catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"生物岗位详情读取失败");save()}
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"生物公司读取失败");save()}
 }
 suspend fun jnjInternCount():Int{
  val result=PublicRecruitmentSources.request("https://example.invalid")
  require(result.optBoolean("success")&&result.has("positionTotal")&&result.optInt("positionTotal",-1)>=0){"实习官网未返回明确岗位数"}
  return result.getInt("positionTotal")
 }
 fun cspcDetail(response:JSONObject,id:String):String{
  require(response.s("state")=="1"){"石药官网详情接口读取失败"}
  val row=response.getJSONObject("result");require(row.s("REQUEST_ID")==id){"石药官网详情编号与请求不一致"}
  fun clean(v:String)=v.replace(Regex("<[^>]*>")," ").replace("&nbsp;"," ").trim()
  val duty=clean(row.s("JOB_DUTY"));val request=clean(row.s("JOB_REQUEST"))
  require(listOf(duty,request).any{it.any{c->c.isLetter()}}){if(duty=="1"&&request=="1")"官网职责和任职要求均为占位符“1”（公开详情接口已核实）"else"石药官网详情未返回职责与任职要求正文"}
  return listOf(duty,request).filter{it.isNotBlank()}.joinToString("\n\n")
 }
}
