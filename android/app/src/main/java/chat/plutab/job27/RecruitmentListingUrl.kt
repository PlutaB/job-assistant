package chat.plutab.job27
import java.net.URI
object RecruitmentListingUrl {
 /** This legacy URL was verified to redirect to the same official career inventory. */
 fun verifiedRedirect(raw:String):String?=null

 /** A known category alias can defer to its exact configured canonical listing, including filters. */
 fun resolveAliases(j:org.json.JSONObject){
  val entries=j.arr("entries")
  for(e in entries){val raw=e.s("url");val canonical=stable(raw)
   if(canonical==raw)continue
   val target=entries.firstOrNull{it!==e&&it.s("url")==canonical}?:continue
   val done=target.optBoolean("done")&&!target.isNull("total")
   val deferred=URI(raw).let{it.host=="recruit.hutch-med.com"&&it.path=="/sc"&&it.rawFragment in listOf(null,"","/")}
   if(deferred){e.put("aliasOf",canonical).put("kind","reference");if(e.s("error").isNotBlank())e.put("previousError",e.s("error"))}
   if(done||deferred)e.put("aliasOf",canonical)else e.remove("aliasOf")
   e.put("done",done)
   if(done){e.put("kind","reference").put("total",0).put("keys",org.json.JSONArray());e.remove("error");e.remove("retryableError")}
  }
 }
 /** Follow only same-host official listing links exposed by a category/company landing page. */
 fun linkedListings(raw:String,page:org.json.JSONObject):List<String> = runCatching{
  val u=URI(raw);val p=u.path.trimEnd('/').lowercase()
  val category=if(u.host.endsWith(".zhiye.com")&&p in listOf("/social","/campus","/intern"))p else null
  page.arr("links").map{it.s("url")}.filter{target->runCatching{val v=URI(target)
   val cspc=u.host=="www.e-cspc.com"&&p=="/hr/index.html"&&v.host=="ehr.e-cspc.com"&&v.port==8081
   v.scheme=="https"&&(v.host==u.host||cspc)&&v.rawUserInfo==null&&(v.port in listOf(-1,443)||cspc)&&target!=raw&&when{
    cspc->Regex("/RedseaPlatform/zhaopin/zpChannelQrCode/[a-f0-9-]+/officeWebsite[.]mob").matches(v.path)
    u.host=="jobs.gsk.com"&&p=="/gb/en"->v.path in listOf("/gb/search-results","/gb/en/search-results")
    category!=null->v.path.lowercase()=="$category/jobs"||Regex("/[0-9]+/jobs").matches(v.path)
    u.host.endsWith("moseeker.com")&&Regex("/company/[0-9]+").matches(p)->v.path=="/positions/index/cid/"+p.substringAfterLast('/')
    else->false
   }
  }.getOrDefault(false)}.distinct()
 }.getOrDefault(emptyList())
 /** Official IDs remain identical across category routes or Hotjob desktop/mobile suites. */
 fun jobIdentity(raw:String):String?=runCatching{
  val u=URI(raw)
  if(u.scheme=="https"&&u.host=="www.zhaopin.com"&&u.rawUserInfo==null&&u.port in listOf(-1,443)){
   Regex("/jobdetail/([A-Za-z0-9]+)[.]htm").matchEntire(u.path)?.groupValues?.get(1)?.let{return@runCatching raw}
  }
  if(u.scheme=="https"&&u.host=="ehr.e-cspc.com"&&u.port==8081&&u.rawUserInfo==null&&u.path=="/RedseaPlatform/zhaopin/zpChannelQrCode/officePage.mob"){
   val id=Regex("(?:^|&)detailRequestId=([a-f0-9-]+)(?:&|$)").find(u.rawQuery.orEmpty())?.groupValues?.get(1)
   if(id!=null)return@runCatching "ehr.e-cspc.com#post:$id"
  }
  if(u.host in listOf("gzmpc.hotjob.cn","sc.hotjob.cn")&&u.path.startsWith("/wt/GZMPC/")&&(u.path.endsWith("webPositionN300!getOnePosition")||u.path.endsWith("/position/detail"))){
   val id=Regex("(?:^|&)(?:postId|postIdsAry)=([0-9]+)(?:&|$)").find(u.rawQuery.orEmpty())?.groupValues?.get(1)
   if(id!=null)return@runCatching "gzmpc.hotjob.cn#post:$id"
  }
  if(u.host=="www.ajinga.com"){
   val id=(Regex("/job-detail-new/([0-9]+)/c/?").matchEntire(u.path)?.groupValues?.get(1)
    ?:Regex("/recruiting/company/[0-9]+/job/([0-9]+)/job-detail/?").matchEntire(u.path)?.groupValues?.get(1))
   if(id!=null)return@runCatching raw
  }
  if(u.host=="wecruit.hotjob.cn"&&Regex("/SU[a-fA-F0-9]+/(?:pb/posDetail\\.html|mc/detail)").matches(u.path)){
   val id=Regex("(?:^|&)postId=([a-fA-F0-9]{24})(?:&|$)").find(u.rawQuery.orEmpty())?.groupValues?.get(1)
   if(id!=null)return@runCatching u.host+"#post:"+id.lowercase()
  }
  if(!u.host.orEmpty().endsWith(".zhiye.com")||!u.path.endsWith("/detail"))return@runCatching null
  val id=Regex("(?:^|&)jobAdId=([A-Za-z0-9-]+)(?:&|$)",RegexOption.IGNORE_CASE).find(u.rawQuery.orEmpty())?.groupValues?.get(1)?:return@runCatching null
  "https://${u.host}/detail?jobAdId=$id"
 }.getOrNull()
 /** Repair known duplicate keys without deleting any original fact files or cached jobs. */
 fun needsIdentityMigration(j:org.json.JSONObject)=RefreshJournal.queue(j).keys().asSequence().any{k->jobIdentity(RefreshJournal.queue(j).getJSONObject(k).s("url"))?.let{it!=k}==true}
 fun migrateIdentities(j:org.json.JSONObject):Int{
  val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j);val mapping=linkedMapOf<String,String>()
  for(k in q.keys().asSequence().toList()){val row=q.getJSONObject(k);val next=jobIdentity(row.s("url"))?:continue;if(next==k)continue
   mapping[k]=next
   val existing=q.optJSONObject(next)
   if(existing==null)q.put(next,row)else existing.put("sources",org.json.JSONArray((existing.strings("sources")+row.strings("sources")).distinct()))
   val fact=facts.optJSONObject(k);val current=facts.optJSONObject(next)
   if(fact!=null&&(current==null||!current.optBoolean("ratingReceived")&&fact.optBoolean("ratingReceived"))){facts.put(next,fact);RefreshJournal.markFact(j,next)}
   q.remove(k);facts.remove(k)
   for(field in listOf("failedDetails","retryableDetails","detailFetchCount","ratingErrors")){val values=j.optJSONObject(field)?:continue;if(values.has(k)){if(!values.has(next))values.put(next,values.get(k));values.remove(k)}}
  }
  if(mapping.isNotEmpty()){
   fun keys(values:List<String>)=values.map{mapping[it]?:it}.distinct()
   for(e in j.arr("entries")){e.put("keys",org.json.JSONArray(keys(e.strings("keys"))));for(field in listOf("numberedPages","cardPageKeys"))e.optJSONObject(field)?.let{pages->for(page in pages.keys().asSequence().toList())pages.put(page,org.json.JSONArray(keys(pages.strings(page))))}}
   val archive=j.optJSONObject("mergedDuplicateKeys")?:org.json.JSONObject();for((old,next)in mapping)archive.put(old,next);j.put("mergedDuplicateKeys",archive)
  }
  return mapping.size
 }
 /** Preserve every configured filter; use the site's own stable title sort across pages. */
 fun stable(raw:String):String {
  verifiedRedirect(raw)?.let{return it}
  val u=URI(raw)
  if(u.host?.endsWith(".zhiye.com")==true&&u.path.trimEnd('/').lowercase() in listOf("/social","/campus","/intern")){
   val path=u.path.trimEnd('/').lowercase()+"/jobs"
   return "https://${u.rawAuthority}$path"+(u.rawQuery?.let{"?$it"}?:"")+(u.rawFragment?.let{"#$it"}?:"")
  }
  if(u.host?.endsWith("novartis.com.cn")!=true||u.path!="/careers/career-search")return raw
  if(Regex("(?:^|&)sort=").containsMatchIn(u.rawQuery.orEmpty()))return raw
  val extra="sort=asc&order=%E8%81%8C%E4%BD%8D%E5%90%8D%E7%A7%B0"
  return raw.substringBefore('#')+(if(u.rawQuery.isNullOrEmpty())"?"else"&")+extra
 }
}
