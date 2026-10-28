package chat.plutab.job27

import org.json.JSONObject

/** Capture finishing is independent from rating readiness and source completeness. */
internal object CaptureRunOutcome {
 fun state(j:JSONObject,pendingRatings:Int):String=when{
  !RefreshJournal.nativeComplete(j)->"partial"
  pendingRatings>0->"waiting_rating"
  else->"complete"
 }
 fun finish(j:JSONObject,pendingRatings:Int):String {
  val outcome=state(j,pendingRatings)
  // This run returned normally: an older pause is history, not its current failure.
  if(j.s("lastError").isNotBlank())j.put("previousPauseError",j.get("lastError"))
  j.remove("lastError");j.put("fullyComplete",outcome=="complete").put("state",outcome)
  return outcome
 }
 fun message(j:JSONObject,pendingRatings:Int):String {
  if(state(j,pendingRatings)=="complete")return "刷新完成：${RefreshJournal.total(j)}个唯一岗位，详情和评级已保存。"
  val missing=(RefreshJournal.total(j)-RefreshJournal.facts(j).length()).coerceAtLeast(0)
  val reason=j.arr("entries").firstOrNull{!it.optBoolean("done")&&it.s("kind")!="reference"&&it.s("error").isNotBlank()}?.s("error")
   ?:j.getJSONObject("failedDetails").keys().asSequence().firstOrNull{!RefreshJournal.facts(j).has(it)}?.let{j.getJSONObject("failedDetails").s(it)}
  return listOfNotNull(if(missing>0)"抓取尚缺${missing}条详情"else if(!RefreshJournal.nativeComplete(j))"抓取入口未完成"else"抓取完成",if(pendingRatings>0)"待评分 $pendingRatings"else"已保存岗位评分完成",reason).joinToString("；")
 }
}
