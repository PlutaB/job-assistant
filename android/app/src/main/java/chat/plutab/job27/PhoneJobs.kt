package chat.plutab.job27

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Facts extracted on the phone; model output can only supply ratings. */
object PhoneJobs {
 fun textHash(text:String):String{val bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray());val hex="0123456789abcdef";return buildString(64){for(b in bytes){val value=b.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])}}}
 fun fingerprint(job:JSONObject,profile:JSONObject):String{val fields=listOf("title","description","location","department","recruitmentType");val value=JSONArray(fields.map{job.s(it)}).put(profile.toString()).put("rating-policy-v1").toString();return textHash(value)}
 fun canonical(raw:String)=RecruitmentListingUrl.jobIdentity(raw)?:runCatching{val u=URI(raw);URI(u.scheme,u.authority,u.path.trimEnd('/'),u.query,u.fragment).toString()}.getOrDefault(raw)
 fun allowed(raw:String,hosts:Set<String>):Boolean=runCatching{val u=URI(raw);u.scheme=="https"&&u.host in hosts&&u.rawUserInfo==null&&(!(u.host=="apply.workable.com"&&"biocytogen.com.cn" in hosts)||u.path.startsWith("/biocytogen/"))&&(u.port in listOf(-1,443)||u.host=="ehr.e-cspc.com"&&u.port==8081)}.getOrDefault(false)
 private fun structures(page:JSONObject):List<JSONObject>{val result=mutableListOf<JSONObject>();fun walk(v:Any?){when(v){is JSONObject->{result.add(v);for(k in v.keys())if(k!="description")walk(v.opt(k))};is JSONArray->for(i in 0 until v.length())walk(v.opt(i))}};for(raw in page.strings("jsonld"))runCatching{walk(if(raw.trim().startsWith("["))JSONArray(raw) else JSONObject(raw))};return result}
 fun listCount(page:JSONObject,visibleRows:Int):Int?{
  val state=page.optJSONObject("officialListState")
  val total=when{
   state?.s("kind")=="official-total"->state.optInt("count",-1).takeIf{it>=0}
   state?.s("kind")=="hotjob-mobile-cumulative"&&!state.optBoolean("hasMore")&&state.optInt("count")==visibleRows->visibleRows
   else->count(page)
  }
  return total.takeUnless{it==0&&visibleRows>0}
 }
 fun needsVerification(text:String)=Regex("验证码|访问过于频繁|(?:请完成|请进行|需要进行|正在进行)(?:人机|安全)验证|(?:^|[\\n ])(?:安全验证|人机验证)(?:$|[\\n ])|captcha|access denied|verifying the safety of the connection|protected by Tencent Cloud EdgeOne|verify you are human|security verification",RegexOption.IGNORE_CASE).containsMatchIn(text)
 fun count(page:JSONObject):Int?{val structured=structures(page).firstOrNull{it.s("@type")=="ItemList"&&it.has("numberOfItems")};if(structured!=null)return structured.optInt("numberOfItems").takeIf{it>=0};val body=page.s("text");val patterns=listOf("共\\s*(\\d+)\\s*条记录","在招职位\\s*(\\d+)\\s*个","(?:共(?:有)?|total\\s*[:：]?)\\s*(\\d+)\\s*(?:个?\\s*(?:职位|岗位)|jobs?|positions?)","(?:^|[|\\n])\\s*(\\d+)\\s*结果(?:\\s|[|]|$)","Showing\\s+(\\d+)\\s+results?","(\\d+)\\s+中国\\s*的职位","全部职位[（(]共\\s*(\\d+)\\s*个[）)]");for(p in patterns)Regex(p,RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()?.let{return it};return if(Regex("无任何在招职位|暂无(?:在招)?(?:职位|岗位)|no (?:open )?(?:jobs|positions)(?: available| found)?",RegexOption.IGNORE_CASE).containsMatchIn(body))0 else null}
 private fun knownSearchLink(raw:String):Boolean=runCatching{
  val u=URI(raw)
  u.host.endsWith(".zhiye.com")&&Regex("/(?:jobs|(?:campus|social|intern)(?:/jobs)?)/?",RegexOption.IGNORE_CASE).matches(u.path)
 }.getOrDefault(false)
 fun jobLink(raw:String):Boolean=runCatching{
  val u=URI(raw);val path=u.path.orEmpty();val fragment=u.fragment.orEmpty()
  when{
   RecruitmentLinkPolicy.navigation(raw)->false
   u.host=="www.bettapharma.com"&&Regex("^position=[0-9]+$").matches(fragment)->true
   u.host=="app.mokahr.com"->Regex("^/job/[^/?#]+").containsMatchIn(fragment)
   u.host=="chinacampus.jnj.com.cn"&&fragment.startsWith("/position?")&&Regex("(?:^|[?&])category=[A-Za-z0-9_-]+").containsMatchIn(fragment)&&Regex("(?:^|&)posinx=[0-9]+(?:&|$)").containsMatchIn(fragment)->true
   u.host in listOf("careersite.tupu360.com","chinacampus.jnj.com.cn")->path.endsWith("/position/detail")&&Regex("(?:^|&)positionId=[A-Za-z0-9_-]+").containsMatchIn(u.query.orEmpty())
   u.host?.endsWith("novartis.com.cn")==true->path.contains("/careers/career-search/job/details/")
   Regex("/(?:jobs?|positions?|requisitions?)/(?:index|search)/?$",RegexOption.IGNORE_CASE).containsMatchIn(path)->false
   Regex("/(?:jobs?|positions?|requisitions?)/?$",RegexOption.IGNORE_CASE).containsMatchIn(path)&&!Regex("(?:job|position)[_-]?id=",RegexOption.IGNORE_CASE).containsMatchIn(u.query.orEmpty())->false
   fragment.isNotBlank()&&!fragment.startsWith("/job/")&&path.endsWith("career-search")->false
   else->true
  }
 }.getOrDefault(false)
 fun discover(page:JSONObject,hosts:Set<String>):List<JSONObject>{val pattern=Regex("(?:[/?#](?:jobs?|positions?|requisitions?)(?:[/=?#]|$)|job(?:ad)?[_-]?id=|position[_-]?id=|post[_-]?id=|#/job/|/jobdetail/|/zpdetail/[0-9]+|/join/details-[0-9]+\\.html)",RegexOption.IGNORE_CASE);return page.arr("links").filter{a->val url=a.s("url");!Regex("^(下一页|下页|next(?: page)?|加载更多|Load more|职位列表|全部职位|首页)$",RegexOption.IGNORE_CASE).matches(a.s("text"))&&allowed(url,hosts)&&jobLink(url)&&!RecruitmentLinkPolicy.wrongZhaopinCompany(url,listOf(page.s("url")))&&a.s("rel")!="next"&&a.s("rel")!="prev"&&!knownSearchLink(url)&&a.s("text").isNotBlank()&&(pattern.containsMatchIn(url)||(runCatching{URI(url).host=="ehr.e-cspc.com"&&Regex("(?:requestId|applyId)=",RegexOption.IGNORE_CASE).containsMatchIn(URI(url).query.orEmpty())}.getOrDefault(false))||(runCatching{URI(url).host=="biocytogen.com.cn"&&URI(url).path.startsWith("/career/")}.getOrDefault(false))||(runCatching{URI(url).host=="jobs.sanofi.cn"&&URI(url).path.startsWith("/工作/")}.getOrDefault(false)))&&!Regex("/?(?:jobs|positions|requisitions)/?",RegexOption.IGNORE_CASE).matches(runCatching{URI(url).fragment}.getOrNull().orEmpty())&&canonical(url)!=canonical(page.s("url"))}.distinctBy{canonical(it.s("url"))}}
 fun date(raw:String):String?{val value=raw.trim().replace('/','-');return runCatching{if(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)){java.time.LocalDate.parse(value);value}else{Instant.parse(value);value}}.getOrNull()}
 private fun clean(raw:String)=raw.replace(Regex("<[^>]+>")," ").replace("&nbsp;"," ").replace("&amp;","&").trim()
 fun detailProblem(page:JSONObject,title:String):String=when{
  needsVerification(page.s("text"))->"官网要求安全验证"
  RecruitmentLinkPolicy.navigation(page.s("url"))||RecruitmentLinkPolicy.listing(page.s("url"))->"详情链接返回了职位列表"
  page.optBoolean("truncated")->"网页正文读取被截断"
  page.s("text").length<80&&!page.optBoolean("officialDescription")->"页面未返回可识别的岗位正文（${page.s("text").length}字）"
  title.isBlank()->"官网未返回岗位名称"
  else->"未识别到岗位职责、任职要求或官网正文区域"
 }
 fun extract(page:JSONObject,title:String):JSONObject?{
  if(RecruitmentLinkPolicy.navigation(page.s("url")))return null
  val body=page.s("text");if(body.length<80&&!page.optBoolean("officialDescription")||page.optBoolean("truncated")||needsVerification(body))return null
  val postings=structures(page).filter{it.s("@type")=="JobPosting"||it.strings("@type").contains("JobPosting")};val posting=if(postings.size==1)postings.first()else postings.singleOrNull{canonical(it.s("url"))==canonical(page.s("url"))}
  if(posting!=null&&posting.s("title").isNotBlank()&&posting.s("description").isNotBlank()){
   val loc=posting.optJSONObject("jobLocation")?:posting.optJSONArray("jobLocation")?.optJSONObject(0);val address=loc?.optJSONObject("address");val dept=posting.optJSONObject("department")?.s("name")?:posting.s("department")
   val deadline=date(posting.s("validThrough"));val closed=deadline?.let{runCatching{if(it.length==10)java.time.LocalDate.parse(it).isBefore(java.time.LocalDate.now())else Instant.parse(it).isBefore(Instant.now())}.getOrDefault(false)}?:false
   return json("title" to clean(posting.s("title")),"url" to page.s("url"),"officialId" to (posting.optJSONObject("identifier")?.s("value")?:posting.s("identifier")),"location" to address?.let{listOf("addressLocality","addressRegion","addressCountry").map{key->it.s(key)}.filter{it.isNotBlank()}.joinToString(" · ")}.orEmpty(),"department" to dept,"description" to clean(posting.s("description")),"recruitmentType" to posting.s("employmentType"),"publishedAt" to date(posting.s("datePosted")),"active" to if(closed)"closed" else "open").also{JobMetadataFacts.normalize(it,body)}
  }
  if(!Regex("岗位职责|工作职责|职位描述|任职要求|任职条件|职位要求|responsibilit|qualification|job description",RegexOption.IGNORE_CASE).containsMatchIn(body)&&!(page.optBoolean("officialDescription")&&page.s("descriptionText").isNotBlank()))return null
  var name=title.split(Regex("\\n|发布于|发布时间|发布日期")).first().trim().take(300);var location="";var department="";var kind=""
  val headerFields=JSONObject()
  if(URI(page.s("url")).host.endsWith("mokahr.com")){
   val header=Regex("([^\\n]+)\\n分享\\n([^\\n]*\\|[^\\n]+)\\n发布于").find(body)
   if(header!=null){name=header.groupValues[1].trim();val fields=JobMetadataFacts.header(header.groupValues[2].split('|'));for(key in fields.keys())headerFields.put(key,fields.get(key));location=fields.s("location")}
  }
  if(name.isBlank())return null
  val published=Regex("(?:发布时间|发布日期|发布于|Date posted)\\s*[:：]?\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2})",RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)

  return json("title" to name,"url" to page.s("url"),"officialId" to (Regex("#/job/([^/?#]+)").find(page.s("url"))?.groupValues?.get(1)?:""),"location" to location,"department" to department,"description" to page.s("descriptionText",body),"recruitmentType" to kind,"publishedAt" to published?.let{date(it)},"active" to if(Regex("职位已关闭|岗位已下架|position (?:is )?closed|no longer accepting",RegexOption.IGNORE_CASE).containsMatchIn(body))"closed" else "open").also{row->for(key in headerFields.keys())row.put(key,headerFields.get(key));JobMetadataFacts.normalize(row,body)}
 }
 fun ratings(raw:String,expected:Set<String>):Map<String,JSONObject>{val clean=raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim();val rows=JSONObject(clean).getJSONArray("ratings");val result=mutableMapOf<String,JSONObject>();for(i in 0 until rows.length()){val r=rows.getJSONObject(i);val id=r.getString("id");require(id in expected&&!result.containsKey(id)){"评级返回了未知或重复岗位"};require(r.has("grade")&&(r.isNull("grade")||r.s("grade") in listOf("S","A","B","U"))){"评级格式无效"};if(r.s("grade") in listOf("S","A","B"))require(r.s("reason").isNotBlank()){"S/A/B评级缺少理由"};result[id]=r};require(result.keys==expected){"评级没有覆盖本批所有岗位"};return result}
 fun merge(snapshot:JSONObject,companyId:String,taskId:String,facts:List<JSONObject>,stamp:String):Pair<JSONObject,JSONObject>{
  val result=RefreshEfficiency.shallow(snapshot).put("companies",JSONArray(snapshot.arr("companies").map{RefreshEfficiency.shallow(it)}));val all=snapshot.arr("jobs").map{if(it.s("companyId")==companyId)RefreshEfficiency.shallow(it)else it}.toMutableList();var added=0;var changed=0
  fun officialKey(j:JSONObject):String?=j.s("officialId").takeIf{it.isNotBlank()}?.let{id->runCatching{URI(j.s("url")).host+"|"+id}.getOrNull()}
  val byUrl=linkedMapOf<String,JSONObject>();val byOfficial=linkedMapOf<String,JSONObject>()
  for(j in all.filter{it.s("companyId")==companyId}){byUrl.putIfAbsent(canonical(j.s("url")),j);officialKey(j)?.let{byOfficial.putIfAbsent(it,j)}}
  for(raw in facts){JobMetadataFacts.normalize(raw);val old=officialKey(raw)?.let{byOfficial[it]}?:byUrl[canonical(raw.s("url"))]
   val j=old?:json("id" to UUID.randomUUID().toString(),"companyId" to companyId,"manual" to false,"manualGrade" to false,"status" to "未投","appliedAt" to null,"notes" to "","resumeId" to null,"history" to JSONArray(),"firstSeenAt" to stamp,"newInTask" to taskId)
   if(old==null){added++;all.add(j)}else if(JobBodyStore.hash(j)!=JobBodyStore.hash(raw)||j.s("suggestedGrade")!=raw.s("grade"))changed++
   for(k in listOf("title","location","description","recruitmentType")){if(!(j.optBoolean("manual")&&k in listOf("title","location")&&j.s(k).isNotBlank())&&(if(k=="description"&&raw.opt(k) is DeferredDescription)true else raw.s(k).isNotBlank()))j.put(k,raw.get(k))}
   if(!j.optBoolean("manualDepartment")&&!(j.optBoolean("manual")&&j.s("department").isNotBlank())&&raw.s("department").isNotBlank())j.put("department",raw.get("department"))
   for(k in listOf("url","officialId"))if(raw.s(k).isNotBlank()&&(k!="url"||j.s("url").isBlank()))j.put(k,raw.get(k))
   if(!raw.isNull("publishedAt")&&raw.s("publishedAt").isNotBlank())j.put("publishedAt",raw.get("publishedAt"))
   for(field in listOf("education","employmentType","jobCode","jobCategory","departmentEvidence"))if(raw.s(field).isNotBlank())j.put(field,raw.get(field))
   JobMetadataFacts.normalize(j)
   if(!j.has("publishedAt"))j.put("publishedAt",JSONObject.NULL)
   if(raw.s("ratingFingerprint").isNotBlank())j.put("ratingFingerprint",raw.s("ratingFingerprint"))
   j.put("lastVerifiedAt",raw.s("verifiedAt",raw.s("lastVerifiedAt",stamp))).put("active",raw.s("active","unknown"));val grade=raw.opt("grade")?:JSONObject.NULL
   if(raw.optBoolean("ratingReceived")||raw.s("grade") in listOf("S","A","B","U")||old==null){j.put("suggestedGrade",grade);if(!j.optBoolean("manualGrade"))j.put("grade",grade);j.put("reason",raw.s("reason","资料不足，待审核")).put("pendingConditions",raw.s("pendingConditions"))}
   else j.put("pendingConditions","本轮尚未取得有效评级，保留上次评级；"+raw.s("pendingConditions"))
   j.put("sources",JSONArray((j.strings("sources")+raw.s("url")+raw.strings("sources")).distinct()))
   byUrl[canonical(raw.s("url"))]=j;officialKey(raw)?.let{byOfficial[it]=j}
  }
  // Merge never promotes completeness. The runner separately verifies the journal and updates counts.
  val company=result.arr("companies").first{it.s("id")==companyId};company.put("lastAttempt",stamp).put("latestTask",taskId);company.put("partialVerified",facts.size).put("partialMatched",facts.count{it.s("grade") in listOf("S","A","B")}).put("lastResultState","partial");company.put("applied",all.count{it.s("companyId")==companyId&&CompanySourceIdentity.currentJob(it)&&it.s("appliedAt").isNotBlank()})
  result.put("jobs",JSONArray(all.sortedWith(compareBy<JSONObject>{gradeOrder(it)}.thenByDescending{it.s("firstSeenAt")})))
  return result to json("added" to added,"changed" to changed,"verified" to facts.size,"matched" to facts.count{it.s("grade") in listOf("S","A","B")},"applied" to company.optInt("applied"))
 }
}
