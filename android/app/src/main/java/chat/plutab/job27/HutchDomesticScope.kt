package chat.plutab.job27
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

/** User requested domestic Hutch recruitment only. Global data is archived, never erased. */
internal object HutchDomesticScope {
 const val companyId="00000000-0000-4000-8000-000000000004"
 const val reason="和黄仅刷新国内招聘；全球入口已移出本次任务，历史岗位和评分保留"
 private fun linkedin(raw:String)=runCatching{val u=URI(raw);u.scheme=="https"&&u.host in listOf("www.linkedin.com","cn.linkedin.com","hk.linkedin.com")&&u.userInfo==null&&u.port in listOf(-1,443)}.getOrDefault(false)
 fun global(raw:String)=linkedin(raw)&&runCatching{val u=URI(raw);u.path.trimEnd('/')=="/company/hutchmed/jobs"||HutchRecruitmentPage.isGlobalSearch(raw)||u.path.startsWith("/jobs/view/")}.getOrDefault(false)
 fun needs(j:JSONObject)=false //j.s("companyId")==companyId&&(j.arr("entries").any{global(it.s("url"))}||RefreshJournal.objects(RefreshJournal.queue(j)).any{linkedin(it.s("url"))&&!j.arr("entries").filterNot{e->global(e.s("url"))}.any{e->RefreshJournal.key(it.s("url")) in e.strings("keys")}})
 /** Save the original journal before calling, so original fact files remain recoverable. */
 fun apply(j:JSONObject):Boolean{
  if(!needs(j))return false
  val excluded=j.arr("entries").filter{global(it.s("url"))};val kept=j.arr("entries").filterNot{global(it.s("url"))}
  val active=kept.flatMap{it.strings("keys")}.toSet();val excludedKeys=excluded.flatMap{it.strings("keys")}.toSet()
  val archived=j.optJSONObject("excludedGlobalJobs")?:JSONObject();val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j)
  for(key in q.keys().asSequence().toList()){
   val row=q.getJSONObject(key)
   if(key in active||!(linkedin(row.s("url"))||key in excludedKeys||row.strings("sources").any{global(it)}))continue
   archived.put(key,json("link" to row,"retainedFactKey" to if(facts.has(key))key else null,"manualGrade" to j.optJSONObject("manualGrades")?.opt(key),"ratingError" to j.optJSONObject("ratingErrors")?.opt(key)))
   q.remove(key);facts.remove(key);j.getJSONObject("failedDetails").remove(key);j.optJSONObject("retryableDetails")?.remove(key);j.optJSONObject("ratingErrors")?.remove(key)
  }
  j.put("excludedGlobalEntries",JSONArray(j.arr("excludedGlobalEntries")+excluded)).put("excludedGlobalJobs",archived).put("entries",JSONArray(kept)).put("domesticScopeVersion",1).put("scopeReason",reason)
  val required=kept.filter{it.s("kind")!="reference"}
  j.put("listingComplete",required.isNotEmpty()&&required.all{it.optBoolean("done")}).put("detailsDone",q.keys().asSequence().all{facts.has(it)})
  j.remove("viewCounts")
  return true
 }
 fun task(old:JSONObject,record:JSONObject):JSONObject=RefreshEfficiency.shallow(old).put("state",if(record.s("state")=="waiting_rating")"queued"else record.s("state")).put("stage",if(record.s("state")=="waiting_rating")"waiting_rating"else record.s("state")).put("message",record.s("message")).put("refreshCounts",record.getJSONObject("viewCounts")).put("updatedAt",java.time.Instant.now().toString())
 fun batch(old:JSONObject,record:JSONObject):JSONObject=RefreshEfficiency.shallow(old).apply{
  val count=record.getJSONObject("viewCounts")
  put("entries",JSONArray(RefreshBatchQueue.entries(old).map{row->if(row.s("companyId")!=companyId)row else RefreshEfficiency.shallow(row).put("captureComplete",count.optBoolean("captureComplete")).put("capture",if(count.optBoolean("captureComplete"))"done"else"queued").put("review",if(count.optInt("rated",-1)==count.optInt("savedDetails",-2))"done"else"queued").put("state",if(record.s("state")=="waiting_rating")"queued"else record.s("state")).put("taskId",record.s("taskId"))}))
  if(arr("entries").isNotEmpty()&&arr("entries").all{it.s("state")=="complete"})put("state","complete")
 }
}
