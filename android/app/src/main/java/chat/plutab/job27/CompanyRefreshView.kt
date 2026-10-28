package chat.plutab.job27
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

/** Every current screen joins the same journal task and its atomically saved counters. */
object CompanyRefreshView {
 fun select(id:String,tasks:List<JSONObject>,journal:JSONObject?):JSONObject? {
  val candidates=tasks.filter{it.s("companyId")==id}
  if(journal!=null){
   val taskId=journal.s("taskId");if(taskId.isBlank())return null
   val base=candidates.firstOrNull{it.s("id")==taskId}?:json("id" to taskId,"companyId" to id,"kind" to "refresh","transport" to "phone","createdAt" to journal.s("createdAt"),"events" to JSONArray())
   val task=RefreshEfficiency.shallow(base);val c=RefreshEfficiency.shallow(journal.getJSONObject("viewCounts"))
   return task.put("refreshCounts",c).put("journalState",journal.s("state")).put("journalComplete",journal.optBoolean("fullyComplete")).put("capturePaused",journal.optBoolean("capturePaused")).put("reviewPaused",journal.optBoolean("reviewPaused"))
  }
  return candidates.maxByOrNull{runCatching{Instant.parse(it.s("createdAt"))}.getOrDefault(Instant.EPOCH)}
 }
 fun phase(task:JSONObject,journal:JSONObject?,batch:JSONObject?,busy:Boolean,singleTaskId:String?,activeBatchTasks:Set<String>?=null):JSONObject {
  if(journal==null)return task
  val view=RefreshEfficiency.shallow(task);val row=batch?.let{RefreshBatchQueue.entries(it).firstOrNull{e->e.s("companyId")==task.s("companyId")&&e.s("taskId")==task.s("id")}}
  val enabled=row!=null&&batch?.s("state")=="running"&&!row.optBoolean("companyPaused")
  val batchRunning=enabled&&busy&&(activeBatchTasks==null||task.s("id") in activeBatchTasks)&&listOf("capture","review").any{row!!.s(it)=="running"&&!row.optBoolean(it+"Paused")&&!batch!!.optBoolean(it+"Paused")}
  val singleRunning=busy&&singleTaskId==task.s("id")&&!(journal.optBoolean("capturePaused")&&journal.optBoolean("reviewPaused"))
  val queued=enabled&&listOf("capture","review").any{row!!.s(it) in listOf("queued","running")&&!row.optBoolean(it+"Paused")&&!batch!!.optBoolean(it+"Paused")}
  val counts=journal.optJSONObject("viewCounts")
  val finished=journal.optBoolean("fullyComplete")&&counts?.optBoolean("captureComplete")==true&&!counts.isNull("rated")&&counts.optInt("rated",-1)==counts.optInt("savedDetails",-2)
  val state=when{singleRunning||batchRunning->"running";finished->"complete";queued->"queued";task.s("state") in listOf("partial","failed","interrupted")->task.s("state");else->"paused"}
  view.put("state",state)
  if(state=="paused"&&task.s("state") in listOf("queued","running","complete"))view.put("message","任务已暂停，断点已保存。最近记录："+task.s("message").ifBlank{"等待继续"})
  return view
 }
 fun company(company:JSONObject,task:JSONObject?,jobs:List<JSONObject>?=null):JSONObject {
  val c=RefreshEfficiency.shallow(company)
  fun withMatches():JSONObject {
   val current=jobs?.filter{it.s("companyId")==company.s("id")&&CompanySourceIdentity.currentJob(it)}
   if(!current.isNullOrEmpty()){
    val counts=c.optJSONObject("snapshot")?.let{RefreshEfficiency.shallow(it)}?:JSONObject()
    counts.put("matched",current.count{it.s("grade") in listOf("S","A","B")});c.put("snapshot",counts)
   }
   return c
  }
  if(task==null)return withMatches()
  val r=task.optJSONObject("refreshCounts")?:task.optJSONObject("result")?:return withMatches()
  if(r.has("total")&&!r.isNull("total")){
   val matched=when{r.has("matched")&&!r.isNull("matched")->r.opt("matched");else->JSONObject.NULL}
   val known=r.optInt("total")!=0||!r.has("listingComplete")||r.optBoolean("listingComplete")
   c.put("snapshot",json("total" to if(known)r.opt("total")else JSONObject.NULL,"matched" to if(known)matched else JSONObject.NULL))
  }
  c.put("latestTask",task.s("id")).put("lastResultState",task.s("state"))
  val warning=task.s("state") in listOf("partial","failed","interrupted")||r.optInt("entryFailures")>0||r.optInt("failedDetails")>0
  c.put("refreshWarning",warning);if(warning)c.put("refreshError",RefreshProgress.failureDetails(task).ifBlank{task.s("message")})else c.remove("refreshError")
  if(task.s("updatedAt").isNotBlank())c.put("lastAttempt",task.s("updatedAt"))
  return withMatches()
 }
 fun rebind(batch:JSONObject,records:JSONObject):JSONObject=JSONObject(batch.toString()).apply{
  var changed=false
  for(row in RefreshBatchQueue.entries(this)){
   val j=records.optJSONObject(row.s("companyId"))?:continue
   val invalidated=j.optBoolean("adapterUpgradePending")&&!j.getJSONObject("viewCounts").optBoolean("captureComplete")&&(row.optBoolean("captureComplete")||row.s("capture")=="done"||row.s("state")=="complete")
   if(j.s("taskId").isBlank()||row.s("taskId")==j.s("taskId")&&!invalidated)continue
   if(invalidated)put("state","paused").put("capturePaused",true).put("reviewPaused",true)
   val c=j.getJSONObject("viewCounts");val captured=c.optBoolean("captureComplete");val scored=!c.isNull("rated")&&c.optInt("rated",-1)==c.optInt("savedDetails",-2)
   val complete=j.optBoolean("fullyComplete")&&captured&&scored
   row.put("taskId",j.s("taskId")).put("newCaptureRound",false).put("captureComplete",captured).put("capture",if(captured)"done"else"queued").put("review",if(scored)"done"else"queued").put("state",if(complete)"complete"else"queued")
   if(c.optInt("savedDetails")>0&&!scored)row.put("reviewSavedFirst",true)
   for(lane in listOf("capture","review")){row.remove(lane+"Retryable");row.remove(lane+"SupplementRetried")}
   changed=true
  }
  if(changed&&s("state")=="complete"&&RefreshBatchQueue.entries(this).any{it.s("state")!="complete"})put("state","paused").put("capturePaused",true).put("reviewPaused",true)
 }
 fun batch(batch:JSONObject,tasks:List<JSONObject>):JSONObject=RefreshEfficiency.shallow(batch).apply{
  put("entries",JSONArray(RefreshBatchQueue.entries(batch).map{raw->
   val row=RefreshEfficiency.shallow(raw);val task=tasks.firstOrNull{it.s("companyId")==row.s("companyId")}
   if(task!=null){val c=task.optJSONObject("refreshCounts");val changed=row.s("taskId")!=task.s("id");row.put("taskId",task.s("id"));
    if(changed){row.put("capture",if(c?.optBoolean("captureComplete")==true)"done"else"queued").put("review",if(c!=null&&!c.isNull("rated")&&c.optInt("rated",-1)==c.optInt("savedDetails",-2))"done"else"queued")}
if(c?.has("captureComplete")==true)row.put("captureComplete",c.optBoolean("captureComplete"))
    if(task.s("state")=="running"&&row.s("capture")!="running"&&row.s("review")!="running")row.put(if(c?.optBoolean("captureComplete")==true)"review"else"capture","running")
    row.put("state",task.s("state"));if(task.s("state")=="complete")row.put("capture","done").put("review","done")
    else if(task.s("state")!="running"){if(row.s("capture")=="running")row.put("capture","queued");if(row.s("review")=="running")row.put("review","queued")}
   };row
  }))
  if(s("state")=="complete"&&arr("entries").any{it.s("state")!="complete"})put("state","partial")
 }
}
