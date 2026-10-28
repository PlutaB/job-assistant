package chat.plutab.job27

import java.net.URI
import org.json.JSONObject

/** Follow the domestic inventory requested by the user; do not add global recruitment. */
internal object HutchRecruitmentPage {
 const val global="https://example.invalid"
 // The public company page's own "See all jobs" link, verified with f_C employer ID.
 const val search="https://example.invalid"
 fun listing(entry:JSONObject):String?=if(entry.s("url")==global)search else null
 fun isGlobalSearch(raw:String):Boolean=runCatching{val u=URI(raw);u.scheme=="https"&&u.host in listOf("www.linkedin.com","cn.linkedin.com","hk.linkedin.com")&&u.path=="/jobs/hutchmed-jobs-worldwide"&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&Regex("(?:^|&)f_C=27069365(?:&|$)").containsMatchIn(u.rawQuery.orEmpty())}.getOrDefault(false)
 fun globalSearch(page:JSONObject):String?=page.arr("links").map{it.s("url")}.firstOrNull{runCatching{
  val u=URI(it);u.scheme=="https"&&u.host=="www.linkedin.com"&&u.path=="/jobs/hutchmed-jobs-worldwide"&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&Regex("(?:^|&)f_C=27069365(?:&|$)").containsMatchIn(u.rawQuery.orEmpty())
 }.getOrDefault(false)}?.let{"https://example.invalid"}
 const val globalCards="""(function(){
  if(!['www.linkedin.com','cn.linkedin.com','hk.linkedin.com'].includes(location.hostname)||!location.pathname.startsWith('/jobs/hutchmed-jobs-worldwide')||new URL(location.href).searchParams.get('f_C')!=='27069365')return null;
  const root=document.querySelector('.jobs-search__results-list');if(!root)return null;
  const rows=[...root.querySelectorAll('.base-card')].map(n=>{
   const company=n.querySelector('.base-search-card__subtitle a');if(!company)return null;
   const c=new URL(company.href);if(c.pathname.replace(/\/$/,'')!=='/company/hutchmed')return null;
   const a=n.querySelector('a.base-card__full-link'),title=n.querySelector('.base-search-card__title');if(!a||!title)return null;
   const u=new URL(a.href);if(!['www.linkedin.com','cn.linkedin.com','hk.linkedin.com'].includes(u.hostname)||!/^\/jobs\/view\/.+-[0-9]+\/?$/.test(u.pathname))return null;
   return {url:u.origin+u.pathname,text:title.innerText.trim()};
  }).filter(Boolean);
  const raw=(document.querySelector('.results-context-header__job-count')?.innerText||'').trim();
  const count=/^[0-9]+$/.test(raw)?Number(raw):null;
  const more=document.querySelector('.infinite-scroller__show-more-button');if(more)more.setAttribute('data-job27-more','');
  return {rows,count,hasMore:count===null||rows.length<count,button:!!more};
 })()"""
 fun links(raw:String,page:JSONObject,configured:List<String>):List<String>{
  if(raw!="https://example.invalid"||page.s("url")!=raw||PhoneJobs.needsVerification(page.s("text")))return emptyList()
  val urls=page.arr("links").map{it.s("url")}
  if("https://example.invalid" !in urls&&"https://example.invalid" !in urls)return emptyList()
  // The official China portal uses the locale in its path. Reuse only an unfiltered
  // configured route; do not treat a filtered or different company's listing as its inventory.
  val china=configured.firstOrNull{runCatching{
   val u=URI(RecruitmentListingUrl.stable(it));u.scheme=="https"&&u.host=="recruit.hutch-med.com"&&u.path in listOf("/sc","/en")&&u.rawFragment=="/jobs"&&u.rawQuery==null&&u.rawUserInfo==null&&u.port in listOf(-1,443)
  }.getOrDefault(false)}?.let{RecruitmentListingUrl.stable(it)}?:"https://example.invalid"
  return listOf(china)
 }
}
