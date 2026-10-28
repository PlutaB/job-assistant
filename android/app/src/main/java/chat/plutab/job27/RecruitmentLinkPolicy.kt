package chat.plutab.job27

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Exclude only recognizable navigation/search URLs, never infer job removal from an HTTP failure. */
object RecruitmentLinkPolicy {
 fun listing(raw:String):Boolean=runCatching{
  val u=URI(raw);val p=u.path.orEmpty().trimEnd('/').lowercase()
  when{
   u.host=="chinacampus.jnj.com.cn"&&u.fragment.orEmpty().startsWith("/position")->true
   u.host=="app.mokahr.com"->!u.fragment.orEmpty().startsWith("/job/")
   u.host.orEmpty().endsWith(".zhiye.com")->p.endsWith("/jobs")||p in listOf("/social","/campus","/intern","/jobs")
   u.host.orEmpty().endsWith("moseeker.com")->p.contains("/positions/index/cid/")
   u.host=="wecruit.hotjob.cn"->p.contains("/pb/")&&p.endsWith(".html")&&!p.endsWith("/index.html")&&!p.endsWith("/posdetail.html")||p.contains("/mc/position/")
   u.host=="www.zhaopin.com"&&p.startsWith("/companydetail/")->true
   u.host=="www.ajinga.com"->p.endsWith("/job-list")||p.endsWith("/professionals")
   u.host.orEmpty().endsWith("iguopin.com")->p.endsWith("/job")
   u.host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")->p.endsWith("/position/index")
   p.endsWith("/careers/jobs")->true
   else->false
  }
 }.getOrDefault(false)
 fun navigation(raw:String):Boolean=runCatching{
  val u=URI(raw);val host=u.host.orEmpty().lowercase();val p=u.path.orEmpty().trimEnd('/').lowercase()
  when{
   host.endsWith(".zhiye.com")->Regex("/(?:home|social|campus|intern|jobs|[0-9]+)(?:/jobs)?").matches(p)
   host=="chinacampus.jnj.com.cn"&&Regex("(?:^|[?&])category=[A-Za-z0-9_-]+").containsMatchIn(u.fragment.orEmpty())&&Regex("(?:^|&)posinx=[0-9]+(?:&|$)").containsMatchIn(u.fragment.orEmpty())->false
   host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")->p.contains("/home/")||p.endsWith("/position/index")||p.contains("/nav/")
   host.endsWith("moseeker.com")->p.contains("/positions/index/cid/")||Regex("/company/[0-9]+").matches(p)
   host.endsWith("hotjob.cn")->p.endsWith("/position/list")||p.endsWith("/position/setsubscriptionposition")||p.endsWith("/mc/index")
   host=="www.bettapharma.com"->(p in listOf("/jobs","/jobs/campus")||Regex("/jobs/index/page/[0-9]+").matches(p))&&!Regex("^position=[0-9]+$").matches(u.fragment.orEmpty())
   host.endsWith("zhaopin.com")->p.startsWith("/position/")||p.startsWith("/companydetail/")
   p.endsWith("/careers/jobs")->true
   else->false
  }
 }.getOrDefault(false)
 fun wrongZhaopinCompany(raw:String,entryUrls:List<String>):Boolean=runCatching{
  val u=URI(raw);if(u.host!="www.zhaopin.com")return@runCatching false
  val company=Regex("/jobdetail/CC(L?[0-9]+)J",RegexOption.IGNORE_CASE).find(u.path)?.groupValues?.get(1)?:return@runCatching false
  val expected=entryUrls.mapNotNull{url->runCatching{val e=URI(url);if(e.host==u.host)Regex("/companydetail/jobs-CZ(L?[0-9]+)",RegexOption.IGNORE_CASE).find(e.path)?.groupValues?.get(1)else null}.getOrNull()}.toSet()
  expected.isNotEmpty()&&company !in expected
 }.getOrDefault(false)
 fun needsQuarantine(j:JSONObject):Boolean {
  val urls=j.arr("entries").map{it.s("url")}
  return RefreshJournal.queue(j).keys().asSequence().any{k->val url=RefreshJournal.queue(j).getJSONObject(k).s("url");navigation(url)||wrongZhaopinCompany(url,urls)}
 }
 fun quarantine(j:JSONObject,archiveSavedFacts:Boolean=false):Int {
  val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j);val entries=j.arr("entries");val urls=entries.map{it.s("url")}
  val archive=j.optJSONObject("excludedNavigation")?:JSONObject();var changed=0
  for(k in q.keys().asSequence().toList()){
   val row=q.getJSONObject(k);val reason=when{
    navigation(row.s("url"))->"已确认是导航、筛选或岗位清单，不是岗位详情"
    wrongZhaopinCompany(row.s("url"),urls)->"职位URL的公司编号与当前招聘清单公司编号不一致"
    else->continue
   }
   // Retain any existing JD/rating; do not silently delete historical or manually edited jobs.
   if(facts.has(k)&&!archiveSavedFacts)continue
   archive.put(k,json("link" to row,"reason" to reason,"previousError" to j.getJSONObject("failedDetails").opt(k),"retainedFactKey" to if(facts.has(k))k else null))
   if(archiveSavedFacts)facts.remove(k)
   q.remove(k);j.getJSONObject("failedDetails").remove(k);j.optJSONObject("retryableDetails")?.remove(k);changed++
   for(e in entries){val old=e.strings("keys");if(k in old){e.put("keys",JSONArray(old.filter{it!=k}));e.put("done",false).put("total",JSONObject.NULL).put("nextPage",1);for(field in listOf("nextUrl","resumeUrl","resumePage","lastReadPage"))e.remove(field)}}
  }
  if(changed>0)j.put("excludedNavigation",archive).put("listingComplete",false).put("fullyComplete",false)
  return changed
 }
}
