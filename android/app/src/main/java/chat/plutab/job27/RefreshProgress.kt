package chat.plutab.job27

import org.json.JSONObject

/** Display only observed task counters; never estimate completion percentages. */
object RefreshProgress {
 fun problems(task:JSONObject?):List<JSONObject>{
  val saved=(task?.optJSONObject("refreshCounts")?:task?.optJSONObject("result"))?.arr("problems").orEmpty().filter{it.s("reason").isNotBlank()}
  if(saved.isNotEmpty())return saved
  val message=task?.s("message").orEmpty()
  return if(task?.s("state") in listOf("paused","partial","failed","interrupted")&&Regex("内存接近|Java.*内存|严重过热|电量低于|低电量|用量耗尽|额度不足|等待网络恢复").containsMatchIn(message))listOf(json("kind" to "task","reason" to message))else emptyList()
 }
 fun shortIssue(reason:String):String=when{
  Regex("HTTP\\s*403",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"官网拒绝访问"
  Regex("HTTP\\s*404",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"官网页面不存在"
  Regex("安全验证|验证码|captcha|页面要求验证|入口需要验证",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"官网要求验证"
  reason.contains("分页没有推进")||reason.contains("分页未推进")->"分页未推进"
  reason.contains("数量")&&reason.contains("变化")||reason.contains("总数校验变化")->"官网数量变化"
  reason.contains("未取得官网总数")->"官网未返回总数"
  reason.contains("未读到岗位")||reason.contains("未返回岗位")->"页面未返回岗位"
  Regex("证书|certificate|CertPath|SSLHandshake",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"官网证书验证失败"
  reason.contains("内存")->if(reason.contains("接近"))"内存接近上限"else"内存不足"
  reason.contains("占位符")->"官网正文是占位符"
  reason.contains("过热")->"手机过热"
  Regex("低电量|电量低").containsMatchIn(reason)->"电量过低"
  Regex("用量耗尽|额度不足").containsMatchIn(reason)->"GPT用量耗尽"
  Regex("网络|timeout|timed out|连接失败",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"网络读取失败"
  Regex("正文|详情不完整|完整岗位说明").containsMatchIn(reason)->"职位正文缺失"
  Regex("HTTP\\s*([0-9]{3})",RegexOption.IGNORE_CASE).containsMatchIn(reason)->"官网错误 "+Regex("HTTP\\s*([0-9]{3})",RegexOption.IGNORE_CASE).find(reason)!!.groupValues[1]
  else->reason.substringBefore('；').substringBefore('。').take(14)
 }
 fun failureDetails(task:JSONObject?):String=problems(task).groupBy{it.s("kind")+"|"+it.s("reason")}.values.joinToString("\n\n"){rows->
  val first=rows.first();val source=first.s("title").ifBlank{runCatching{java.net.URI(first.s("source")).host}.getOrNull().orEmpty()}
  (when(first.s("kind")){"listing"->"列表";"task"->"任务";else->"详情"})+(if(rows.size>1)" ${rows.size} 项"else"")+(if(source.isNotBlank())" · $source"else"")+"："+first.s("reason")
 }
 fun detail(task:JSONObject?):String {
  if(task==null)return ""
  val waiting=task.s("stage")=="loading"
  val m=if(waiting)task.s("progressMessage",task.s("message"))else task.s("message");val count=Regex("(\\d+)\\s*/\\s*(\\d+)").find(m)?.groupValues?.let{it[1]+"/"+it[2]}
  return when(if(waiting)task.s("progressStage")else task.s("stage")){
   "details"->if(count!=null)"核对详情$count" else m.substringBefore('；').take(45)
   "listing"->m.substringBefore('；').take(45)
   "rating"->if(m.contains("重试"))"审核重试"+(count?:"") else if(count!=null)"审核$count" else "审核中"
   "retry"->m.substringBefore('：').take(45)
   "waiting_rating"->if(count!=null)"详情$count · 等待审核" else "等待审核"
   "resuming"->"读取已保存断点"
   else->m.substringBefore('；').take(45)
  }
 }
 fun overview(task:JSONObject?,networkWaiting:Boolean=false):String {
  val r=task?.optJSONObject("refreshCounts")?:task?.optJSONObject("result")
  fun number(key:String)=if(r!=null&&r.has(key)&&!r.isNull(key))r.optInt(key).coerceAtLeast(0).toString()else "—"
  val page=if(r!=null&&r.has("pagesViewed")&&!r.isNull("pagesViewed"))number("pagesViewed")else{
   val pattern=Regex("第\\s*(\\d+)\\s*页")
   val messages=listOfNotNull(task?.s("message"))+task?.arr("events").orEmpty().asReversed().map{it.s("message")}
   messages.firstNotNullOfOrNull{pattern.find(it)?.groupValues?.get(1)}?:"—"
  }
  return "查看 $page 页 · 抓取 ${number("savedDetails")}/${number("total")} · 评分 ${number("rated")}/${number("savedDetails")}"
 }
 fun counts(task:JSONObject?):String? {
  val r=task?.optJSONObject("refreshCounts")?:task?.optJSONObject("result")?:return null
  if(listOf("total","savedDetails").any{!r.has(it)||r.isNull(it)})return null
  val total=r.optInt("total").coerceAtLeast(0)
  val captured=r.optInt("savedDetails").coerceAtLeast(0)
  val rated=if(r.has("rated")&&!r.isNull("rated"))r.optInt("rated").coerceAtLeast(0).toString()else "—"
  return "抓取 $captured/$total · 评分 $rated/$captured"
 }
 data class RowStatus(val text:String,val complete:Boolean=false,val warning:Boolean=false)
 fun rowStatus(item:JSONObject,tasks:List<JSONObject>,blocked:Boolean=false):RowStatus {
  val t=tasks.firstOrNull{it.s("id")==item.s("taskId")}
  val r=t?.optJSONObject("refreshCounts")?:t?.optJSONObject("result")
  val issue=problems(t).firstOrNull()?.s("reason")?.let(::shortIssue).orEmpty()
  if(r!=null&&listOf("total","savedDetails").all{r.has(it)&&!r.isNull(it)}&&(!r.has("rated")||r.isNull("rated"))){
   val total=r.optInt("total");val saved=r.optInt("savedDetails");val parts=mutableListOf<String>()
   if(saved!=total)parts.add("抓取 $saved/$total")else if(!r.optBoolean("captureComplete")&&!item.optBoolean("captureComplete"))parts.add("核对列表")
   if(saved>0)parts.add("评分待核实")
   return RowStatus((if(item.optBoolean("companyPaused"))"已暂停 · "else "")+(issue.ifBlank{parts.joinToString(" · ").ifBlank{"核对列表"}}),warning=issue.isNotBlank())
  }
  if(r!=null&&listOf("total","savedDetails","rated").all{r.has(it)&&!r.isNull(it)}){
   val total=r.optInt("total");val saved=r.optInt("savedDetails");val rated=r.optInt("rated")
   if(total>=0&&saved>=0&&rated>=0){
    val captureDone=saved==total&&(item.optBoolean("captureComplete")||r.optBoolean("listingComplete")||item.s("state")=="complete")
    val reviewDone=rated==saved
    val active=item.s("capture")=="running"||item.s("review")=="running"
    if(captureDone&&reviewDone&&!active)return RowStatus("✓",complete=true)
    val parts=mutableListOf<String>()
    if(saved!=total)parts.add("抓取 $saved/$total")else if(!captureDone)parts.add("核对列表")
    if(!reviewDone)parts.add("评分 $rated/$saved")
    val gap=saved!=total||!reviewDone
    val warning=(item.s("state")=="partial"&&gap)||(!captureDone&&(issue.isNotBlank()||r.optInt("entryFailures")>0||r.optInt("failedDetails")>0))
    var text=when(parts.size){0->"保存结果";1->parts.first();else->parts.joinToString(" · ").replace("抓取 ","抓").replace("评分 ","评")}
    if(warning&&issue.isNotBlank()){
     text=if(saved<total&&!reviewDone){
      val reason=problems(t).first().s("reason");val compact=Regex("HTTP\\s*([0-9]{3})",RegexOption.IGNORE_CASE).find(reason)?.groupValues?.get(1)?:issue.removePrefix("官网").removePrefix("职位").take(4)
      "$saved/$total · $rated/$saved · $compact"
     }else if(saved<total)"$saved/$total · "+issue.removePrefix("官网")else issue
    }
    if(blocked&&!reviewDone)text+=" · 用量耗尽"
    if(item.optBoolean("companyPaused"))text="已暂停 · $text"
    return RowStatus(text,warning=warning)
   }
  }
  if(item.s("state")=="complete")return RowStatus("✓",complete=true)
  val text=when{
   item.optBoolean("companyPaused")->"任务暂停"
   item.s("review")=="running"->"评分中"
   item.s("capture")=="running"->"抓取中"+detail(t).takeIf{it.isNotBlank()}?.let{"·$it"}.orEmpty()
   item.optBoolean("captureComplete")&&item.s("review")=="done"->return RowStatus("✓",complete=true)
   item.optBoolean("captureComplete")->if(blocked)"待评分 · 用量耗尽" else "待评分"
   item.s("capture")=="done"->"核对列表"
   else->"排队中"
  }
  return RowStatus(text)
 }
 fun label(item:JSONObject,tasks:List<JSONObject>,blocked:Boolean=false):String {
  val status=rowStatus(item,tasks,blocked)
  return (if(status.warning)"⚠️ " else "")+status.text
 }
 fun notification(data:JSONObject):String {
  val batch=data.optJSONObject("refreshBatch")
  if(batch?.s("state")=="running"){
   if(batch.optBoolean("networkWaiting"))return "等待网络恢复，断点保留；联网后继续"
   val items=RefreshBatchQueue.entries(batch).filter{it.s("capture")=="running"||it.s("review")=="running"}
   if(items.isNotEmpty())return items.joinToString("\n"){it.s("name")+" · "+label(it,data.arr("tasks"),batch.optBoolean("quotaBlocked"))}
  }
  val task=data.arr("tasks").firstOrNull{it.s("state")=="running"}
  if(task!=null){val name=data.arr("companies").firstOrNull{it.s("id")==task.s("companyId")}?.s("name").orEmpty();return name+" · "+detail(task)}
  return if(data.has("gptQuotaBlock"))"GPT用量耗尽，审核暂停；已保存岗位" else "读取已保存任务，点此查看"
 }
}
