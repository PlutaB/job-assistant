package chat.plutab.job27

import org.json.*
import java.net.URI
import kotlinx.coroutines.CancellationException

/** Verified China campus campaign plus the domestic social board linked by the corporate page. */
internal object LegendChinaSource {
 const val listing="https://example.invalid"
 const val script="https://example.invalid"
 const val companyId="00000000-0000-4000-8000-000000000005"
 fun supports(url:String)=url==listing
 const val social="https://example.invalid"
 private fun corporate(url:String)=runCatching{val u=URI(url);u.scheme=="https"&&u.host=="legendbiotech.cn"&&u.path.trimEnd('/')=="/careers"&&u.rawQuery==null&&u.rawUserInfo==null&&u.port in listOf(-1,443)}.getOrDefault(false)
 fun attach(j:JSONObject):Boolean {
  if(j.s("companyId")!=companyId)return false
  var changed=false
  for((url,name)in listOf(listing to "传奇国内校园招聘",social to "传奇国内社会招聘")){
   if(j.arr("entries").none{it.s("url")==url}){
    j.getJSONArray("entries").put(json("url" to url,"name" to name,"nextPage" to 1,"done" to false,"keys" to JSONArray(),"total" to JSONObject.NULL));changed=true
   }
  }
  // Browser-confirmed corporate job links all point to this exact employer board.
  // The brochure is a signpost; only the board's full list/JDs establish capture completion.
  val target=j.arr("entries").first{it.s("url")==social}
  for(e in j.arr("entries").filter{corporate(it.s("url"))}){
   if(e.s("aliasOf")!=social||e.s("kind")!="reference"){
    if(e.s("error").isNotBlank())e.put("previousError",e.s("error"))
    e.put("kind","reference").put("aliasOf",social).put("effectiveListingUrl",social).put("linkedListings",JSONArray().put(social)).put("aliasEvidence","corporate-browser-dom-verified-2026-10-28")
    e.remove("error");e.remove("retryableError");changed=true
   }
   if(e.optBoolean("done")!=target.optBoolean("done")){e.put("done",target.optBoolean("done"));changed=true}
  }
  if(changed)j.put("listingComplete",false).put("fullyComplete",false)
  return changed
 }
 fun rows(js:String):List<JSONObject>{
  val marker=Regex("\\bvar\\s+jobData\\s*=\\s*").find(js)?:error("传奇国内专题未返回岗位数据")
  val value=JSONTokener(js.substring(marker.range.last+1)).nextValue() as? JSONObject?:error("传奇国内专题岗位格式无效")
  require(value.optJSONArray("list")!=null&&value.optJSONArray("type")!=null){"传奇国内专题清单不完整"}
  val types=value.strings("type");val rows=value.arr("list").map{r->
   val u=URI(r.s("url"));val id=Regex("/job/([a-f0-9-]{36})").matchEntire(u.fragment.orEmpty())?.groupValues?.get(1)
   require(u.scheme=="https"&&u.host=="app.mokahr.com"&&u.path=="/campus-recruitment/legendbiotech/68171"&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&u.rawQuery==null&&id!=null){"传奇专题包含未核实公司或职位链接"}
   val body=r.s("job_info");val text=body.replace(Regex("<[^>]*>")," ")
   require(r.s("job_name").isNotBlank()&&r.s("type") in types&&text.length>=100&&Regex("职责|Responsibilities",RegexOption.IGNORE_CASE).containsMatchIn(text)&&Regex("任职|资格|Qualifications",RegexOption.IGNORE_CASE).containsMatchIn(text)){"传奇专题职位没有返回完整职责及任职要求"}
   json("url" to r.s("url"),"officialId" to id,"title" to r.s("job_name"),"text" to r.s("job_name"),"description" to body,"department" to "","jobCategory" to r.s("type"),"recruitmentType" to "校园招聘","location" to r.s("job_name").substringAfterLast('-'),"active" to "unknown","evidence" to "employer-china-2027-campaign-full-jd")
  }
  require(rows.isNotEmpty()&&rows.map{it.s("officialId")}.distinct().size==rows.size){"传奇专题为空或存在重复编号，未确认完整清单"}
  return rows
 }
 suspend fun collect(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  try{
   stage("listing","读取传奇国内校招完整岗位说明")
   val rows=rows(PublicRecruitmentSources.publicHtml(script))
   // A complete single-response list can change between refreshes; replace only its index.
   entry.put("keys",JSONArray());entry.remove("total");entry.put("done",false)
   RefreshJournal.addPage(j,entry,rows,rows.size,false);entry.put("lastReadPage",1);save()
   for((i,row)in rows.withIndex()){
    val key=RefreshJournal.key(row.s("url"));if(RefreshJournal.facts(j).has(key))continue
    row.put("sources",RefreshJournal.queue(j).getJSONObject(key).getJSONArray("sources")).put("verifiedAt",java.time.Instant.now().toString())
    RefreshJournal.facts(j).put(key,row);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);j.optJSONObject("retryableDetails")?.remove(key);save();onFact(row,j)
    stage("details","传奇国内详情 ${i+1}/${rows.size}")
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("done",false).put("error",e.message?:"传奇国内招聘专题读取失败");save()}
 }
}
