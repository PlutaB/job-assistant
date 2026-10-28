package chat.plutab.job27

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection

/** Public search APIs used by the corresponding official recruitment pages. */
object PublicRecruitmentSources {
 /** Reopen previously misread empty lists without discarding any facts or ratings. */
 fun prepareUpgrade(j:JSONObject):Boolean{
  var changed=false
  val aliases=linkedMapOf<String,String>()
  for(map in listOf(RefreshJournal.queue(j),RefreshJournal.facts(j)))for(old in map.keys().asSequence().toList()){
   val row=map.getJSONObject(old);val canonical=RecruitmentListingUrl.jobIdentity(row.s("url"))?:continue
   if(old==canonical)continue
   val existing=map.optJSONObject(canonical)
   if(existing==null||!existing.optBoolean("ratingReceived")&&row.optBoolean("ratingReceived"))map.put(canonical,row)
   else existing.put("sources",JSONArray((existing.strings("sources")+row.strings("sources")).distinct()))
   if(map===RefreshJournal.facts(j))RefreshJournal.markFact(j,canonical)
   map.remove(old);aliases[old]=canonical;changed=true
  }
  if(aliases.isNotEmpty()){
   for(entry in j.arr("entries")){
    entry.put("keys",JSONArray(entry.strings("keys").map{aliases[it]?:it}.distinct()))
    for(name in listOf("numberedPages","cardPageKeys"))entry.optJSONObject(name)?.let{pages->for(page in pages.keys())pages.put(page,JSONArray(pages.strings(page).map{aliases[it]?:it}.distinct()))}
   }
   val archive=j.optJSONObject("mergedDuplicateKeys")?:JSONObject();for((old,key)in aliases)archive.put(old,key);j.put("mergedDuplicateKeys",archive)
   for(name in listOf("failedDetails","ratingErrors","retryableDetails","manualGrades","detailFetchCount"))j.optJSONObject(name)?.let{map->for(old in aliases.keys)if(map.has(old)){val key=aliases.getValue(old);if(!map.has(key))map.put(key,map.get(old));map.remove(old)}}
   j.optJSONArray("retainedMatches")?.let{j.put("retainedMatches",JSONArray(j.strings("retainedMatches").map{aliases[it]?:it}.distinct()))}
  }
  for(entry in j.arr("entries")){
   val url=entry.s("url");val native=supports(url)&&entry.optInt("publicApiVersion")<1
   val pdf=RecruitmentDocuments.supports(url)&&entry.optInt("pdfAdapterVersion")<1
   val dom=URI(url).let{it.host in setOf("ehr.e-cspc.com","www.moseeker.com","www.bettapharma.com","sc.hotjob.cn","jobs.gsk.com","biocytogen.com.cn")||it.host=="xyz.51job.com"&&it.query.orEmpty().contains("ctmid=9532340")||it.host=="campus.51job.com"&&it.path.startsWith("/Innovent2027/")}
   val menu=menuSource(url)&&entry.optInt("menuAdapterVersion")<1
   val reported=URI(url).let{u->u.host=="jobs.gsk.com"&&u.path.trimEnd('/')=="/gb/en"||RecruitmentListingUrl.verifiedRedirect(url)!=null||u.host=="www.bettapharma.com"&&u.path=="/Jobs/campus"||u.host=="www.sinopharm.com"&&u.path=="/xyzp.html"||u.host=="www.e-cspc.com"&&u.path=="/hr/index.html"||u.host=="campus.51job.com"&&u.path.startsWith("/Innovent2027/")}&&entry.optInt("reportedEntryVersion")<1
   if(native||pdf||menu||reported||dom&&entry.optInt("remainingDomVersion")<1){
    entry.put("done",false);if(menu||reported)entry.put("kind","listing");if(dom||reported){entry.put("remainingDomVersion",1).put("keys",JSONArray()).put("nextPage",1);entry.remove("total")};if(reported)entry.put("reportedEntryVersion",1);changed=true
   }
  }
  if(changed)j.put("listingComplete",false).put("fullyComplete",false)
  return changed
 }
 // Public edition uses user-configured browser sources, with no bundled company endpoints.
 fun supports(url:String):Boolean=false
 private val hosts=setOf("www.ajinga.com","production.api.recruiting-solutions.org","gp-api.iguopin.com","www.novonordisk.com","ehr.e-cspc.com","wecruit.hotjob.cn","gzmpc.hotjob.cn","www.fosunwanbang.com","chinacampus.jnj.com.cn","fe-api.zhaopin.com")
 /** An explicitly expired, empty old campaign is a historical reference, not a current vacancy list. */
 suspend fun retireExpiredActivity(entry:JSONObject,j:JSONObject,save:suspend()->Unit):Boolean{
  val old=URI(entry.s("url"));if(old.host!="sinopharm2026.iguopin.com"||old.path!="/job"||entry.strings("keys").isNotEmpty()||j.arr("entries").none{runCatching{val u=URI(it.s("url"));u.host=="sinopharm2027.iguopin.com"&&u.path=="/job"}.getOrDefault(false)})return false
  if(RefreshJournal.queue(j).keys().asSequence().any{RefreshJournal.queue(j).getJSONObject(it).strings("sources").contains(entry.s("url"))})return false
  val response=request("https://example.invalid")
  if(response.optInt("code")!=14001||response.s("msg")!="活动不存在或者已结束")return false
  entry.put("kind","reference").put("done",true).put("total",0).put("retiredActivity",true).put("retiredActivityEvidence",json("code" to 14001,"message" to response.s("msg"),"at" to java.time.Instant.now().toString()))
  if(entry.s("error").isNotBlank())entry.put("previousError",entry.s("error"));entry.remove("error");entry.remove("retryableError");save();return true
 }
 fun menuSource(url:String)=URI(url).let{it.host=="wecruit.hotjob.cn"&&Regex("/SU[a-f0-9]{24}/mc/index",RegexOption.IGNORE_CASE).matches(it.path)}
 suspend fun expandMenu(entry:JSONObject,j:JSONObject,save:suspend()->Unit,stage:suspend(String,String)->Unit):List<JSONObject>{
  if(entry.optInt("menuAdapterVersion")==1&&entry.optBoolean("done"))return emptyList()
  return try{
   val suite=URI(entry.s("url")).path.split('/')[1];stage("listing","读取官网招聘分类入口")
   val response=request("https://example.invalid")
   val menu=response.getJSONObject("data").getJSONObject("config").getJSONObject("home").getJSONObject("differentStyles").getJSONObject("postType").arr("elementList")
   val targets=menu.mapNotNull{row->val raw=row.s("jumpLink").trim();runCatching{val u=URI(raw);if(u.host!="wecruit.hotjob.cn"||u.userInfo!=null||u.port !in listOf(-1,443)||u.scheme !in listOf("http","https")||!Regex("/SU[a-f0-9]{24}/mc/position/(campus|society|intern)",RegexOption.IGNORE_CASE).matches(u.path))null else json("url" to "https://"+u.rawAuthority+u.rawPath+(u.rawQuery?.let{"?$it"}?:""),"name" to row.s("name"))}.getOrNull()}.distinctBy{it.s("url")}
   require(targets.isNotEmpty()){"官网分类页未返回可读取的职位入口"}
   val existing=j.arr("entries").map{it.s("url")}.toSet();val added=targets.filter{it.s("url") !in existing}.map{json("url" to it.s("url"),"name" to it.s("name"),"nextPage" to 1,"done" to false,"keys" to JSONArray(),"total" to null)}
   for(child in added)j.getJSONArray("entries").put(child)
   entry.put("kind","reference").put("done",true).put("keys",JSONArray()).put("total",0).put("menuAdapterVersion",1).put("linkedListings",JSONArray(targets));entry.remove("error");entry.remove("retryableError");j.put("listingComplete",false);save();added
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"招聘分类入口读取失败");save();emptyList()}
 }
 // The official server omits its intermediate. This is NOT a new trust anchor:
 // both the original and supplemented chain must validate against Android's system roots.
 // Public issuer certificate: https://example.invalid
 private val wanbangTls by lazy {
  val factory=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply{init(null as java.security.KeyStore?)}
  val system=factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
  val intermediate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate("""-----BEGIN CERTIFICATE-----
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
-----END CERTIFICATE-----""".byteInputStream()) as java.security.cert.X509Certificate
  val trust=object:javax.net.ssl.X509TrustManager {
   override fun getAcceptedIssuers()=system.acceptedIssuers
   override fun checkClientTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String)=system.checkClientTrusted(chain,authType)
   override fun checkServerTrusted(chain:Array<java.security.cert.X509Certificate>,authType:String){
    try{system.checkServerTrusted(chain,authType)}catch(original:java.security.cert.CertificateException){
     if(chain.isEmpty()||chain.last().issuerX500Principal!=intermediate.subjectX500Principal)throw original
     intermediate.checkValidity();system.checkServerTrusted(chain+intermediate,authType)
    }
   }
  }
  javax.net.ssl.SSLContext.getInstance("TLS").apply{init(null,arrayOf<javax.net.ssl.TrustManager>(trust),null)}.socketFactory
 }
 internal suspend fun publicHtml(url:String):String=withContext(Dispatchers.IO){cancellableHttp{bind->
  val uri=URI(url);require(uri.scheme=="https"&&uri.host in setOf("www.novonordisk.com","jobs.gsk.com","www.fosunwanbang.com","gzmpc.hotjob.cn","www.zhaopin.com","chinacampus.jnj.com.cn","www.qianmoqi.com")&&uri.port==-1&&uri.userInfo==null)
  val c=URL(url).openConnection() as HttpsURLConnection;if(uri.host=="www.fosunwanbang.com")c.sslSocketFactory=wanbangTls;bind(c);val deadline=RequestDeadline(c,45000)
  try{c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000;c.setRequestProperty("User-Agent",if(uri.host in setOf("gzmpc.hotjob.cn","wecruit.hotjob.cn"))"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"else"Mozilla/5.0")
   if(c.responseCode !in 200..299)throw RefreshHttpFailure(c.responseCode)
   val text=StringBuilder();c.inputStream.bufferedReader(Charsets.UTF_8).use{r->val buffer=CharArray(8192);while(true){currentCoroutineContext().ensureActive();val n=r.read(buffer);if(n<0)break;require(text.length+n<=4000000){"职位网页异常过大"};text.append(buffer,0,n)}};text.toString()
  }finally{deadline.close();c.disconnect()}
 }}
 internal suspend fun request(url:String,body:JSONObject?=null,form:String?=null):JSONObject=withContext(Dispatchers.IO){cancellableHttp{bind->
  val uri=URI(url);require(uri.scheme=="https"&&uri.host in hosts&&(uri.port==-1||uri.host=="ehr.e-cspc.com"&&uri.port==8081)&&uri.userInfo==null)
  val c=URL(url).openConnection() as HttpsURLConnection;if(uri.host=="www.fosunwanbang.com")c.sslSocketFactory=wanbangTls;bind(c);val deadline=RequestDeadline(c,45000)
  try{
   c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000;c.setRequestProperty("User-Agent",if(uri.host in setOf("gzmpc.hotjob.cn","wecruit.hotjob.cn"))"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"else"Mozilla/5.0");c.setRequestProperty("Accept","application/json");if(uri.host=="production.api.recruiting-solutions.org")c.setRequestProperty("customerid","bi-prod")
   if(body!=null||form!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type",if(form!=null)"application/x-www-form-urlencoded"else"application/json");c.outputStream.use{it.write((form?:body.toString()).toByteArray(Charsets.UTF_8))}}
   if(c.responseCode !in 200..299)throw RefreshHttpFailure(c.responseCode,RefreshFailures.retryAfter(c.getHeaderField("Retry-After")))
   val text=StringBuilder();c.inputStream.bufferedReader(Charsets.UTF_8).use{r->val buffer=CharArray(8192);while(true){currentCoroutineContext().ensureActive();val n=r.read(buffer);if(n<0)break;require(text.length+n<=4000000){"招聘接口响应过大"};text.append(buffer,0,n)}};JSONObject(text.toString())
  }finally{deadline.close();c.disconnect()}
 }}
 internal fun parameters(url:String):List<Pair<String,String>>{val u=URI(url);return listOf(u.rawQuery,u.rawFragment).filterNotNull().flatMap{it.split('&')}.filter{it.contains('=')}.map{URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('='),"UTF-8")}}
 fun biFilter(url:String):String{
  val cities=parameters(url).filter{it.first=="addresses/countryCity"}.map{it.second}.distinct()
  require(cities.all{it.length<200&&!it.contains('\'')}){"招聘筛选参数无效"}
  return "language eq 'zh_CN'"+if(cities.isEmpty())""else cities.joinToString(" or "," and (",")"){"addresses/any(jt: jt/countryCity eq '$it')"}
 }
 private fun meaningful(value:String)=value.isNotBlank()&&!Regex("^[0-9\\s\\p{Punct}—]+$").matches(value)
 fun confirmedEmptyBody(reason:String)=listOf("官网职责和任职要求均为占位符“1”","官网列表仍包含该岗位，但详情页没有职位正文").any{reason.startsWith(it)}
 private fun gzSource(url:String)=URI(url).let{u->u.host=="gzmpc.hotjob.cn"&&Regex("/wt/GZMPC/web/index/(campus|social|society|interns)").matches(u.path)||u.host=="sc.hotjob.cn"&&u.path=="/wt/GZMPC/mobweb/v8/position/list"}
 private suspend fun collectGz(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  if(entry.optBoolean("done")&&entry.strings("keys").all{RefreshJournal.facts(j).has(it)})return
  try{
   val u=URI(entry.s("url"));val params=parameters(entry.s("url")).toMap()
   val type=params["recruitType"]?:when(u.path.substringAfterLast('/')){"campus"->"1";"interns"->"12";else->"2"};require(type in listOf("1","2","12")){"招聘类别参数无效"}
   val brand=params["brandCode"]?:"1";require(brand.matches(Regex("[0-9]+"))){"招聘品牌参数无效"}
   val landing=publicHtml("https://example.invalid"+(if(type=="1")"campus"else if(type=="12")"interns"else"social")+"?brandCode=$brand")
   val operational=Regex("/wt/GZMPC/web/json/position/list\\?operational=([a-zA-Z0-9]+)").find(landing)?.groupValues?.get(1)?:error("官网未返回公开职位接口参数")
   var page=minOf(entry.optInt("nextPage",1),entry.strings("keys").filter{!RefreshJournal.facts(j).has(it)}.minOfOrNull{RefreshJournal.queue(j).optJSONObject(it)?.optInt("_sourcePage",1)?:1}?:entry.optInt("nextPage",1))
   while(true){
    stage("listing","读取官网职位第 $page 页")
    val response=request("https://example.invalid")
    require(response.optInt("req_state")==9200&&response.optInt("page")==page&&response.optJSONArray("postList")!=null){"官网职位分页响应无效"}
    val total=response.getInt("rowCount");val pageCount=response.getInt("pageCount");val rows=response.arr("postList").map{row->
     val id=row.s("postId");require(id.matches(Regex("[0-9]+")))
     val url="https://example.invalid"
     json("url" to url,"text" to row.s("postName"),"title" to row.s("postName"),"officialId" to id,"_sourcePage" to page,"description" to clean(row.s("workContent")+"\n\n"+row.s("serviceCondition")),"location" to row.s("workPlace"),"department" to row.s("deptOrgName"),"recruitmentType" to if(type=="1")"校园招聘"else if(type=="12")"实习生"else"社会招聘","publishedAt" to row.s("publishDate"),"active" to "open")
    }
    require(total>=0&&(total==0||rows.isNotEmpty())){"官网没有返回本页岗位"}
    entry.put("nextPage",page);RefreshJournal.addPage(j,entry,rows,total,page<pageCount);entry.put("lastReadPage",maxOf(page,entry.optInt("lastReadPage")));save()
    for(row in rows){val key=RefreshJournal.key(row.s("url"));if(RefreshJournal.facts(j).has(key))continue
     if(!meaningful(row.s("description"))){j.getJSONObject("failedDetails").put(key,"官网未填写岗位职责或任职要求");continue}
     row.put("sources",RefreshJournal.queue(j).getJSONObject(key).getJSONArray("sources"));RefreshJournal.facts(j).put(key,row);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(row,j)
    }
    if(entry.optBoolean("done")&&(page>=pageCount||entry.strings("keys").all{RefreshJournal.facts(j).has(it)}))break
    if(!entry.optBoolean("done")&&page>=pageCount)error(entry.s("error","官网分页数量尚未核齐"))
    page++
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("done",false).put("error",e.message?:"官网职位接口读取失败").put("retryableError",RefreshFailures.temporary(e));save()}
 }
 private fun clean(value:String)=android.text.Html.fromHtml(value,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
 internal fun htmlSection(html:String,className:String):String?{
  val opening=Regex("<div\\b[^>]*class=[\"'][^\"']*\\b"+Regex.escape(className)+"\\b[^\"']*[\"'][^>]*>",RegexOption.IGNORE_CASE).find(html)?:return null
  val start=opening.range.last+1;var depth=1
  for(tag in Regex("</?div\\b[^>]*>",RegexOption.IGNORE_CASE).findAll(html,start)){
   if(tag.value.startsWith("</"))depth-- else if(!tag.value.endsWith("/>"))depth++
   if(depth==0)return html.substring(start,tag.range.first)
  }
  return null
 }
 internal suspend fun structuredGskFact(url:String,title:String):JSONObject{
  require(URI(url).host=="jobs.gsk.com"&&URI(url).path.startsWith("/gb/en/job/"))
  val html=publicHtml(url)
  val structured=Regex("<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",RegexOption.IGNORE_CASE).findAll(html).map{it.groupValues[1]}.toList()
  require(structured.isNotEmpty()){"官网未返回结构化职位正文"}
  val page=json("url" to url,"text" to structured.joinToString("\n"),"jsonld" to JSONArray(structured))
  return PhoneJobs.extract(page,title)?:error("官网结构化职位正文不完整")
 }
 fun biRow(row:JSONObject):JSONObject{
  val url=row.s("link");require(URI(url).host=="jobs.boehringer-ingelheim.com"&&URI(url).path.startsWith("/job-invite/"))
  return json("url" to url,"text" to row.s("title"),"title" to row.s("title"),"officialId" to row.s("jobIdNoLocale"),"description" to clean(row.s("description")),"location" to row.arr("addresses").map{it.s("city")}.distinct().joinToString(" · "),"department" to "","jobCategory" to row.s("category"),"recruitmentType" to row.s("jobSchedule"),"active" to if(row.optBoolean("isActive"))"open"else"closed")
 }
 fun sinopharmRow(row:JSONObject):JSONObject{
  val id=row.s("job_id");require(Regex("[0-9]+").matches(id))
  return json("url" to "https://example.invalid","text" to row.s("job_name"),"title" to row.s("job_name"),"officialId" to id,"description" to clean(row.s("contents")),"department" to row.s("department_cn"),"recruitmentType" to row.s("nature_cn"),"publishedAt" to row.s("start_time"),"active" to "open","location" to row.arr("district_list").map{it.s("area_cn")}.filter{it.isNotBlank()}.distinct().joinToString(" · "))
 }
 suspend fun collect(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit,emptyCspcIds:MutableSet<String> = mutableSetOf()){
  require(supports(entry.s("url")));val u=URI(entry.s("url"));val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j)
  if(entry.optInt("publicApiVersion")!=1){entry.put("done",false).put("keys",JSONArray()).put("nextPage",1).put("publicApiVersion",1);entry.remove("total");j.put("listingComplete",false);save()}
  if(LegendChinaSource.supports(entry.s("url"))){LegendChinaSource.collect(entry,j,save,onFact,stage);return}
  if(JnjInternSource.supports(entry.s("url"))){JnjInternSource.collect(entry,j,save,onFact,stage);return}
  if(ZhaopinCompanySource.supports(entry.s("url"))){ZhaopinCompanySource.collect(entry,j,save,onFact,stage);return}
  if(RemainingCompanySources.bioListing(entry.s("url"))){RemainingCompanySources.collectBio(entry,j,save,onFact,stage);return}
  if(gzSource(entry.s("url"))){collectGz(entry,j,save,onFact,stage);return}
  if(u.host=="www.fosunwanbang.com"){collectWanbang(entry,j,save,onFact,stage);return}
  if(u.host=="ehr.e-cspc.com"){collectCspc(entry,j,save,onFact,stage,emptyCspcIds);return}
  if(u.host=="www.novonordisk.com"){collectNovo(entry,j,save,onFact,stage);return}
  if(entry.optBoolean("done")){if(u.host=="www.ajinga.com")collectAjingaDetails(entry,j,save,onFact,stage);return}
  try{
   var project=""
   if(u.host=="sinopharm2027.iguopin.com"){
    val info=request("https://example.invalid").getJSONObject("data")
    val config=JSONObject(info.getString("content"));project=config.getJSONObject("params").arr("nav").first{it.s("route")=="/job"}.getJSONObject("props").getString("projectId");require(project.matches(Regex("[0-9]+")))
   }
   while(!entry.optBoolean("done")){
    currentCoroutineContext().ensureActive();val page=entry.optInt("nextPage",1)
    stage("listing","读取官网职位第 $page 页")
    retryRefreshPoint(onRetry={attempt,error->save();stage("retry","列表重试 $attempt 次：$error")}){
     var total:Int;val rows:List<JSONObject>
     when(u.host){
      "www.ajinga.com"->{
       val company=Regex("/company/([0-9]+)/").find(u.path)!!.groupValues[1]
       val filters=parameters(entry.s("url")).filter{it.first.startsWith("custom_filter_")||it.first in setOf("job_industry_id","job_type_id")}.joinToString(""){"&"+java.net.URLEncoder.encode(it.first,"UTF-8")+"="+java.net.URLEncoder.encode(it.second,"UTF-8")}
       val response=request("https://example.invalid")
       require(response.optJSONArray("data")!=null){"官网未返回职位数组"};total=response.getInt("total_count");rows=response.arr("data").map{row->val path=row.s("url");require(path.startsWith("/job-detail-new/")&&!path.startsWith("//"));json("url" to "https://example.invalid","text" to row.s("title"),"officialId" to row.s("pk"))}
       require(response.has("isLastPage")){"官网未返回分页状态"}
      }
      "www.boehringer-ingelheim.cn"->{val response=request("https://example.invalid",json("count" to true,"filter" to ("datePosted lt "+entry.s("postedBefore",java.time.Instant.now().toString()).also{entry.put("postedBefore",it)}+" and "+biFilter(entry.s("url"))),"orderby" to "datePosted desc","search" to "*","searchFields" to "jobId,title,description,addresses/city","skip" to ((page-1)*25),"top" to 25));require(response.optJSONArray("value")!=null){"官网未返回职位数组"};total=response.getInt("@odata.count");rows=response.arr("value").map(::biRow)}
      else->{val response=request("https://example.invalid",json("page" to page,"page_size" to 20,"source" to "s_job_list","project_id" to JSONArray().put(project)));require(response.optInt("code")==200){response.s("msg","职位接口失败")};val data=response.getJSONObject("data");require(data.optJSONArray("list")!=null){"官网未返回职位数组"};total=data.getInt("total");rows=data.arr("list").map(::sinopharmRow)}
     }
     require(total>=0&&rows.all{it.s("text").isNotBlank()}){"官网岗位结构无效"}
     val before=entry.strings("keys").size;val newKeys=(entry.strings("keys")+rows.map{RefreshJournal.key(it.s("url"))}).toSet()
     require(total==0||newKeys.size>before){"分页未推进，已保留第 $page 页断点"}
     RefreshJournal.addPage(j,entry,rows,total,newKeys.size<total);entry.put("lastReadPage",page);save()
     for(row in rows){val key=RefreshJournal.key(row.s("url"));if(row.s("description").isBlank()||facts.has(key))continue
      require(meaningful(row.s("description"))){"官网职位正文缺失：${row.s("title")}"}
      val fact=RefreshEfficiency.shallow(row).put("sources",q.getJSONObject(key).getJSONArray("sources"));facts.put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
     }
    }
   }
   if(u.host=="www.ajinga.com")collectAjingaDetails(entry,j,save,onFact,stage)
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"公开职位接口读取失败").put("retryableError",RefreshFailures.temporary(e));save();stage("listing","入口失败：${entry.s("error")}")}
 }
 private suspend fun collectAjingaDetails(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  val company=Regex("/company/([0-9]+)/").find(URI(entry.s("url")).path)!!.groupValues[1]
  for((index,key)in entry.strings("keys").withIndex()){
   if(RefreshJournal.facts(j).has(key))continue
   val row=RefreshJournal.queue(j).getJSONObject(key);val id=Regex("/job-detail-new/([0-9]+)/").find(row.s("url"))?.groupValues?.get(1)?:continue
   try{retryRefreshPoint(onRetry={attempt,error->stage("retry","详情重试 $attempt 次：$error")}){
    stage("details","读取官网详情 ${index+1}/${entry.strings("keys").size}")
    val response=request("https://example.invalid");require(response.optInt("code")==200){response.s("message","职位接口失败")}
    val job=response.getJSONObject("data").getJSONObject("data").getJSONObject("job");require(job.s("id")==id){"官网返回的岗位编号不一致"}
    val description=clean(job.s("cn_description",job.s("description")));require(meaningful(description)){"官网未返回职位正文"}
    val fact=json("url" to row.s("url"),"officialId" to id,"title" to job.s("cn_title",job.s("en_title")),"description" to description,"location" to job.arr("job_locations").map{it.optJSONObject("city")?.s("cn_name").orEmpty()}.distinct().joinToString(" · "),"sources" to row.getJSONArray("sources"),"active" to "open","recruitmentType" to job.s("role_type"))
    RefreshJournal.facts(j).put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
   }}catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"职位正文读取失败");save()}
  }
 }
 internal fun wanbangListPath(html:String):String{
  val base=Regex("eajax\\(\"(/data/pages2\\.aspx\\?createId=[0-9]+&typeName=[a-zA-Z]+)&t1=").find(html)?:error("官网未返回公开职位列表入口")
  val script=html.substring(base.range.first,minOf(html.length,base.range.first+2000))
  val filter=Regex("&tp=([0-9]+)&typeid=([0-9]+)").find(script)?:error("官网未返回招聘类别筛选条件")
  return base.groupValues[1]+"&t1=undefined&t2=undefined&t3=undefined&tp="+filter.groupValues[1]+"&typeid="+filter.groupValues[2]+"&dataType=GET"
 }
 private suspend fun collectWanbang(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  if(entry.optBoolean("done")&&entry.strings("keys").all{RefreshJournal.facts(j).has(it)})return
  try{
   val html=publicHtml(entry.s("url"))
   val path=wanbangListPath(html)
   val endpoint=URI(entry.s("url")).resolve(path);require(endpoint.host=="www.fosunwanbang.com"&&endpoint.path=="/data/pages2.aspx")
   var page=minOf(entry.optInt("nextPage",1),entry.strings("keys").filter{!RefreshJournal.facts(j).has(it)}.minOfOrNull{RefreshJournal.queue(j).optJSONObject(it)?.optInt("_sourcePage",1)?:1}?:entry.optInt("nextPage",1))
   while(true){
    stage("listing","读取官网职位第 $page 页")
    val data=request(endpoint.toString(),form="pageid=$page&pagecount=15&isCount=true");val total=data.getInt("Count")
    val rows=data.arr("data").map{row->val id=row.s("id");require(id.matches(Regex("[0-9]+")));json("url" to "https://example.invalid","text" to row.s("name"),"title" to row.s("name"),"officialId" to id,"_sourcePage" to page,"description" to clean(row.s("statement")+"\n\n"+row.s("requirement")),"location" to row.s("placeId"),"department" to "","jobCategory" to row.s("categoryId"),"recruitmentType" to "校园招聘","active" to "unknown")}
    require(total>=0&&(total==0||rows.isNotEmpty())){"官网未返回本页岗位"}
    entry.put("nextPage",page);RefreshJournal.addPage(j,entry,rows,total,page*15<total);entry.put("lastReadPage",maxOf(page,entry.optInt("lastReadPage")));save()
    for(row in rows){val key=RefreshJournal.key(row.s("url"));if(RefreshJournal.facts(j).has(key))continue
     if(!meaningful(row.s("description"))){j.getJSONObject("failedDetails").put(key,"官网未填写岗位职责或任职要求");continue}
     row.put("sources",RefreshJournal.queue(j).getJSONObject(key).getJSONArray("sources"));RefreshJournal.facts(j).put(key,row);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(row,j)
    }
    if(page*15>=total){require(entry.optBoolean("done")){entry.s("error","官网分页尚未核齐")};break};if(entry.optBoolean("done")&&entry.strings("keys").all{RefreshJournal.facts(j).has(it)})break;page++
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("done",false).put("error",e.message?:"官网职位列表读取失败");save()}
 }
 private suspend fun collectCspc(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit,emptyCspcIds:MutableSet<String>){
  try{
   val channel=URI(entry.s("url")).path.split('/').dropLast(1).last();require(Regex("[a-f0-9-]+").matches(channel))
   stage("listing","读取官网公开渠道岗位")
   val response=request("https://example.invalid",form="channelQrId=$channel&sortName=zri.ext_rankNum&root_id=cspc&keyword=")
   require(response.s("state")=="1"&&response.optJSONArray("jsonList")!=null){response.s("meg","官网职位接口失败")}
   val rows=response.arr("jsonList").map{row->val id=row.s("REQUEST_ID");require(Regex("[a-f0-9-]+").matches(id));json("url" to "https://example.invalid","title" to row.s("PUBLISH_NAME"),"text" to row.s("PUBLISH_NAME"),"officialId" to id,"description" to (row.s("JOB_DUTY")+"\n\n"+row.s("JOB_REQUEST")).trim(),"location" to row.s("work_place"),"department" to row.s("ORG_ALIAS_NAME"),"experience" to row.s("item2"),"recruitmentType" to when(channel){"00000000-0000-4000-8000-000000000002"->"校园招聘";"00000000-0000-4000-8000-000000000003"->"社会招聘";else->""},"active" to "open")}
   if(entry.has("sourceTotal"))entry.put("total",entry.optInt("sourceTotal"))
   RefreshJournal.addPage(j,entry,rows,rows.size,false);entry.put("sourceTotal",rows.size).put("lastReadPage",1);save()
   for(row in rows){val key=RefreshJournal.key(row.s("url"));if(RefreshJournal.facts(j).has(key))continue
    if(!meaningful(row.s("description"))){
     if(row.s("officialId") in emptyCspcIds)continue
     stage("details","读取石药岗位：${row.s("title")}")
     try{
      val response=request("https://example.invalid",form="REQUEST_ID=${row.s("officialId")}")
      row.put("description",RemainingCompanySources.cspcDetail(response,row.s("officialId"))).put("evidence","official-public-detail-api")
     }catch(e:Exception){if(e is CancellationException)throw e;val reason=e.message?:"官网没有返回完整岗位说明";j.getJSONObject("failedDetails").put(key,reason);if(CspcPlaceholderJobs.confirmed(reason))emptyCspcIds.add(row.s("officialId"));save();stage("details","${row.s("title")}：$reason");continue}
    }
    row.put("sources",RefreshJournal.queue(j).getJSONObject(key).getJSONArray("sources"));RefreshJournal.facts(j).put(key,row);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(row,j)
   }
   val removed=CspcPlaceholderJobs.remove(j,rows,emptyCspcIds);save()
   if(removed>0)stage("details","已移除 $removed 个官网正文仅为1的岗位")
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"官网职位接口读取失败");save()}
 }
 private suspend fun collectNovo(entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit){
  try{
   if(!entry.optBoolean("done")){
    val response=request("https://example.invalid");require(response.optInt("status")==200){"职位搜索接口失败"};val data=response.getJSONObject("data");require(data.optJSONArray("jobs")!=null){"官网未返回职位数组"}
    val rows=data.arr("jobs").map{row->val id=row.s("jobId");require(Regex("[0-9]+\\.[a-zA-Z_]+").matches(id));json("url" to "https://example.invalid","text" to row.s("jobTitle"),"location" to row.s("jobLocationLabel"),"department" to "","jobCategory" to row.optJSONObject("jobCategory")?.s("label"))}
    val total=data.getInt("totalMatches");require(rows.size==total){"官网返回 ${rows.size}/$total 个职位"};RefreshJournal.addPage(j,entry,rows,total,false);entry.put("lastReadPage",1);save()
   }
   for((index,key)in entry.strings("keys").withIndex()){
    if(RefreshJournal.facts(j).has(key))continue
    val row=RefreshJournal.queue(j).getJSONObject(key)
    try{retryRefreshPoint(onRetry={attempt,error->stage("retry","详情重试 $attempt 次：$error")}){
     stage("details","读取职位 ${index+1}/${entry.strings("keys").size}");val html=publicHtml(row.s("url"))
     val section=htmlSection(html,"job-display-details")?:error("官网未返回职位正文")
     val button=Regex("<div\\b[^>]*class=[\"'][^\"']*job-display-details-buttons[^\"']*[\"'][^>]*>",RegexOption.IGNORE_CASE).find(section)
     val description=clean(if(button==null)section else section.substring(0,button.range.first));if(!meaningful(description))throw RefreshPointFailure("官网列表仍包含该岗位，但详情页没有职位正文",IllegalStateException("官网正文为空"))
     val fact=RefreshEfficiency.shallow(row).put("title",row.s("text")).put("description",description).put("active","open")
     RefreshJournal.facts(j).put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
    }}catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"职位正文读取失败");save()}
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"职位列表读取失败");save()}
 }
}
