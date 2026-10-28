package chat.plutab.job27
import java.net.URI
import org.json.JSONObject

/** Only verified information pages; linked job searches must remain mandatory entries. */
object RecruitmentReference {
 /** Known signposts point to configured inventories; a failed brochure is not a failed JD list. */
 fun configuredSignpost(url:String,entries:List<String>):Boolean=runCatching{
  val u=URI(url);val path=u.path.trimEnd('/')
  fun provider(host:String)=entries.any{runCatching{URI(it).host==host&&it!=url}.getOrDefault(false)}
  when{
   u.host=="www.dizalpharma.com"&&path=="/careers"->entries.any{it.startsWith("https://example.invalid")}
   u.host=="www.innoventbio.com"&&path.isBlank()->provider("innoventbio.zhiye.com")
   u.host=="www.sanhome.com"&&path.isBlank()->provider("sanhome.zhiye.com")
   u.scheme=="https"&&u.host=="www.allist.com.cn"&&path in listOf("","/recruit.html")&&u.rawUserInfo==null&&u.port in listOf(-1,443)->listOf("/social/jobs","/campus/jobs").all{route->entries.any{runCatching{val v=URI(RecruitmentListingUrl.stable(it));v.scheme=="https"&&v.host=="allist.zhiye.com"&&v.path.trimEnd('/').lowercase()==route&&v.rawQuery==null&&v.rawUserInfo==null&&v.port in listOf(-1,443)}.getOrDefault(false)}}
   u.host=="www.innocarepharma.com"&&path=="/career/job"->provider("innocare.zhiye.com")
   u.host=="chinacampus.jnj.com.cn"&&path.endsWith("/nav/campus_nav.html")->entries.any{it.startsWith("https://example.invalid")&&it.contains("#/position")}
   u.host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")&&path.contains("/home/")->entries.any{runCatching{val v=URI(it);v.host==u.host&&v.path==u.path.substringBefore("/home/")+"/position/index"}.getOrDefault(false)}
   u.host.endsWith(".zhiye.com")&&path=="/home"->entries.any{runCatching{val v=URI(it);v.host==u.host&&v.path.endsWith("/jobs")}.getOrDefault(false)}
   u.host=="wecruit.hotjob.cn"&&path.endsWith("/pb/index.html")->entries.any{it.substringBefore('?').substringBefore('#')==url.substringBefore('?').substringBefore('#').replace("index.html","social.html")}&&entries.any{it.substringBefore('?').substringBefore('#')==url.substringBefore('?').substringBefore('#').replace("index.html","school.html")}
   else->false
  }
 }.getOrDefault(false)
 fun matches(url:String,page:JSONObject,entryUrls:List<String> = emptyList()):Boolean=runCatching{
  val u=URI(url);val text=page.s("text")
  if(u.scheme!="https"||u.rawUserInfo!=null||u.port !in listOf(-1,443))return@runCatching false
  if(Regex("验证码|captcha|access denied|访问过于频繁",RegexOption.IGNORE_CASE).containsMatchIn(text))return@runCatching false
  val signpost=when{
   u.host=="www.dizalpharma.com"&&u.path.trimEnd('/')=="/careers"->"app.mokahr.com"
   u.host=="www.innoventbio.com"&&u.path in listOf("","/")->"innoventbio.zhiye.com"
   u.host=="www.sanhome.com"&&u.path in listOf("","/")->"sanhome.zhiye.com"
   else->null
  }
  if(signpost!=null&&entryUrls.any{runCatching{URI(it).host==signpost}.getOrDefault(false)}&&page.arr("links").any{link->
   runCatching{val target=URI(link.s("url"));target.scheme in listOf("http","https")&&target.host==signpost&&target.rawUserInfo==null}.getOrDefault(false)
  })return@runCatching true
  when{
   u.host=="www.bettapharma.com"&&u.path=="/Jobs/campus" ->
    text.contains("校招覆盖主要院校")&&text.contains("贝达药业招聘联系方式")&&entryUrls.any{runCatching{val v=URI(it);v.scheme=="https"&&v.host=="bettapharma.zhiye.com"&&v.path.trimEnd('/').lowercase() in listOf("/campus","/campus/jobs")}.getOrDefault(false)}
   u.host=="www.sinopharm.com"&&u.path=="/xyzp.html" ->
    text.contains("国药")&&text.contains("2027届校园招聘")&&entryUrls.any{runCatching{val v=URI(it);v.scheme=="https"&&v.host=="sinopharm2027.iguopin.com"&&v.path=="/job"}.getOrDefault(false)}
   u.host=="careers.roche.com"&&u.path.trimEnd('/')=="/cn/zh/startup-china-pharma" -> text.contains("StartUp")&&text.contains("人才发展")
   u.host=="www.bayer.com.cn"&&u.path.trimEnd('/') in listOf("/zh-hans/zhaoxiannashi","/zh-cn/career") ->
    text.contains("职业发展")&&text.contains("员工关爱")&&linkedSearch(page,entryUrls,"app.mokahr.com","/social-recruitment/bayer/148387")
   u.host=="www.simcere.com"&&u.path=="/jrwm/jrwm.aspx" ->
    text.contains("工作机会")&&text.contains("人才")&&linkedSearch(page,entryUrls,"wecruit.hotjob.cn","/SU61458d83bef57c54dcb4e43f/pb/index.html")
   u.host=="www.innocarepharma.com"&&u.path.trimEnd('/')=="/career/job" ->
    text.contains("社会招聘")&&text.contains("校园招聘")&&linkedSearch(page,entryUrls,"innocare.zhiye.com","/social/jobs")&&linkedSearch(page,entryUrls,"innocare.zhiye.com","/campus/jobs")
   u.host=="www.xtalpi.com"&&u.path.trimEnd('/')=="/career" ->
    text.contains("加入我们")&&text.contains("晶泰")&&linkedSearch(page,entryUrls,"app.mokahr.com","/apply/jingtai/2144")&&linkedSearch(page,entryUrls,"app.mokahr.com","/campus-recruitment/jingtai/2143")
   u.host=="cn.novogene.com"&&u.path=="/novo/jrwm_175.html" ->
    text.contains("诺禾")&&text.contains("加入我们")&&linkedSearch(page,entryUrls,"novogene.zhiye.com","/Social")&&linkedSearch(page,entryUrls,"novogene.zhiye.com","/Campus")
   else -> false
  }
 }.getOrDefault(false)
 private fun linkedSearch(page:JSONObject,entries:List<String>,host:String,path:String):Boolean{
  fun same(raw:String)=runCatching{val u=URI(raw);u.scheme=="https"&&u.host==host&&u.path.trimEnd('/')==path&&u.rawUserInfo==null&&u.port in listOf(-1,443)}.getOrDefault(false)
  return entries.any{same(it)}&&page.arr("links").any{same(it.s("url"))}
 }
}
