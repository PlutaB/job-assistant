package chat.plutab.job27

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class BatchStageResult(val complete:Boolean,val message:String="",val taskId:String="",val retryable:Boolean=false)

object RefreshBatchQueue {
 fun create(companies:List<JSONObject>)=json("id" to UUID.randomUUID().toString(),"state" to "running","createdAt" to Instant.now().toString(),"entries" to JSONArray(companies.map{json("companyId" to it.s("id"),"name" to it.s("name"),"capture" to "queued","newCaptureRound" to true,"review" to "queued","state" to "queued")}))
 fun entries(batch:JSONObject)=batch.arr("entries")
 fun entry(batch:JSONObject,id:String)=entries(batch).first{it.s("companyId")==id}
 fun resume(batch:JSONObject,completedTaskIds:Set<String> = emptySet(),reviewSavedOnly:Boolean=false):JSONObject=JSONObject(batch.toString()).apply{
  put("state","running");remove("finishedAt");remove("error");remove("quotaBlocked")
  for(item in entries(this)){
   if(item.s("taskId").isNotBlank()&&item.s("taskId") in completedTaskIds)item.put("capture","done").put("review","done").put("captureComplete",true).put("state","complete")
   if(item.s("state")=="complete")continue
   if(item.s("capture")=="running"||(!reviewSavedOnly&&item.s("review")=="done"&&!item.optBoolean("captureComplete"))){item.put("capture","queued")}
   if(item.s("taskId").isNotBlank()&&item.s("capture")!="done")item.put("reviewSavedFirst",true)
   item.put("review","queued").put("state","queued")
  }
 }
 fun label(item:JSONObject)=when{
  item.optBoolean("companyPaused")->"任务暂停"
  item.s("review")=="running"->"评分中"
  item.s("capture")=="running"->"抓取中"
  item.s("state")=="complete"->"完成"
  item.s("state")=="partial"&&item.s("review")=="done"&&!item.optBoolean("captureComplete")->"⚠️ 抓取有断点 · 评分已处理"
  item.s("state")=="partial"->"⚠️ 断点已保存"
  item.s("capture")=="done"->"等待评分"
  else->"排队中"
 }
}

/** One ordered capture producer and one ordered GPT consumer; all state is persisted. */
class RefreshBatchPipeline(
 private val current:()->JSONObject,
 private val update:suspend((JSONObject)->Unit)->Unit,
 private val capture:suspend(String)->BatchStageResult,
 private val review:suspend(String)->BatchStageResult
){
 suspend fun run(){
  val ids=RefreshBatchQueue.entries(current()).map{it.s("companyId")}
  var quota:GptQuotaExhausted?=null
  try{
   // Resume saved reviews before restarting incomplete capture. Never run two GPT lanes.
   for(id in ids){
    val item=RefreshBatchQueue.entry(current(),id)
    if(!item.optBoolean("reviewSavedFirst")||item.s("review")=="done")continue
    currentCoroutineContext().ensureActive()
    update{RefreshBatchQueue.entry(it,id).put("review","running").put("state","running")}
    var savedResult:BatchStageResult?=null
    try{savedResult=review(id)}catch(e:Exception){
     if(e is CancellationException)throw e
     if(e is GptQuotaExhausted){quota=e;update{it.put("quotaBlocked",true).put("error",e.message)}}
     else update{RefreshBatchQueue.entry(it,id).put("reviewMessage",e.message?:"已有岗位审核未完成")}
    }
    update{val e=RefreshBatchQueue.entry(it,id);e.remove("reviewSavedFirst")
     if(savedResult?.complete==true){e.put("capture","done").put("captureComplete",true).put("review","done").put("state","complete");if(savedResult!!.taskId.isNotBlank())e.put("taskId",savedResult!!.taskId)}
     else e.put("review","queued").put("state","queued")
    }
    if(quota!=null)break
   }
   coroutineScope {
    val ready=Channel<String>(Channel.UNLIMITED)
    launch {
     try{for(id in ids){
      ensureActive()
      var item=RefreshBatchQueue.entry(current(),id)
      if(item.s("review")=="done")continue
      if(item.s("capture")!="done"){
       update{RefreshBatchQueue.entry(it,id).put("capture","running").put("state","running")}
       val result=try{capture(id)}catch(e:Exception){if(e is RefreshResourcePause){this@coroutineScope.cancel(e);throw e};if(e is CancellationException)throw e;BatchStageResult(false,e.message?:"抓取未完成")}
       update{RefreshBatchQueue.entry(it,id).put("capture","done").put("captureComplete",result.complete).put("captureMessage",result.message).put("taskId",result.taskId)}
      }
      ready.send(id)
     }}finally{ready.close()}
    }
    launch {
     for(id in ready){
      ensureActive()
      if(quota!=null)continue
      update{RefreshBatchQueue.entry(it,id).put("review","running").put("state","running")}
      val result=try{review(id)}catch(e:Exception){
       if(e is RefreshResourcePause){this@coroutineScope.cancel(e);throw e}
       if(e is CancellationException)throw e
       if(e is GptQuotaExhausted){quota=e;update{it.put("quotaBlocked",true).put("error",e.message);RefreshBatchQueue.entry(it,id).put("review","queued").put("state","partial").put("reviewMessage",e.message)};continue}
       BatchStageResult(false,e.message?:"审核未完成")
      }
      update{val item=RefreshBatchQueue.entry(it,id);item.put("review","done").put("reviewMessage",result.message).put("state",if(item.optBoolean("captureComplete")&&result.complete)"complete" else "partial");if(result.taskId.isNotBlank())item.put("taskId",result.taskId)}
     }
    }
   }
   if(quota!=null){update{it.put("state","paused").put("quotaBlocked",true)};return}
   update{it.put("state",if(RefreshBatchQueue.entries(it).all{e->e.s("state")=="complete"})"complete" else "partial").put("finishedAt",Instant.now().toString())}
  }catch(e:CancellationException){
   withContext(NonCancellable){update{it.put("state","paused");for(item in RefreshBatchQueue.entries(it)){if(item.s("capture")=="running")item.put("capture","queued");if(item.s("review")=="running")item.put("review","queued")}}}
   throw e
  }
 }
}
