package chat.plutab.job27

import java.net.URI
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** Only the verified Allist legal entity; no search/recommendation results from other employers. */
internal object ZhaopinCompanySource {
 const val listing="https://example.invalid"
 const val companyNumber="CZL1254687440"
 const val companyName="上海艾力斯医药科技股份有限公司"
 fun supports(raw:String)=raw==listing
 fun pageUrl(page:Int):String {require(page in 1..200);return "https://example.invalid"}
 fun rows(response:JSONObject):Pair<List<JSONObject>,Int>{
  require(response.optInt("code")==200){"艾力斯公开岗位接口读取失败"}
  val data=response.getJSONObject("data")
  require(data.optInt("isVerification",-1)==0){"艾力斯公开岗位接口要求安全验证"}
  val total=data.optInt("count",-1);require(total>=0&&data.optJSONArray("list")!=null){"艾力斯公开接口未返回明确总数或清单"}
  val rows=data.arr("list").map{row->
   val id=row.s("number")
   require(row.s("companyNumber")==companyNumber&&row.s("companyName")==companyName&&Regex("CCL1254687440J[0-9]+").matches(id)&&row.s("name").isNotBlank()){"艾力斯公开接口返回其他公司或无编号岗位，未接收推荐结果"}
   json("url" to "https://example.invalid","text" to row.s("name"),"officialId" to id)
  };require(rows.size<=20&&rows.size<=total){"艾力斯公开岗位清单数量异常"};return rows to total
 }
 fun detail(html:String,url:String):JSONObject{
  val u=URI(url);val id=Regex("/jobdetail/(CCL1254687440J[0-9]+)[.]htm").matchEntire(u.path)?.groupValues?.get(1)
  require(u.scheme=="https"&&u.host=="www.zhaopin.com"&&u.userInfo==null&&u.port in listOf(-1,443)&&id!=null){"不是艾力斯独立岗位地址"}
  val value=RemainingCompanySources.state(html);val detail=value.getJSONObject("jobDetail");val company=detail.getJSONObject("detailedCompany");val row=detail.getJSONObject("detailedPosition")
  require(value.s("jobNumber")==id&&row.s("positionNumber")==id&&row.s("companyNumber")==companyNumber&&company.s("companyNumber")==companyNumber&&company.s("companyName")==companyName){"岗位或公司主体与艾力斯不一致"}
  require(row.s("positionName").isNotBlank()&&row.s("description").isNotBlank()&&!PhoneJobs.needsVerification(row.s("description"))){"艾力斯官网未返回完整岗位正文"}
  return json("url" to url,"title" to row.s("positionName"),"officialId" to id,"description" to row.s("description"),"location" to row.s("positionWorkCity"),"department" to row.s("subJobTypeLevelName"),"active" to "unknown","employer" to companyName,"evidence" to "official-public-company-and-job-payload")
 }
 suspend fun collect(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  require(supports(entry.s("url")))
  try{
   while(!entry.optBoolean("done")){
    val page=entry.optInt("nextPage",1);stage("listing","读取艾力斯智联第${page}页")
    retryRefreshPoint(onRetry={attempt,error->stage("retry","艾力斯第${page}页重试 $attempt：$error")}){
     val (rows,total)=rows(PublicRecruitmentSources.request(pageUrl(page)))
     val previous=entry.strings("keys").toSet()
     require(total==0||rows.isNotEmpty()&&rows.any{RefreshJournal.key(it.s("url")) !in previous}){"艾力斯公开分页未推进，已保留第${page}页断点"}
     RefreshJournal.addPage(j,entry,rows,total,rows.isNotEmpty());entry.put("lastReadPage",page);entry.remove("retryableError");save()
    }
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"艾力斯清单读取失败");save()}
  for((index,key) in entry.strings("keys").withIndex()){
   if(RefreshJournal.facts(j).has(key))continue
   val link=RefreshJournal.queue(j).getJSONObject(key)
   try{retryRefreshPoint(onRetry={attempt,error->stage("retry","艾力斯详情重试 $attempt：$error")}){
    stage("details","读取艾力斯详情 ${index+1}/${entry.strings("keys").size}")
    val fact=detail(PublicRecruitmentSources.publicHtml(link.s("url")),link.s("url")).put("sources",link.getJSONArray("sources")).put("verifiedAt",java.time.Instant.now().toString())
    RefreshJournal.facts(j).put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
   }}catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"艾力斯详情读取失败");save()}
  }
 }
}
