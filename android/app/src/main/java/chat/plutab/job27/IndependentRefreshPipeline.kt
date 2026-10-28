package chat.plutab.job27

import kotlinx.coroutines.*
import org.json.JSONObject

/** Independent lanes; a company's journal is owned by only one lane at a time. */
class IndependentRefreshPipeline(
 private val current:()->JSONObject,
 private val update:suspend((JSONObject)->Unit)->Unit,
 private val capture:suspend(String)->BatchStageResult,
 private val review:suspend(String)->BatchStageResult,
 private val reviewHasPendingRatings:suspend(String)->Boolean={true}
){
 suspend fun run()=coroutineScope {
  data class Active(val id:String,val lane:String,val job:Job)
  val active=mutableListOf<Active>()
  val pendingChecked=mutableSetOf<String>()
  suspend fun start(id:String,lane:String):Active {
   update{RefreshBatchQueue.entry(it,id).put(lane,"running").put("state","running")}
   val job=launch {
    try{
     val result=if(lane=="capture")capture(id)else review(id)
     update{val e=RefreshBatchQueue.entry(it,id);e.put(lane,"done").put(lane+"Message",result.message).put(lane+"Retryable",result.retryable)
      if(result.taskId.isNotBlank())e.put("taskId",result.taskId)
      if(lane=="capture"){pendingChecked.remove(id);e.put("captureComplete",result.complete).put("review","queued");e.remove("reviewSavedFirst")}
      else if(result.complete)e.put("capture","done").put("captureComplete",true)
      e.put("state",if(e.s("capture")=="done"&&e.optBoolean("captureComplete")&&e.s("review")=="done"&&result.complete)"complete" else if(lane=="capture"&&result.complete)"queued" else "partial")
      if(lane=="review"&&result.message.contains("待审核 0"))e.put("reviewMessage","已保存详情审核完成 · 抓取未完成")
     }
    }catch(e:CancellationException){
     if(e is RefreshResourcePause)this@coroutineScope.cancel(e)
     withContext(NonCancellable){update{RefreshBatchQueue.entry(it,id).put(lane,"queued").put("state","queued")}}
     throw e
    }catch(e:Exception){
     update{val row=RefreshBatchQueue.entry(it,id);row.put(lane+"Message",e.message?:"任务未完成").put("state","partial").put(lane+"Retryable",RefreshFailures.temporary(e))
      if(e is GptQuotaExhausted){it.put("quotaBlocked",true).put("reviewPaused",true).put("error",e.message);row.put(lane,"queued")}
      else row.put(lane,"done")
     }
    }
   }
   return Active(id,lane,job)
  }
  try{
   while(true){
    ensureActive()
    for(a in active.toList()){
     val b=current();val row=RefreshBatchQueue.entry(b,a.id)
     if(b.optBoolean(a.lane+"Paused")||row.optBoolean("companyPaused")||row.optBoolean(a.lane+"Paused")){a.job.cancelAndJoin();active.remove(a)}
     else if(a.job.isCompleted){a.job.join();active.remove(a)}
    }
    val before=current()
    if(!before.optBoolean("reviewPaused")&&!before.optBoolean("quotaBlocked")){
     val empty=RefreshBatchQueue.entries(before).filter{row->
      row.s("taskId").isNotBlank()&&row.s("review")!="done"&&!row.optBoolean("companyPaused")&&!row.optBoolean("reviewPaused")&&active.none{it.id==row.s("companyId")}&&row.s("companyId") !in pendingChecked&&!reviewHasPendingRatings(row.s("companyId")).also{if(it)pendingChecked.add(row.s("companyId"))}
     }.map{it.s("companyId")}.toSet()
     if(empty.isNotEmpty())update{batch->for(row in RefreshBatchQueue.entries(batch))if(row.s("companyId") in empty){
      // No worker, running state or GPT request when all saved details are already rated.
      row.put("review","done").put("reviewMessage","待审核 0").put("reviewRetryable",false);row.remove("reviewSavedFirst")
      if(row.s("capture")=="done"&&row.optBoolean("captureComplete"))row.put("state","complete")
     }}
    }
    val b=current()
    for(lane in listOf("review","capture")){
     if(active.any{it.lane==lane}||b.optBoolean(lane+"Paused")||(lane=="review"&&b.optBoolean("quotaBlocked")))continue
     val row=RefreshBatchQueue.entries(current()).firstOrNull{e->
      !e.optBoolean("companyPaused")&&!e.optBoolean(lane+"Paused")&&e.s("state")!="complete"&&e.s(lane)!="done"&&active.none{it.id==e.s("companyId")}&&
       (if(lane=="review")e.s("taskId").isNotBlank()&&(e.s("capture")=="done"||e.optBoolean("reviewSavedFirst")||b.optBoolean("capturePaused"))
        else !(e.optBoolean("reviewSavedFirst")&&e.s("review")!="done"&&!b.optBoolean("reviewPaused")&&!b.optBoolean("quotaBlocked")))
     }
     if(row!=null)active.add(start(row.s("companyId"),lane))
    }
    if(active.isEmpty()){
     var retry=false
     update{batch->for(row in RefreshBatchQueue.entries(batch)){
      if(row.optBoolean("companyPaused"))continue
      for(lane in listOf("capture","review"))if(!batch.optBoolean(lane+"Paused")&&!row.optBoolean(lane+"Paused")&&!(lane=="review"&&batch.optBoolean("quotaBlocked"))&&row.optBoolean(lane+"Retryable")&&!row.optBoolean(lane+"SupplementRetried")){
       row.put(lane+"SupplementRetried",true).put(lane,"queued").put("state","queued");retry=true
      }
     }}
     if(!retry)break
    }
    delay(200)
   }
   update{b->b.put("state",if(RefreshBatchQueue.entries(b).all{it.s("state")=="complete"})"complete" else if(b.optBoolean("capturePaused")||b.optBoolean("reviewPaused")||b.optBoolean("quotaBlocked")||RefreshBatchQueue.entries(b).any{it.optBoolean("companyPaused")||it.optBoolean("capturePaused")||it.optBoolean("reviewPaused")})"paused" else "partial")}
  }catch(e:CancellationException){
   withContext(NonCancellable){for(a in active)a.job.cancelAndJoin();update{b->b.put("state","paused").put("capturePaused",true).put("reviewPaused",true);for(row in RefreshBatchQueue.entries(b))for(lane in listOf("capture","review"))if(row.s(lane)=="running")row.put(lane,"queued")}}
   throw e
  }
 }
}
