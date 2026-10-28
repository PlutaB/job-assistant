package chat.plutab.job27

import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.time.Instant

/** Public Workday CXS pages use a durable offset, independent of a WebView renderer. */
data class WorkdaySite(val origin:String,val tenant:String,val site:String){
 val endpoint get()="$origin/wday/cxs/$tenant/$site"
 fun publicUrl(path:String):String{require(path.startsWith("/job/")&&!path.contains("..")&&!path.contains('?')&&!path.contains('#')){"岗位路径无效"};return "$origin/$site$path"}
 companion object {
  fun from(url:String):WorkdaySite?=runCatching{
   val u=URI(url);val host=u.host?:return null
   if(u.scheme!="https"||u.rawUserInfo!=null||u.port !in listOf(-1,443)||!Regex("[a-zA-Z0-9-]+\\.wd[0-9]+\\.myworkdayjobs\\.com").matches(host))return null
   val parts=u.path.split('/').filter{it.isNotBlank()};val site=parts.firstOrNull{!Regex("[a-z]{2}-[A-Z]{2}").matches(it)}?:return null
   if(!Regex("[a-zA-Z0-9_-]+").matches(site))return null
   WorkdaySite("https://$host",host.substringBefore('.'),site)
  }.getOrNull()
 }
}
object WorkdayJobs {
 const val pageSize=20
 fun rows(site:WorkdaySite,page:JSONObject,known:List<JSONObject>):List<JSONObject>{
  val prior=known.filter{WorkdaySite.from(it.s("url"))==site}.associateBy{URI(it.s("url")).path.substringAfter("/job/","")}
  return page.arr("jobPostings").map{r->val path=r.s("externalPath");val url=prior[path.substringAfter("/job/","")]?.s("url")?:site.publicUrl(path)
   json("url" to url,"text" to r.s("title"),"title" to r.s("title"),"officialId" to r.strings("bulletFields").firstOrNull().orEmpty())
  }
 }
 fun detailUrl(site:WorkdaySite,url:String):String{require(WorkdaySite.from(url)==site);val path=URI(url).rawPath.substringAfter("/job/","");require(path.isNotBlank()&&!URI(url).path.substringAfter("/job/","").contains(".."));return site.endpoint+"/job/"+path}
 fun begin(entry:JSONObject){if(!entry.optBoolean("workdayNative")){entry.put("workdayNative",true).put("workdayOffset",0).put("nextPage",1).put("keys",JSONArray()).put("total",JSONObject.NULL).put("done",false)}}
 /** CXS reports the real total on page one; some tenants send total=0 on later nonempty pages. */
 fun listingTotal(entry:JSONObject,offset:Int,page:JSONObject):Int {
  require(page.has("total")){"官网未返回岗位总数"}
  val reported=page.getInt("total");val saved=entry.optInt("total",-1)
  if(offset>0&&reported==0&&page.arr("jobPostings").isNotEmpty()&&saved>0)return saved
  return reported
 }
 fun commit(j:JSONObject,entry:JSONObject,offset:Int,rows:List<JSONObject>,total:Int){
  require(total>=0&&offset==entry.optInt("workdayOffset")){"岗位分页位置不一致：请求 $offset，断点 ${entry.optInt("workdayOffset")}，本页总数 $total"}
  require(rows.isNotEmpty()||total==0){"官网未返回本页岗位，保留断点"}
  if(rows.size>pageSize||offset+rows.size>total){
   entry.put("workdayOffset",0).put("nextPage",1).put("keys",JSONArray()).put("total",JSONObject.NULL).put("done",false)
   error("官网分页数量与已保存总数不一致，保留详情并重新核对首页")
  }
  entry.put("nextPage",offset/pageSize+1)
  try{RefreshJournal.addPage(j,entry,rows,total,offset+rows.size<total)}catch(e:Exception){entry.put("workdayOffset",0);throw e}
  if(!entry.optBoolean("done"))require(entry.optInt("nextPage")>offset/pageSize+1){"本页未核齐官网总数，保留断点"}
  entry.put("workdayOffset",offset+rows.size)
 }
 fun fact(site:WorkdaySite,url:String,raw:JSONObject):JSONObject{
  val info=raw.getJSONObject("jobPostingInfo")
  val description=info.s("jobDescription").replace(Regex("(?i)</(?:p|div|li)>|<br\\s*/?>"),"\n").replace(Regex("<[^>]*>")," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace(Regex("[ \t]+\\n"),"\n").replace(Regex("\\n[ \t]+"),"\n").trim()
  require(info.s("title").isNotBlank()&&description.isNotBlank()){"官网岗位详情不完整"}
  require(WorkdaySite.from(url)==site)
  return json("url" to url,"title" to info.s("title"),"officialId" to info.s("jobReqId"),"description" to description,"location" to info.s("location"),"department" to info.s("department"),"recruitmentType" to info.s("timeType"),"publishedAt" to PhoneJobs.date(info.s("startDate")),"active" to if(info.optBoolean("canApply",true))"open" else "closed","verifiedAt" to Instant.now().toString(),"evidence" to "official-workday-cxs")
 }
}

internal suspend fun workdayRequest(url:String,body:JSONObject?):JSONObject=cancellableHttp{bind->
 val u=URI(url);require(WorkdaySite.from("https://${u.host}/site")!=null&&u.path.startsWith("/wday/cxs/"))
 val c=URL(url).openConnection() as HttpsURLConnection;bind(c);val deadline=RequestDeadline(c,45000)
 try{
  c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000
  c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Mozilla/5.0")
  if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
  if(c.responseCode !in 200..299)throw RefreshHttpFailure(c.responseCode,RefreshFailures.retryAfter(c.getHeaderField("Retry-After")))
  val text=c.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()};require(text.length<=4000000){"招聘网站响应异常过大"};JSONObject(text)
 }finally{deadline.close();c.disconnect()}
}

class WorkdayCollector(private val fetch:suspend(String,JSONObject?)->JSONObject=::workdayRequest){
 suspend fun collect(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit,reconcile:Boolean=true){
  val site=WorkdaySite.from(entry.s("url"))?:error("非Workday入口")
  val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j)
  val audit=json("startedAt" to Instant.now().toString(),"savedDetailsBefore" to facts.length(),"listingRequests" to 0,"detailRequests" to 0,"cachedDetailsSkipped" to 0)
  entry.put("lastCaptureAudit",audit)
  try{
   if(!entry.optBoolean("done")||!entry.optBoolean("workdayNative")){
    WorkdayJobs.begin(entry);save()
    while(!entry.optBoolean("done")){
     currentCoroutineContext().ensureActive()
     retryRefreshPoint(onRetry={attempt,error->save();stage("retry","同一列表页自动重试 $attempt：$error")}){
      val offset=entry.optInt("workdayOffset")
      stage("listing","直接读取招聘列表第${offset/WorkdayJobs.pageSize+1}页；已保存 ${q.length()} 个岗位链接")
      audit.put("listingRequests",audit.optInt("listingRequests")+1)
      val page=fetch(site.endpoint+"/jobs",json("appliedFacets" to JSONObject(),"limit" to WorkdayJobs.pageSize,"offset" to offset,"searchText" to ""))
      require(page.has("total")){"官网未返回岗位总数"}
      val rows=WorkdayJobs.rows(site,page,RefreshJournal.objects(q))
      entry.put("lastReportedTotal",page.getInt("total"));WorkdayJobs.commit(j,entry,offset,rows,WorkdayJobs.listingTotal(entry,offset,page));save()
     }
    }
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"列表读取失败").put("retryableError",RefreshFailures.temporary(e));save();stage("listing","⚠️ 当前入口已保留断点，继续已保存岗位及下一入口")}
  var missingOnOfficialSite=false
  val forbidden=mutableSetOf<String>()
  val queuePositions=q.keys().asSequence().withIndex().associate{it.value to it.index+1}
  val savedSiteKeys=if(!reconcile)emptyList()else q.keys().asSequence().filter{WorkdaySite.from(q.getJSONObject(it).s("url"))==site}.toList()
  for(k in (entry.strings("keys")+savedSiteKeys).distinct()){
   if(facts.has(k)){audit.put("cachedDetailsSkipped",audit.optInt("cachedDetailsSkipped")+1);continue}
   currentCoroutineContext().ensureActive();val row=q.optJSONObject(k)?:continue
   j.put("currentDetail",json("index" to queuePositions[k],"url" to row.s("url"),"title" to row.s("text")))
   try{
    stage("details","核对详情 ${queuePositions[k]?:facts.length()+1}/${q.length()}；已保存 ${facts.length()} 个详情（直接读取）")
    retryRefreshPoint(onRetry={attempt,error->save();stage("retry","同一岗位详情自动重试 $attempt：$error")}){
     audit.put("detailRequests",audit.optInt("detailRequests")+1)
     val raw=fetch(WorkdayJobs.detailUrl(site,row.s("url")),null);val f=WorkdayJobs.fact(site,row.s("url"),raw)
     f.put("sources",JSONArray((row.strings("sources")+row.s("url")).distinct()));facts.put(k,f);RefreshJournal.markFact(j,k);j.getJSONObject("failedDetails").remove(k);j.optJSONObject("retryableDetails")?.remove(k);save();onFact(f,j)
    }
   }catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(k,e.message?:"详情读取失败");val retryable=j.optJSONObject("retryableDetails")?:JSONObject().also{j.put("retryableDetails",it)};if(RefreshFailures.temporary(e))retryable.put(k,true)else retryable.remove(k);save();if(RefreshFailures.status(e)==404){missingOnOfficialSite=true;continue};if(RefreshFailures.status(e)==403)forbidden.add(k);stage("details","详情失败：${row.s("text").take(36)} · ${e.message?:"读取失败"}")}
  }
  val signature=forbidden.sorted().joinToString("|")
  val lastCheck=runCatching{Instant.parse(entry.s("forbiddenCheckedAt"))}.getOrNull()
  val checkForbidden=forbidden.isNotEmpty()&&(entry.s("forbiddenCheckedKeys")!=signature||lastCheck==null||java.time.Duration.between(lastCheck,Instant.now()).toHours()>=6)
  if((missingOnOfficialSite||checkForbidden)&&reconcile){
   if(checkForbidden)entry.put("forbiddenCheckedKeys",signature).put("forbiddenCheckedAt",Instant.now().toString())
   stage("listing","旧岗位详情返回403或404，重新核对官网当前列表；仅在完整列表确认岗位已撤下时移出本轮，已保存详情不重抓")
   entry.put("workdayOffset",0).put("nextPage",1).put("total",JSONObject.NULL).put("keys",JSONArray()).put("done",false)
   save();collect(entry,j,save,onFact,stage,reconcile=false)
  }
 }
}
