package chat.plutab.job27

import android.content.Context
import org.json.JSONObject
import java.io.File

object CaptureIssueReport {
 fun read(context:Context,companies:List<JSONObject>):String {
  val names=companies.associate{it.s("id") to it.s("name")}
  val rows=mutableListOf<JSONObject>()
  val known=mutableSetOf<String>()
  for(file in File(context.filesDir,"refresh-journal").listFiles().orEmpty().filter{it.extension=="json"}.sortedBy{it.name}){
   try{val j=RefreshJournal(context,file.nameWithoutExtension).issueSnapshot()?:continue
    val id=j.s("companyId",file.nameWithoutExtension);known.add(id);rows.add(j.put("companyName",names[id]?:id))
   }catch(e:Exception){rows.add(json("companyName" to (names[file.nameWithoutExtension]?:file.nameWithoutExtension),"readError" to (e.message?:"断点读取失败")))}
  }
  for(c in companies)if(c.s("id") !in known)rows.add(json("companyId" to c.s("id"),"companyName" to c.s("name"),"state" to "无已保存断点"))
  return format(rows,BuildConfig.VERSION_NAME,java.time.Instant.now().toString())
 }
 internal fun clean(text:String):String {
  var value=text.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+"),"$1[已隐藏]")
  value=value.replace(Regex("(?i)((?:access_token|refresh_token|authorization|password|cookie|session|signature|email|phone|openid|operational|sourceToken|token|secret|code)=)[^&#\\s]+"),"$1[已隐藏]")
  return value
 }
 private fun activeEntries(row:JSONObject)=row.arr("entries").filter{!it.optBoolean("done")&&it.s("kind")!="reference"}
 private fun resolved(row:JSONObject):Boolean{
  val c=row.optJSONObject("viewCounts")
  if(c!=null){val total=c.optInt("total",-1);val saved=c.optInt("savedDetails",-1);val rated=c.optInt("rated",-1);if(total>=0&&saved>=0&&saved<total||saved>=0&&rated>=0&&rated<saved)return false}
  return row.s("state")=="complete"&&row.optBoolean("listingComplete")&&activeEntries(row).isEmpty()&&row.arr("missingDetails").isEmpty()&&row.s("readError").isBlank()
 }
 internal fun status(row:JSONObject):String {
  val c=row.optJSONObject("viewCounts")
  val captureIssue=activeEntries(row).isNotEmpty()||row.arr("missingDetails").isNotEmpty()||row.s("readError").isNotBlank()||c?.let{it.optInt("total",-1)>it.optInt("savedDetails",Int.MAX_VALUE)}==true
  return when{
   row.s("state")=="running"->"正在运行"
   captureIssue->"抓取未完成"
   c!=null&&c.optInt("rated",-1)>=0&&c.optInt("rated")<c.optInt("savedDetails",-1)->"等待评分"
   resolved(row)->"已完成"
   else->row.s("state")
  }
 }
 private fun pauseRecord(row:JSONObject)=if(row.s("state") in listOf("paused","partial","failed","interrupted"))row.s("lastError")else ""
 fun format(rows:List<JSONObject>,version:String,at:String):String=buildString{
  val shown=rows.filterNot(::resolved)
  append("求职助手 · 当前未完成问题列表\n版本：$version\n生成时间：$at\n公司记录：${rows.size} · 已完成 ${rows.size-shown.size} · 未完成 ${shown.size}\n")
  append("已完成公司、完成入口和旧暂停记录不重复列出；岗位、评分及断点保留。只读取本机记录，不发起抓取或评分。\n\n")
  val capture=shown.filter{status(it)=="抓取未完成"};val waiting=shown.filter{status(it)=="等待评分"};val running=shown.filter{status(it)=="正在运行"}
  append("抓取未完成 ${capture.size} · 等待评分 ${waiting.size} · 正在运行 ${running.size}\n\n")
  val groups=linkedMapOf<String,MutableSet<String>>()
  for(row in shown){val messages=activeEntries(row).map{it.s("error")}+row.arr("missingDetails").map{it.s("error")}+listOf(pauseRecord(row),row.s("readError"))
   for(error in messages.filter{it.isNotBlank()}){
    if(error=="任务暂停，断点已保存"||error=="StandaloneCoroutine was cancelled"||error=="StandaloneCoroutine was cancelled.")continue
    val kind=when{
     Regex("用量已用完|用量耗尽|额度不足|usage.limit|subscription_sharing_usage_limit",RegexOption.IGNORE_CASE).containsMatchIn(error)->"评分额度暂停记录"
     Regex("内存|严重过热|电量低于|低电量").containsMatchIn(error)->"资源保护记录"
     Regex("HTTP\\s*403",RegexOption.IGNORE_CASE).containsMatchIn(error)->"出现 HTTP 403"
     Regex("HTTP\\s*404",RegexOption.IGNORE_CASE).containsMatchIn(error)->"出现 HTTP 404"
     Regex("HTTP\\s*429",RegexOption.IGNORE_CASE).containsMatchIn(error)->"官网请求限流（HTTP 429）"
     Regex("HTTP\\s*999",RegexOption.IGNORE_CASE).containsMatchIn(error)->"LinkedIn拒绝访问（HTTP 999）"
     error.contains("占位符")->"官网正文为占位符"
     error.contains("微信")->"官网要求微信入口"
     error.contains("证书")->"官网证书验证失败"
     error.contains("分页")->"分页异常"
     error.contains("网络")||error.contains("超时")->"网络或超时记录"
     error.contains("详情")||error.contains("验证")->"详情未取得或页面验证"
     else->"其他错误记录"
    }
    groups.getOrPut(kind){linkedSetOf()}.add(row.s("companyName"))
   }
  }
  if(groups.isEmpty())append("没有已保存的当前错误；未完成进度见下方。\n")
  for((kind,names)in groups)append("$kind：${clean(names.joinToString("、"))}\n")
  append("\n")
  for((i,row)in shown.withIndex()){
   val c=row.optJSONObject("viewCounts")
   fun count(key:String):String{
    if(c!=null&&c.has(key)&&!c.isNull(key))return c.opt(key).toString()
    return when{
     key=="total"&&row.has("queueCount")->row.optInt("queueCount").toString()
     key=="savedDetails"&&row.has("savedCount")->row.optInt("savedCount").toString()
     key=="rated"&&row.optBoolean("fullyComplete")&&row.has("savedCount")->row.optInt("savedCount").toString()
     else->"未知"
    }
   }
   append("${i+1}. ${clean(row.s("companyName"))} · ${status(row)}\n")
   append("查看 ${count("pagesViewed")} 页 · 抓取 ${count("savedDetails")}/${count("total")} · 评分 ${count("rated")}/${count("savedDetails")}\n")
   if(row.s("readError").isNotBlank())append("读取失败：${clean(row.s("readError"))}\n")
   if(pauseRecord(row).isNotBlank())append("最近暂停：${clean(pauseRecord(row))}\n")
   val entries=activeEntries(row)
   if(entries.isNotEmpty()||row.arr("missingDetails").isNotEmpty())append("公司ID：${row.s("companyId")} · 任务：${row.s("taskId")}\n")
   for((n,e)in entries.withIndex()){
    append("未完成入口 ${n+1}：${clean(e.s("url"))}\n")
    append("  第${e.optInt("nextPage",1)}页 · 官网总数=${if(e.has("total")&&!e.isNull("total"))e.opt("total")else "未知"}\n")
    if(e.has("reportedInternCount"))append("  官网报告实习岗位数：${e.optInt("reportedInternCount")}（尚未取得清单）\n")
    if(e.s("effectiveListingUrl").isNotBlank()&&e.s("effectiveListingUrl")!=e.s("url"))append("  实际职位入口：${clean(e.s("effectiveListingUrl"))}\n")
    if(e.s("error").isNotBlank())append("  原因：${clean(e.s("error"))}\n")
   }
   for(m in row.arr("missingDetails"))append("未取得详情：${clean(m.s("title"))}\n  ${clean(m.s("url"))}\n  原因：${clean(m.s("error"))}\n")
   val pending=row.s("state")!="complete"&&(c==null||c.optInt("rated",-1)<c.optInt("savedDetails",Int.MAX_VALUE))
   if(pending)row.optJSONObject("ratingErrors")?.let{errors->for(error in errors.keys().asSequence().map{errors.s(it)}.distinct())append("${if(row.s("state")=="complete")"历史评分错误"else "评分错误"}：${clean(error)}\n")}
   append("\n")
  }
 }
}
