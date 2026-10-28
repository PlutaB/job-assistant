package chat.plutab.job27

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Correct only the known biologics-company/medical-group mix-up, retaining all history. */
internal object CompanySourceIdentity {
 const val bioId="00000000-0000-4000-8000-000000000001"
 const val bioName="天士力生物医药股份有限公司"
 const val bioListing="https://example.invalid"
 const val oldListing="https://example.invalid"
 const val oldCampus="https://example.invalid"
 const val reason="原招聘来源属于天士力医药集团，已改为天士力生物独立主体；原岗位及评分保留为历史来源，不计入当前生物岗位"
 fun target(c:JSONObject)=false //c.s("id")==bioId&&c.s("name")=="天士力生物"
 fun wrongSource(url:String)=url==oldListing||url==oldCampus
 fun wrongJob(url:String)=runCatching{val u=URI(url);u.scheme=="https"&&u.host=="www.zhaopin.com"&&u.userInfo==null&&u.port in listOf(-1,443)&&Regex("/jobdetail/CC516730880J[0-9]+[.]htm").matches(u.path)}.getOrDefault(false)
 fun currentJob(j:JSONObject)=!j.optBoolean("sourceCompanyMismatch")
 fun displayCompany(job:JSONObject,c:JSONObject?):JSONObject?=if(job.optBoolean("sourceCompanyMismatch")&&c!=null)RefreshEfficiency.shallow(c).put("name",job.s("sourceCompanyName")).put("englishName","")else c
 fun snapshot(data:JSONObject):Boolean{
  val c=data.arr("companies").firstOrNull{target(it)}?:return false
  if(c.optInt("sourceIdentityVersion")>=1)return false
  val oldLinks=c.arr("links").filter{wrongSource(it.s("url"))}
  if(oldLinks.isEmpty()&&data.arr("jobs").none{it.s("companyId")==bioId&&wrongJob(it.s("url"))})return false // Never replace an unrelated, user-selected source.
  c.put("sourceHistory",JSONArray(c.arr("sourceHistory")+oldLinks.map{RefreshEfficiency.shallow(it).put("reason",reason)}))
  val links=c.arr("links").filterNot{wrongSource(it.s("url"))}.toMutableList()
  if(links.none{it.s("url")==bioListing})links.add(json("name" to "天士力生物医药股份有限公司 · 官方企业招聘页","url" to bioListing))
  c.put("links",JSONArray(links));if(wrongSource(c.s("website")))c.put("website",bioListing)
  val oldCounts=JSONObject();for(k in listOf("snapshot","lastSuccess","lastCompleteSnapshot","snapshotJobIds","applied"))if(c.has(k)){oldCounts.put(k,c.get(k));c.remove(k)}
  c.put("previousSourceCounts",oldCounts).put("sourceIdentityVersion",1).put("sourceIdentityCorrected",true).put("refreshWarning",true).put("refreshError",reason).put("lastResultState","partial")
  for(job in data.arr("jobs"))if(job.s("companyId")==bioId&&wrongJob(job.s("url"))){
   job.put("sourceCompanyMismatch",true).put("sourceCompanyName","天士力医药集团股份有限公司（历史来源）").put("sourceCompanyMismatchReason",reason)
  }
  c.put("applied",data.arr("jobs").count{it.s("companyId")==bioId&&currentJob(it)&&it.s("appliedAt").isNotBlank()})
  return true
 }
 /** Call store.save before this method: retainedFactKey points to the original atomic fact file. */
 fun journal(j:JSONObject):Boolean{
  if(j.s("companyId")!=bioId||j.arr("entries").none{wrongSource(it.s("url"))})return false
  val old=j.arr("entries").filter{wrongSource(it.s("url"))}
  val archived=j.optJSONObject("excludedCompanyJobs")?:JSONObject()
  val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j)
  for(key in q.keys().asSequence().toList()){
   val row=q.getJSONObject(key)
   if(!wrongJob(row.s("url"))&&!row.strings("sources").any{wrongSource(it)})continue
   archived.put(key,json("link" to row,"retainedFactKey" to if(facts.has(key))key else null,"manualGrade" to j.optJSONObject("manualGrades")?.opt(key),"reason" to reason))
   q.remove(key);facts.remove(key);j.getJSONObject("failedDetails").remove(key);j.optJSONObject("ratingErrors")?.remove(key);j.optJSONObject("manualGrades")?.remove(key)
  }
  val entries=j.arr("entries").filterNot{wrongSource(it.s("url"))}.toMutableList()
  if(entries.none{it.s("url")==bioListing})entries.add(json("url" to bioListing,"done" to false,"nextPage" to 1,"keys" to JSONArray(),"total" to JSONObject.NULL))
  j.put("sourceEntryHistory",JSONArray(j.arr("sourceEntryHistory")+old)).put("excludedCompanyJobs",archived).put("entries",JSONArray(entries)).put("listingComplete",false).put("fullyComplete",false).put("detailsDone",false).put("state","partial").put("capturePaused",true).put("reviewPaused",true).put("lastError",reason)
  j.put("retainedMatches",JSONArray(j.strings("retainedMatches").filter{q.has(it)}))
  for(k in listOf("viewCounts","lastResult","result"))j.remove(k)
  return true
 }
}
