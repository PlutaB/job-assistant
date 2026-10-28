package chat.plutab.job27

import org.json.JSONObject

/** Starting one lane must not revive another lane's stale enabled flag. */
object RefreshLaneControl {
 private fun other(lane:String)=if(lane=="capture")"review"else"capture"
 fun batch(batch:JSONObject,lane:String,pause:Boolean){
  if(!pause&&batch.s("state")!="running")batch.put(other(lane)+"Paused",true)
  batch.put(lane+"Paused",pause)
  if(!pause)batch.put("state","running")
 }
 fun company(batch:JSONObject,id:String,lane:String,pause:Boolean){
  val row=RefreshBatchQueue.entry(batch,id)
  if(!pause){
   val stopped=batch.s("state")!="running"
   val opposite=other(lane)
   // Preserve the other lane's effective stopped state, including company-wide pause.
   if(stopped)batch.put(opposite+"Paused",true)
   if(stopped||row.optBoolean("companyPaused")||batch.optBoolean(opposite+"Paused"))row.put(opposite+"Paused",true)
   // A company button may resume a globally paused lane only for this company.
   if(stopped||batch.optBoolean(lane+"Paused")){
    for(e in RefreshBatchQueue.entries(batch))if(e.s("companyId")!=id)e.put(lane+"Paused",true)
   }
   batch.put(lane+"Paused",false).put("state","running")
   row.put("companyPaused",false).put(lane,"queued").put("state","queued")
   row.remove(lane+"Retryable");row.remove(lane+"SupplementRetried")
  }
  row.put(lane+"Paused",pause)
 }
}
