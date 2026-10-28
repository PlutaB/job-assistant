package chat.plutab.job27

import android.app.Application
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.net.InetAddress
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Read only, checkpointed DOM traversal. Large listing text cannot discard independent job links. */
class PhoneCrawler(private val app:Application,private val maxJobs:Int=Int.MAX_VALUE,private val inspect:((JSONObject)->Unit)?=null){
 private val publicHosts=ConcurrentHashMap<String,Boolean>()
 private val script="""(function(){const text=document.body?document.body.innerText:'';let nodes=[...document.querySelectorAll('button,[role=button],a[rel=next],[class*="Pagination-forward"],[class*="pagination-next"]')];return JSON.stringify({url:location.href,text:text.slice(0,16000),bodyLength:text.length,jsonld:[...document.querySelectorAll('script[type="application/ld+json"]')].map(x=>x.textContent),frames:[...document.querySelectorAll('iframe[src]')].map(n=>n.src),links:[...document.querySelectorAll('a[href]')].filter(a=>location.hostname!=='app.mokahr.com'||!document.querySelector('[class*=results-]')||a.closest('[class*=results-]')||!a.hash.startsWith('#/job/')).filter(a=>location.hostname!=='jobs.sanofi.cn'||!document.querySelector('#search-results-list')||a.closest('#search-results-list')||/^(下一页|下页|Next(?: page)?)$/i.test((a.innerText||a.textContent).trim())).map(a=>({url:a.href,text:((a.innerText||a.textContent).trim().split('\n')[0]).slice(0,300),rel:a.rel})),buttons:nodes.map((b,index)=>({index,text:(b.textContent||b.getAttribute('aria-label')||'').trim(),className:b.className||'',disabled:!!b.disabled||b.getAttribute('aria-disabled')==='true'||/disabled/i.test(b.className||'')})).filter(b=>!b.disabled&&(/^(下一页|下页|Next(?: page)?|加载更多|Load more)$/i.test(b.text)||/Pagination-forward|pagination-next/i.test(b.className)))});})()"""
 private suspend fun js(v:WebView,code:String):String=withTimeout(8000){suspendCancellableCoroutine{c->v.evaluateJavascript(code){if(c.isActive)c.resume(it)}}}
 private fun blocked()=WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
 private fun public(url:String)=runCatching{val u=URI(url);u.scheme=="https"&&u.userInfo==null&&(u.port in listOf(-1,443)||u.host=="ehr.e-cspc.com"&&u.port==8081)&&publicHosts.getOrPut(u.host){InetAddress.getAllByName(u.host).all{!it.isAnyLocalAddress&&!it.isLoopbackAddress&&!it.isSiteLocalAddress&&!it.isLinkLocalAddress&&!it.isMulticastAddress}}}.getOrDefault(false)
 suspend fun collect(company:JSONObject,stage:suspend(String,String)->Unit):Pair<List<JSONObject>,List<String>>{val j=RefreshJournal.new(company);collectInto(company,j,{},{_,_->},stage);return RefreshJournal.objects(RefreshJournal.facts(j)) to j.arr("entries").mapNotNull{it.s("error").takeIf{v->v.isNotBlank()}}}
 suspend fun collectInto(company:JSONObject,journal:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit)=withContext(Dispatchers.Main){
  val metadataChanged=withContext(Dispatchers.Default){var changed=false;for((key,fact) in RefreshJournal.facts(journal).keys().asSequence().map{it to RefreshJournal.facts(journal).getJSONObject(it)})if(JobMetadataFacts.normalize(fact)){RefreshJournal.markFact(journal,key);changed=true};changed}
  if(metadataChanged)save()
  if(HutchDomesticScope.needs(journal)){save();HutchDomesticScope.apply(journal);save()}
  if(LegendChinaSource.attach(journal))save()
  val emptyCspcIds=mutableSetOf<String>()
  val entries=journal.arr("entries").toMutableList();require(entries.isNotEmpty()){"公司没有可读取的招聘清单入口"}
  for(entry in entries.toList())if(PublicRecruitmentSources.menuSource(entry.s("url")))entries.addAll(PublicRecruitmentSources.expandMenu(entry,journal,save,stage))
  // Re-index only adapters changed by this upgrade; preserve all saved job details/ratings.
  for(entry in entries){val host=URI(entry.s("url")).host.orEmpty();val adapterVersion=if(host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn"))2 else 1
   if(entry.optInt("domAdapterVersion")<adapterVersion&&(host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")||host=="campus.51job.com"&&URI(entry.s("url")).path.startsWith("/msd/")||host.endsWith(".zhiye.com")||host.endsWith("novartis.com.cn")&&!entry.optBoolean("done"))){
    entry.put("domAdapterVersion",adapterVersion).put("nextPage",1).put("done",false).put("keys",JSONArray()).put("total",JSONObject.NULL)
    for(field in listOf("nextUrl","resumeUrl","resumePage","lastReadPage","error","retryableError","numberedPages","numberedTotal"))entry.remove(field)
    journal.put("listingComplete",false);save()
   }
  }
  for(entry in entries)if(!entry.optBoolean("done")&&URI(entry.s("url")).host=="app.mokahr.com"&&entry.optInt("numberedTotal")==0&&entry.s("error").contains("分页没有推进")){
   entry.put("nextPage",1);for(field in listOf("nextUrl","resumeUrl","resumePage"))entry.remove(field)
  }
  for(entry in entries)if(URI(entry.s("url")).host=="jobs.sanofi.cn"&&!entry.optBoolean("scopedSearchLinks")){
   entry.put("scopedSearchLinks",true).put("keys",JSONArray()).put("total",JSONObject.NULL).put("done",false).put("nextPage",1)
   for(field in listOf("nextUrl","resumeUrl","resumePage","lastReadPage","error"))entry.remove(field)
   journal.put("listingComplete",false);save()
  }
  for(entry in entries)if(RecruitmentReference.configuredSignpost(entry.s("url"),entries.map{it.s("url")}))entry.put("kind","reference")
  RecruitmentListingUrl.resolveAliases(journal)
  // Native HTTP keeps its own page offsets and never replays browser clicks on resume.
  for(entry in entries)if(!entry.optBoolean("done")&&URI(entry.s("url")).host=="sinopharm2026.iguopin.com"){try{stage("reference","核实旧招聘活动状态");PublicRecruitmentSources.retireExpiredActivity(entry,journal,save)}catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"旧招聘活动状态读取失败");save()}}
  for(entry in entries){if(WorkdaySite.from(entry.s("url"))!=null)WorkdayCollector().collect(entry,journal,save,onFact,stage)}
  for(entry in entries)if(RecruitmentDocuments.supports(entry.s("url")))RecruitmentDocuments.collect(app,entry,journal,save,onFact,stage)
  val nativeRead=mutableSetOf<JSONObject>()
  for(entry in entries)if(PublicRecruitmentSources.supports(entry.s("url"))){nativeRead.add(entry);PublicRecruitmentSources.collect(entry,journal,save,onFact,stage,emptyCspcIds)}
  if(entries.all{it.optBoolean("done")}&&RefreshJournal.queue(journal).keys().asSequence().all{RefreshJournal.facts(journal).has(it)}){
   RefreshJournal.finishListing(journal);journal.put("detailsDone",RefreshJournal.queue(journal).keys().asSequence().all{RefreshJournal.facts(journal).has(it)});save();return@withContext
  }
  val hosts=(entries.map{URI(it.s("url")).host}+entries.mapNotNull{RecruitmentListingUrl.verifiedRedirect(it.s("url"))?.let{url->URI(url).host}}).toSet().let{if("biocytogen.com.cn" in it)it+setOf("biocytogen.workable.com","apply.workable.com")else it}.let{if(entries.any{URI(it.s("url")).let{u->u.host=="campus.51job.com"&&u.path.startsWith("/Innovent2027/")}})it+"innoventbio.zhiye.com"else it}.toMutableSet();if(entries.any{it.s("url")==HutchRecruitmentPage.global})hosts.addAll(listOf("cn.linkedin.com","hk.linkedin.com"));val throttle=RecruitmentThrottle(journal);val v=WebView(app);var loaded=false;var failure:String?=null;var networkFailure:java.net.SocketException?=null;var httpFailure:RefreshHttpFailure?=null
  v.layout(0,0,1280,900);v.settings.useWideViewPort=true;v.settings.loadWithOverviewMode=true;v.settings.userAgentString="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";v.settings.blockNetworkImage=true;v.settings.loadsImagesAutomatically=false;v.settings.mediaPlaybackRequiresUserGesture=true;v.settings.javaScriptEnabled=true;v.settings.domStorageEnabled=true;v.settings.allowFileAccess=false;v.settings.allowContentAccess=false;v.settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,false);v.onResume();v.resumeTimers()
  v.webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView,r:WebResourceRequest):Boolean=if(r.isForMainFrame&&!PhoneJobs.allowed(r.url.toString(),hosts)){failure="招聘入口跳转到未配置域名："+r.url.host;true}else false
   override fun shouldInterceptRequest(view:WebView,r:WebResourceRequest):WebResourceResponse?{
    val url=r.url.toString()
    if(!r.isForMainFrame&&WebResourcePolicy.blocked(url)||!public(url))return blocked()
    if(r.method!="GET"||!HutchRecruitmentTls.supports(url))return null
    return try{HutchRecruitmentTls.read(url,r.requestHeaders).also{response->if(r.isForMainFrame&&response.statusCode>=400)httpFailure=RefreshHttpFailure(response.statusCode)}}catch(e:Exception){
     if(r.isForMainFrame){if(e is java.net.SocketException)networkFailure=e else failure="和黄招聘页读取失败：${e.message?:e.javaClass.simpleName}"}
     // The typed failure above is handled by load(); do not replace it with synthetic HTTP 502.
     blocked()
    }
   }
   override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{failure="网页读取进程退出，当前断点已保留";return true}
   override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel();failure="官网证书验证失败（${error.primaryError}），未继续读取不安全连接"}
   override fun onReceivedHttpError(view:WebView,r:WebResourceRequest,response:WebResourceResponse){if(r.isForMainFrame||r.url.path.orEmpty().contains("/api/Jobad/",ignoreCase=true))httpFailure=RefreshHttpFailure(response.statusCode)}
   override fun onPageFinished(view:WebView,url:String){if(url.startsWith("https://"))loaded=true}
   override fun onReceivedError(view:WebView,r:WebResourceRequest,e:WebResourceError){if(r.isForMainFrame){if(e.errorCode in listOf(-2,-6,-7,-8))networkFailure=java.net.SocketException("招聘页网络读取失败（${e.errorCode}）")else failure="招聘页加载失败（${e.errorCode}），进度已保存"}}
  }
  suspend fun decode(code:String):String{val raw=js(v,code);return JSONArray("[$raw]").getString(0)}
  suspend fun snapshot():JSONObject{
   val page=JSONObject(decode(script));val cards=JSONObject(decode("JSON.stringify("+RecruitmentDom.cards+")"))
   page.put("links",JSONArray(page.arr("links")+cards.arr("links"))).put("embeddedJobs",cards.getJSONArray("embedded")).put("buttons",JSONArray(page.arr("buttons")+cards.arr("buttons")))
   page.put("pendingPublicData",cards.optBoolean("pending"));cards.optJSONObject("listState")?.let{state->page.put("officialListState",state)}
   val numbered=JSONObject(decode("JSON.stringify("+RecruitmentPagination.script+")"));page.put("pagination",numbered)
   if(numbered.optInt("next")>0)page.put("buttons",JSONArray(page.arr("buttons")+json("index" to -1,"pageNumber" to numbered.optInt("next"),"selector" to numbered.s("selector"))))
   val globalRaw=if(URI(page.s("url")).host in listOf("www.linkedin.com","cn.linkedin.com","hk.linkedin.com"))decode("JSON.stringify("+HutchRecruitmentPage.globalCards+")")else "null"
   if(HutchRecruitmentPage.isGlobalSearch(page.s("url")))page.put("pendingPublicData",true)
   if(globalRaw!="null"){
    val global=JSONObject(globalRaw);page.put("pendingPublicData",global.isNull("count")||global.optInt("count")>0&&global.arr("rows").isEmpty());page.put("links",global.getJSONArray("rows")).put("buttons",JSONArray())
    if(!global.isNull("count"))page.put("officialListState",json("kind" to "official-total","count" to global.optInt("count"),"hasMore" to global.optBoolean("hasMore")))
    if(global.optBoolean("hasMore")&&global.optBoolean("button"))page.getJSONArray("buttons").put(json("selector" to "[data-job27-more]","text" to "Load more"))
   }
   return page
  }
  // onPageFinished can wait indefinitely for third-party resources. Read usable DOM
  // as soon as it exists, including in the background, with a bounded renderer call.
  suspend fun visitOnce(url:String,requireDetail:Boolean=false):JSONObject{
   currentCoroutineContext()[RefreshNetworkGate]?.awaitOnline()
   require(PhoneJobs.allowed(url,hosts));loaded=false;failure=null;networkFailure=null;httpFailure=null;v.stopLoading();v.loadUrl("about:blank");delay(80);v.onResume();v.resumeTimers();v.loadUrl(url)
   val started=android.os.SystemClock.elapsedRealtime();var lastNotice=started
   var lastPage=JSONObject()
   try{return withTimeout(45000){
    var page=JSONObject();var stable=0;var previous=""
    while(true){
     delay(700);networkFailure?.let{throw it};httpFailure?.let{throw throttle.failure(url,it)};failure?.let{error(it)}
     page=snapshot();lastPage=page;inspect?.invoke(page);val actual=page.s("url")
     if(URI(url).host=="sinopharm2026.iguopin.com"&&page.s("text").contains("活动不存在或者已结束"))throw RefreshPointFailure("官网明确提示：2026招聘活动不存在或已结束；保留历史岗位，当前2027入口单独抓取",IllegalStateException("招聘活动已结束"))
     if(URI(actual).host=="chinacampus.jnj.com.cn"&&page.s("text").contains("请用微信扫描二维码打开页面"))throw RefreshPointFailure("官网要求在微信中打开实习招聘入口，普通网页未提供职位列表",IllegalStateException("官网限定微信入口"))
     if(PhoneJobs.allowed(actual,hosts)){
      val useful=PhoneJobs.discover(page,hosts).isNotEmpty()||Regex("职位描述|岗位职责|工作职责|任职条件|job description",RegexOption.IGNORE_CASE).containsMatchIn(page.s("text"))
      val signature=actual+":"+page.optInt("bodyLength")+":"+page.arr("links").size
      stable=if(signature==previous)stable+1 else 0;previous=signature
      // Job content must be present or the loaded DOM must settle (e.g. zero results/captcha).
      val rowsReady=PhoneJobs.discover(page,hosts).isNotEmpty()||page.arr("embeddedJobs").isNotEmpty()
      val emptyReady=(PhoneJobs.count(page)==0||page.optJSONObject("officialListState")?.let{it.s("kind")=="official-total"&&it.optInt("count",-1)==0}==true)&&android.os.SystemClock.elapsedRealtime()-started>=6000&&(page.optJSONObject("officialListState")?.s("kind")=="official-total"||Regex("无任何在招职位|暂无|没有.*职位|no (?:open )?(?:jobs|positions)|抱歉.*工作",RegexOption.IGNORE_CASE).containsMatchIn(page.s("text")))
      val linkedReady=RecruitmentListingUrl.linkedListings(url,page).isNotEmpty()
      val listingReady=requireDetail||!RecruitmentLinkPolicy.listing(url)||rowsReady||emptyReady||linkedReady||PhoneJobs.needsVerification(page.s("text"))
      val bodyReady=!requireDetail||PhoneJobs.extract(page,"待核实岗位")!=null||js(v,"(function(){let n=${RecruitmentDom.descriptionSelector};return !!(n&&n.innerText.trim());})()")=="true"||PhoneJobs.needsVerification(page.s("text"))
      if(!page.optBoolean("pendingPublicData")&&listingReady&&bodyReady&&(useful&&stable>=1||loaded&&stable>=2&&page.s("text").isNotBlank())&&(PhoneJobs.count(page)!=0||android.os.SystemClock.elapsedRealtime()-started>=6000)){inspect?.invoke(page);return@withTimeout page}
     }
     val now=android.os.SystemClock.elapsedRealtime()
     if(now-lastNotice>=10000){lastNotice=now;save();stage("loading","等待招聘网页内容 · ${(now-started)/1000}秒；45秒未就绪将重试当前页")}
    }
    @Suppress("UNREACHABLE_CODE") page
   }}catch(e:TimeoutCancellationException){if(!currentCoroutineContext().isActive)throw e;throw RefreshPointFailure(if(requireDetail&&lastPage.s("text").isNotBlank())PhoneJobs.detailProblem(lastPage,"岗位")+"（等待45秒后仍未就绪）"else"招聘网页45秒未返回可读取内容",e)}finally{v.stopLoading()}
  }
  suspend fun visit(url:String):JSONObject=retryRefreshPoint(onRetry={attempt,error->
   journal.put("retryPoint",url).put("retryAttempt",attempt);save();stage("retry","同一页面自动重试 $attempt：$error")
  }){
   val page=visitOnce(url)
   if(PhoneJobs.needsVerification(page.s("text")))throw RefreshPointFailure("招聘入口要求安全验证，断点已保留",IllegalStateException("网站安全验证"))
   page
  }
  suspend fun clickNext(button:JSONObject):JSONObject{
   val target=button.optInt("pageNumber")
   if(target>0){
    val before=PhoneJobs.discover(snapshot(),hosts).map{RefreshJournal.key(it.s("url"))}.toSet()
    val selector=button.s("selector").ifBlank{"[data-action=pageTurn][data-pagenum=\"$target\"]"}
    val clicked=js(v,"(function(){let n=document.querySelector(${JSONObject.quote(selector)});if(!n)return false;n.click();return true;})()")
    require(clicked=="true"){"官网数字分页按钮未找到"}
    return boundedRefreshStep(15000){while(true){delay(500);val page=snapshot();if(page.optJSONObject("pagination")?.optInt("current")==target&&PhoneJobs.discover(page,hosts).any{RefreshJournal.key(it.s("url")) !in before})return@boundedRefreshStep page};@Suppress("UNREACHABLE_CODE") JSONObject()}
   }
   if(button.s("selector")=="[data-job27-more]"){
    val before=PhoneJobs.discover(snapshot(),hosts).map{RefreshJournal.key(it.s("url"))}.toSet()
    js(v,"document.querySelector(\"[data-job27-more]\")?.click()")
    return boundedRefreshStep(15000){while(true){delay(500);val page=snapshot();if(PhoneJobs.discover(page,hosts).any{RefreshJournal.key(it.s("url")) !in before})return@boundedRefreshStep page};@Suppress("UNREACHABLE_CODE") JSONObject()}
   }
   val index=button.optInt("index")
   js(v,"(function(){let b=[...document.querySelectorAll('button,[role=button],a[rel=next],[class*=\"Pagination-forward\"],[class*=\"pagination-next\"]')][$index];if(b)b.click();return true;})()");delay(1500)
   return retryRefreshPoint(onRetry={attempt,error->save();stage("retry","当前分页读取自动重试 $attempt：$error")}){val page=snapshot();require(!PhoneJobs.needsVerification(page.s("text"))){"当前分页需要验证"};page}
  }
  val q=RefreshJournal.queue(journal);val facts=RefreshJournal.facts(journal)
  suspend fun captureEmbedded(page:JSONObject){
   for(raw in page.arr("embeddedJobs")){
    val key=RefreshJournal.key(raw.s("url"));if(facts.has(key)||raw.s("description").isBlank()||!raw.optBoolean("descriptionComplete"))continue
    val f=JSONObject(raw.toString()).put("verifiedAt",java.time.Instant.now().toString()).put("active","open").put("evidence","official-inline-jd").put("sources",JSONArray().put(page.s("url")).put(raw.s("url")))
    facts.put(key,f);RefreshJournal.markFact(journal,key);journal.getJSONObject("failedDetails").remove(key);journal.optJSONObject("retryableDetails")?.remove(key);save();onFact(f,journal)
   }
  }
  suspend fun scrollNext(previous:JSONObject):JSONObject{
   val before=PhoneJobs.discover(previous,hosts).map{RefreshJournal.key(it.s("url"))}.toSet()
   js(v,"(function(){const n=document.querySelector('.am-list-view-scrollview');if(n){n.scrollTop=n.scrollHeight;n.dispatchEvent(new Event('scroll'))}else{window.scrollTo(0,document.documentElement.scrollHeight);window.dispatchEvent(new Event('scroll'))}})()")
   return withTimeout(15000){while(true){delay(700);val next=snapshot();if(PhoneJobs.discover(next,hosts).any{RefreshJournal.key(it.s("url")) !in before})return@withTimeout next};@Suppress("UNREACHABLE_CODE") previous}
  }
  suspend fun captureCards(rows:List<JSONObject>){
   for(a in rows.take(maxJobs)){val k=RefreshJournal.key(a.s("url"));if(facts.has(k)){val f=facts.getJSONObject(k);f.put("sources",JSONArray((f.strings("sources")+q.getJSONObject(k).strings("sources")+a.s("url")).distinct()));RefreshJournal.markFact(journal,k);continue}
    val card=JSONObject(decode("""(function(){let a=[...document.querySelectorAll('a[href]')].find(a=>a.href===${JSONObject.quote(a.s("url"))});if(!a)return '{}';let d=a.querySelector('[class*=job-description]');if(!d)return '{}';let parts=[...a.querySelectorAll('[class*=info-] [class*=sd-foundation-body-secondary]')].map(n=>(n.querySelector('[class*=hiddenContent]')||n).textContent.trim());return JSON.stringify({title:(a.querySelector('[class*=title-]')||a).innerText.split('\n')[0],description:d.innerText,headerParts:parts,published:(a.querySelector('[class*=published-at]')||{}).textContent||''});})()"""))
    val description=card.s("description");if(description.isBlank()||card.s("title").isBlank())continue
    val fact=PhoneJobs.extract(json("url" to a.s("url"),"text" to (card.s("title")+"\n"+card.s("published")+"\n职位描述\n"+description),"descriptionText" to description,"officialDescription" to true),card.s("title"))?:continue
    val metadata=JobMetadataFacts.header(card.strings("headerParts"));for(field in metadata.keys())fact.put(field,metadata.get(field));JobMetadataFacts.normalize(fact,card.s("description"));fact.put("verifiedAt",java.time.Instant.now().toString()).put("sources",JSONArray((q.getJSONObject(k).strings("sources")+a.s("url")).distinct())).put("evidence","official-full-jd-in-list-card");facts.put(k,fact);RefreshJournal.markFact(journal,k);journal.getJSONObject("failedDetails").remove(k);journal.optJSONObject("retryableDetails")?.remove(k);save();onFact(fact,journal);if(facts.length()%10==0)stage("details","核对详情 ${facts.length()}/${q.length()}；已保存 ${facts.length()} 个详情")
   }
  }
  try{
   var entryCursor=0
   while(entryCursor<entries.size){RecruitmentListingUrl.resolveAliases(journal);val entryIndex=entryCursor++;val entry=entries[entryIndex]
    if(entry.optBoolean("done")||entry.has("aliasOf")||WorkdaySite.from(entry.s("url"))!=null||RecruitmentDocuments.supports(entry.s("url")))continue
    if(PublicRecruitmentSources.supports(entry.s("url"))){if(entry !in nativeRead){nativeRead.add(entry);PublicRecruitmentSources.collect(entry,journal,save,onFact,stage,emptyCspcIds)};continue}
    if(throttle.paused(entry.s("url"))){entry.put("error","官网请求限流（HTTP 429），本轮已暂停该来源，断点已保存，稍后手动继续").put("retryableError",false);save();continue}
    try{
     retryRefreshPoint(retries=if(entry.s("kind")=="reference")0 else 3,onRetry={attempt,error->save();stage("retry","入口 ${entryIndex+1} 的同一断点自动重试 $attempt：$error")}){
     val base=RecruitmentListingUrl.stable(entry.s("url"));val infinite=URI(base).host.endsWith(".zhiye.com");val moka=URI(base).host=="app.mokahr.com";var cursor=entry.optInt("nextPage",1);stage("listing","读取入口 ${entryIndex+1}/${entries.size} 第${cursor}页；已保存 ${q.length()} 个岗位链接")
     val savedPage=entry.optInt("resumePage")==cursor&&entry.s("resumeUrl").isNotBlank()
     val hutchTarget=HutchRecruitmentPage.listing(entry);if(hutchTarget!=null){entry.put("effectiveListingUrl",hutchTarget);save()}
     var page=visit(hutchTarget?:if(moka)base.substringBefore('#')+"#/jobs?page=$cursor&anchorName=jobsList" else if(savedPage)entry.s("resumeUrl") else entry.s("nextUrl",if(URI(entry.s("url")).host=="jobs.gsk.com"||RecruitmentListingUrl.verifiedRedirect(entry.s("url"))!=null)base else entry.s("url")))
     val actual=URI(page.s("url"));val redirectTarget=entries.firstOrNull{candidate->
      candidate!==entry&&candidate.optBoolean("done")&&actual.host=="app.mokahr.com"&&URI(entry.s("url")).host!=actual.host&&runCatching{val configured=URI(candidate.s("url"));configured.host==actual.host&&configured.path==actual.path&&configured.rawQuery==actual.rawQuery}.getOrDefault(false)
     }
     if(redirectTarget!=null){entry.put("kind","reference").put("done",true).put("total",0).put("keys",JSONArray()).put("effectiveListingUrl",redirectTarget.s("url"));entry.remove("error");entry.remove("retryableError");save();return@retryRefreshPoint}
     val hutchLinks=HutchRecruitmentPage.links(entry.s("url"),page,entries.map{it.s("url")})
     if(hutchLinks.isNotEmpty()){
      hosts.addAll(hutchLinks.map{URI(it).host})
      entry.put("referenceContent",page.s("text")).put("referenceReadAt",java.time.Instant.now().toString()).put("linkedListings",JSONArray(hutchLinks))
     }
     val linked=RecruitmentListingUrl.linkedListings(entry.s("url"),page)+hutchLinks
     if(base==HutchRecruitmentPage.global){
      val search=if(HutchRecruitmentPage.isGlobalSearch(page.s("url")))HutchRecruitmentPage.search else HutchRecruitmentPage.globalSearch(page)
      require(search!=null){"和黄全球岗位页未提供可读取的全部职位入口"}
      entry.put("effectiveListingUrl",search);if(!HutchRecruitmentPage.isGlobalSearch(page.s("url")))page=visit(search)
     }
     if(PhoneJobs.discover(page,hosts).isEmpty()&&linked.isNotEmpty()){
      for(target in linked.map{RecruitmentListingUrl.stable(it)}.distinct())if(entries.none{RecruitmentListingUrl.stable(it.s("url"))==target}){
       val child=json("url" to target,"nextPage" to 1,"done" to false,"keys" to JSONArray(),"total" to JSONObject.NULL,"discoveredFrom" to entry.s("url"))
       entries.add(child);journal.getJSONArray("entries").put(child)
      }
      entry.put("kind","reference").put("done",true).put("total",0).put("keys",JSONArray());entry.remove("error");entry.remove("retryableError");save();return@retryRefreshPoint
     }
     if(URI(base).host=="chinacampus.jnj.com.cn"&&URI(base).path=="/jnj/home/goPage/internship"&&page.strings("frames").isNotEmpty()){
      var count:Int?=null
      try{count=RemainingCompanySources.jnjInternCount();entry.put("reportedInternCount",count);save()}catch(e:Exception){if(e is CancellationException)throw e;entry.put("internCountError",e.message)}
      throw RefreshPointFailure(if(count!=null)"强生实习官网报告${count}个岗位，实际清单要求在微信中打开，尚未取得岗位详情"else"强生实习入口要求在微信中打开，尚未核实完整岗位清单",IllegalStateException("实习官网限制微信入口"))
     }
     if(entry.s("kind")!="reference"&&URI(base).host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")&&URI(base).path.contains("/home/")&&page.arr("embeddedJobs").none{it.s("evidence")=="official-jnj-public-position-data"}){
      val listing=page.arr("links").firstOrNull{val url=it.s("url");PhoneJobs.allowed(url,hosts)&&URI(url).path.endsWith("/position/index")&&it.s("text") in listOf("校招职位","校园招聘","全部职位","立即申请")}
      require(listing!=null){"招聘介绍页没有找到可核实的官方岗位清单"}
      entry.put("effectiveListingUrl",listing.s("url"));page=visit(listing.s("url"))
     }
     if(URI(base).host=="chinacampus.jnj.com.cn"&&page.arr("embeddedJobs").any{it.s("evidence")=="official-jnj-public-position-data"}){
      val fragment=URI(entry.s("url")).fragment.orEmpty();val specific=fragment.contains("category=")&&fragment.contains("posinx=")
      val roles=page.arr("embeddedJobs").filter{it.s("evidence")=="official-jnj-public-position-data"}.filter{role->!specific||URI(role.s("url")).fragment?.substringAfter('?')==fragment.substringAfter('?').removePrefix("selected=&")}
      require(roles.isNotEmpty()){"强生当前岗位路由未匹配公开岗位说明"}
      RefreshJournal.addPage(journal,entry,roles.map{json("url" to it.s("url"),"text" to it.s("title"))},roles.size,false)
      captureEmbedded(RefreshEfficiency.shallow(page).put("embeddedJobs",JSONArray(roles)));save();stage("details","已读取强生官网公开岗位及完整说明：${roles.size}条")
     }
     if(URI(base).host=="campus.51job.com"&&URI(base).path.startsWith("/msd/")){
      val embedded=mutableListOf<JSONObject>()
      for(tab in 0..1){js(v,"document.querySelectorAll('.job-type-item')[$tab]?.click()");delay(500);embedded.addAll(snapshot().arr("embeddedJobs"))}
      require(embedded.isNotEmpty()){"默沙东官方校园页面尚未载入岗位"}
      val unique=embedded.distinctBy{RefreshJournal.key(it.s("url"))}
      RefreshJournal.addPage(journal,entry,unique.map{json("url" to it.s("url"),"text" to it.s("title"))},unique.size,false)
      for(f in unique){val key=RefreshJournal.key(f.s("url"));if(facts.has(key))continue
       require(f.optBoolean("descriptionComplete")&&f.s("description").isNotBlank()){"官方岗位说明尚未完整载入"}
       f.put("verifiedAt",java.time.Instant.now().toString()).put("evidence","official-campus-inline-jd").put("active","open").put("sources",JSONArray().put(base).put(f.s("url")))
       facts.put(key,f);RefreshJournal.markFact(journal,key);journal.getJSONObject("failedDetails").remove(key);save();onFact(f,journal)
      }
      stage("details","已读取默沙东官网两类岗位及完整说明：${unique.size}条")
     }
     if((entry.s("kind")=="reference"||RecruitmentReference.matches(base,page,entries.map{it.s("url")}))&&PhoneJobs.discover(page,hosts).isEmpty()){
      entry.put("kind","reference").put("referenceContent",page.s("text")).put("referenceReadAt",java.time.Instant.now().toString()).put("done",true).put("total",0).put("keys",JSONArray());entry.remove("error");entry.remove("retryableError");save()
      stage("reference","入口 ${entryIndex+1}：已读取招聘介绍页，内容保留，不作为分页岗位列表")
     }
     if(RecruitmentPagination.recognized(page)&&page.getJSONObject("pagination").optInt("current")<cursor){
      boundedRefreshStep(120000){while(page.getJSONObject("pagination").optInt("current")<cursor){val next=page.arr("buttons").firstOrNull{it.optInt("pageNumber")>0}?:error("数字分页无法恢复");page=clickNext(next)}}
     }
     if(!entry.optBoolean("done")&&!moka&&cursor>1&&!entry.has("nextUrl")&&!savedPage&&!RecruitmentPagination.recognized(page)){boundedRefreshStep(120000){for(i in 1 until cursor){
      stage("listing","恢复网页分页 ${i+1}/$cursor；已保存 ${q.length()} 个岗位链接")
      if(infinite||page.optJSONObject("officialListState")?.s("kind")=="hotjob-mobile-cumulative")page=scrollNext(page)else {val next=page.arr("buttons").firstOrNull()?:error("无法恢复该入口分页，请核对招聘网站");page=clickNext(next)}
     }}}
     var pages=0
     while(!entry.optBoolean("done")&&pages++<200){
      currentCoroutineContext().ensureActive();require(!PhoneJobs.needsVerification(page.s("text"))){"入口需要验证，断点已保留"}
      if(base==HutchRecruitmentPage.global){
       val login=Regex("sign in|join linkedin|登录|登入",RegexOption.IGNORE_CASE).containsMatchIn(page.s("text"))
       require(!login||PhoneJobs.discover(page,hosts).isNotEmpty()){"和黄全球岗位入口要求LinkedIn登录，未取得公开岗位清单"}
       require(page.optJSONObject("officialListState")?.s("kind")=="official-total"){"和黄全球岗位入口未返回可核实的完整清单总数"}
      }
      val rows=PhoneJobs.discover(page,hosts);val listState=page.optJSONObject("officialListState");val expected=PhoneJobs.listCount(page,rows.size);require(rows.isNotEmpty()||expected==0&&(!infinite||Regex("无任何在招职位|暂无(?:符合条件的|相关|在招)?(?:职位|岗位)|没有(?:符合条件的|相关)?(?:职位|岗位)").containsMatchIn(page.s("text")))){"当前页未读到岗位，且官网未确认零岗位"};val previousKeys=entry.strings("keys").toSet();val next=page.arr("buttons").firstOrNull();val nextLink=page.arr("links").firstOrNull{(it.s("rel")=="next"||it.s("text") in listOf("下一页","下页","Next","Next page"))&&PhoneJobs.allowed(it.s("url"),hosts)}
      val more=listState?.optBoolean("hasMore")==true||next!=null||nextLink!=null||((moka||infinite)&&expected!=null&&previousKeys.size+rows.count{RefreshJournal.key(it.s("url")) !in previousKeys}<expected)
      if(expected!=null&&previousKeys.size==expected&&rows.isNotEmpty()&&rows.all{RefreshJournal.key(it.s("url")) in previousKeys}){entry.put("total",expected).put("done",true);entry.remove("error");entry.remove("retryableError");save();break}
      if(!RecruitmentPagination.recognized(page)&&rows.isNotEmpty()&&rows.all{RefreshJournal.key(it.s("url")) in previousKeys}&&previousKeys.isNotEmpty())error("分页没有推进，已保留第${cursor}页断点")
      val pageKeys=entry.optJSONObject("cardPageKeys")?:JSONObject().also{entry.put("cardPageKeys",it)}
      if(moka)pageKeys.put(cursor.toString(),JSONArray(rows.map{RefreshJournal.key(it.s("url"))}))
      if(RecruitmentPagination.recognized(page))RecruitmentPagination.commit(journal,entry,rows,page)else RefreshJournal.addPage(journal,entry,rows,expected,more);entry.put("lastReadPage",cursor);captureEmbedded(page);if(moka)captureCards(rows);if(nextLink!=null)entry.put("nextUrl",nextLink.s("url"));save();stage("listing","入口 ${entryIndex+1}/${entries.size} 第${cursor}页已保存；共 ${q.length()} 个唯一岗位链接")
      if(entry.optBoolean("done")||!more||q.length()>=maxJobs)break
      cursor=entry.optInt("nextPage",1);val previousUrl=page.s("url")
      stage("listing","读取入口 ${entryIndex+1}/${entries.size} 第${cursor}页；已保存 ${q.length()} 个岗位链接")
      page=if(RecruitmentPagination.recognized(page))clickNext(next!!)else if(moka)visit(base.substringBefore('#')+"#/jobs?page=$cursor&anchorName=jobsList")else if(nextLink!=null)visit(nextLink.s("url"))else if(infinite||listState?.s("kind")=="hotjob-mobile-cumulative")scrollNext(page)else clickNext(next!!)
      if(!moka&&page.s("url")!=previousUrl&&PhoneJobs.allowed(page.s("url"),hosts)){entry.put("resumeUrl",page.s("url")).put("resumePage",cursor);save()}
     }
     }
    }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"入口读取失败").put("retryableError",RefreshFailures.status(e)!=429&&RefreshFailures.temporary(e));save()}
   }
   RecruitmentListingUrl.resolveAliases(journal);RefreshJournal.finishListing(journal);save()
   // Older journals may have all URLs but lack full JDs. Rehydrate missing full card JDs page by page.
   for(entry in entries){if(entry.s("paginationMode")=="moka-numbered"||URI(entry.s("url")).host!="app.mokahr.com"||!entry.optBoolean("done")||entry.strings("keys").all{facts.has(it)})continue
    val completed=entry.strings("cardPagesDone").toMutableSet()
    for(pageNo in MokaDetailPlan.pages(entry,facts)){
     if(pageNo.toString() in completed)continue
     try{stage("details","补读缺失详情所在第${pageNo}页；已保存 ${facts.length()}/${q.length()} 个详情")
      val page=visit(entry.s("url").substringBefore('#')+"#/jobs?page=$pageNo&anchorName=jobsList");val expected=PhoneJobs.count(page);require(expected==entry.optInt("total")){"官网列表数量已变化，需重新核对入口"};val rows=PhoneJobs.discover(page,hosts).filter{q.has(RefreshJournal.key(it.s("url")))};captureCards(rows);completed.add(pageNo.toString());entry.put("cardPagesDone",JSONArray(completed));save()
     }catch(e:Exception){if(e is CancellationException)throw e;break}
    }
   }
   var processed=0
   for((queueIndex,k) in q.keys().asSequence().toList().withIndex()){
    if(facts.has(k)||throttle.paused(q.getJSONObject(k).s("url")))continue
    // A confirmed empty official body must not become a JD made of navigation and labels.
    val knownFailure=journal.getJSONObject("failedDetails").s(k)
    if(PublicRecruitmentSources.confirmedEmptyBody(knownFailure))continue
    if(processed++>=maxJobs)break
    val a=q.getJSONObject(k);journal.put("currentDetail",json("index" to queueIndex+1,"url" to a.s("url"),"title" to a.s("text")))
    // Native Workday detail failures may still have an ordinary public page. Only missing JDs reach this fallback.
    try{stage("details","核对详情 ${queueIndex+1}/${q.length()}；已保存 ${facts.length()} 个详情")
     retryRefreshPoint(onRetry={attempt,error->save();stage("retry","同一岗位详情自动重试 $attempt：$error")}){
     withTimeout(90000){
     if(URI(a.s("url")).host=="jobs.gsk.com"){
      val fact=PublicRecruitmentSources.structuredGskFact(a.s("url"),a.s("text")).put("verifiedAt",java.time.Instant.now().toString()).put("sources",JSONArray((a.strings("sources")+a.s("url")).distinct()))
      facts.put(k,fact);RefreshJournal.markFact(journal,k);journal.getJSONObject("failedDetails").remove(k);journal.optJSONObject("retryableDetails")?.remove(k);save();onFact(fact,journal);return@withTimeout
     }
     val attempts=journal.optJSONObject("detailFetchCount")?:JSONObject().also{journal.put("detailFetchCount",it)};attempts.put(k,attempts.optInt(k)+1);save();val page=visitOnce(a.s("url"),requireDetail=true);if(PhoneJobs.needsVerification(page.s("text")))throw RefreshPointFailure("岗位页面要求安全验证，断点已保留",IllegalStateException("网站安全验证"));val selector=if(URI(a.s("url")).host in listOf("www.linkedin.com","cn.linkedin.com","hk.linkedin.com"))"document.querySelector('.show-more-less-html__markup')"else RecruitmentDom.descriptionSelector;val length=js(v,"(function(){let n=$selector;return n?n.innerText.length:document.body.innerText.length;})()").toIntOrNull()?:0
     require(length<=2000000){"岗位详情异常过大，已保存断点；没有丢弃岗位链接"};val description=StringBuilder();for(offset in 0 until length step 24000){description.append(decode("(function(){let n=$selector;return (n?n.innerText:document.body.innerText).slice($offset,${offset+24000});})()"))};page.put("descriptionText",description.toString());page.put("officialDescription",js(v,"Boolean($selector)")=="true")
     val fact=PhoneJobs.extract(page,a.s("text"))?:error(PhoneJobs.detailProblem(page,a.s("text")));fact.put("verifiedAt",java.time.Instant.now().toString()).put("sources",JSONArray((a.strings("sources")+fact.s("url")).distinct()));facts.put(k,fact);RefreshJournal.markFact(journal,k);journal.getJSONObject("failedDetails").remove(k);journal.optJSONObject("retryableDetails")?.remove(k);save();onFact(fact,journal)
     }
     }
    }catch(e:Exception){if(e is CancellationException)throw e;journal.getJSONObject("failedDetails").put(k,e.message?:"详情失败");val retryable=journal.optJSONObject("retryableDetails")?:JSONObject().also{journal.put("retryableDetails",it)};if(RefreshFailures.status(e)==429)throttle.failure(a.s("url"),e);if(RefreshFailures.status(e)!=429&&RefreshFailures.temporary(e))retryable.put(k,true)else retryable.remove(k);save();stage("details","详情 ${queueIndex+1}/${q.length()} 失败：${a.s("text").take(36)} · ${e.message?:"读取失败"}")}
   }
   journal.put("detailsDone",q.keys().asSequence().all{facts.has(it)});save()
  }finally{v.stopLoading();v.destroy()}
 }
}
