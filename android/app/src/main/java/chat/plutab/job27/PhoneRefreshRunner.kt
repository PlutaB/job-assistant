package chat.plutab.job27

import android.app.Application
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

typealias RefreshCollector = suspend (JSONObject,JSONObject,suspend()->Unit,suspend(JSONObject,JSONObject)->Unit,suspend(String,String)->Unit)->Unit

/** Stateless runner: all durable work is in the journal; UI lifetime is irrelevant. */
class PhoneRefreshRunner(
 private val app:Application,private val current:()->JSONObject,
 private val persist:suspend(((JSONObject)->JSONObject))->Unit,
 private val stage:suspend(String,String,String,String)->Unit,
 private val emit:suspend(String,String,JSONObject,String)->Unit,
 private val rate:suspend(String,suspend(String,JSONObject,String)->Unit)->String,
 private val collector:RefreshCollector?=null,
 private val progress:suspend(String,JSONObject)->Unit={_,_->},
 private val readJobs:suspend(List<JSONObject>)->List<JSONObject> = {it}
){
 private fun now()=Instant.now().toString()
 private fun profile():JSONObject=PersonalFacts.ratingContext(current().optJSONObject("profile")?:JSONObject())
 private fun synchronizeCounts(data:JSONObject,j:JSONObject,state:String,prefs:JSONObject,fingerprint:(JSONObject)->String):JSONObject{
  val c=data.arr("companies").first{it.s("id")==j.s("companyId")};val keys=RefreshJournal.queue(j).keys().asSequence().toSet();val indexed=data.arr("jobs").filter{it.s("companyId")==c.s("id")&&runCatching{RefreshJournal.key(it.s("url")) in keys}.getOrDefault(false)}
  if(!c.has("lastCompleteSnapshot")&&c.optJSONObject("snapshot")!=null&&c.s("lastSuccess").isNotBlank())c.put("lastCompleteSnapshot",JSONObject(c.getJSONObject("snapshot").toString()))
  if(keys.isNotEmpty()||j.arr("entries").all{it.optBoolean("done")})c.put("snapshot",json("total" to keys.size,"matched" to indexed.count{it.s("grade") in listOf("S","A","B")},"totalIncomplete" to !j.optBoolean("listingComplete"),"matchedIncomplete" to (RefreshJournal.pendingRatings(j,prefs,fingerprint).isNotEmpty()||RefreshJournal.facts(j).length()<keys.size)))
  c.put("lastAttempt",now()).put("lastResultState",state).put("latestTask",j.s("taskId"));if(state=="complete"){c.put("lastSuccess",now()).put("lastCompleteSnapshot",c.get("snapshot"));c.put("snapshotJobIds",JSONArray(indexed.map{it.s("id")}))};return data
 }
 suspend fun run(company:JSONObject,j:JSONObject,store:RefreshJournal,captureOnly:Boolean=false,reviewOnly:Boolean=false){
  if(HutchDomesticScope.needs(j))withContext(Dispatchers.IO){store.save(j);HutchDomesticScope.apply(j);store.save(j)}
  val prefs=profile();val fingerprints=RatingFingerprintCache(prefs)
  withContext(Dispatchers.IO){for(f in RefreshJournal.objects(RefreshJournal.facts(j)))fingerprints.get(f)}
  if(!captureOnly&&!reviewOnly&&RefreshJournal.facts(j).length()>0&&RefreshJournal.pendingRatings(j,prefs,fingerprints::get).isNotEmpty()){
   run(company,j,store,reviewOnly=true)
   if(j.optBoolean("fullyComplete"))return
  }
  if(RecruitmentLinkPolicy.needsQuarantine(j)||RecruitmentListingUrl.needsIdentityMigration(j))withContext(Dispatchers.IO){
   // Commit original facts first. Their files and cached historical jobs remain recoverable.
   store.save(j);RecruitmentListingUrl.migrateIdentities(j);RecruitmentLinkPolicy.quarantine(j,archiveSavedFacts=true);store.save(j)
  }
  val id=j.s("taskId");val companyId=company.s("id");if(collector==null)RefreshJournal.trackFacts(j);j.put("state","running");RefreshJournal.resetRatings(j,prefs,fingerprints::get)
  suspend fun save()=withContext(Dispatchers.IO){store.save(j)}
  suspend fun report(taskId:String,step:String,state:String,text:String){
   progress(taskId,json("total" to RefreshJournal.total(j),"savedDetails" to RefreshJournal.facts(j).length(),"rated" to RefreshJournal.objects(RefreshJournal.facts(j)).count{it.optBoolean("ratingReceived")},"listingComplete" to j.optBoolean("listingComplete"),"entryFailures" to j.arr("entries").count{it.s("kind")!="reference"&&!it.optBoolean("done")&&it.s("error").isNotBlank()},"failedDetails" to j.getJSONObject("failedDetails").keys().asSequence().count{!RefreshJournal.facts(j).has(it)}))
   stage(taskId,step,state,text)
  }

  suspend fun flush(state:String="running"){
   save();persist{data->val (merged,result)=PhoneJobs.merge(data,companyId,id,RefreshJournal.objects(RefreshJournal.facts(j)),now());val baseline=j.optJSONObject("baseline")?:JSONObject();val companyRows=merged.arr("jobs").filter{it.s("companyId")==companyId};result.put("added",companyRows.count{!baseline.has(it.s("id"))}).put("changed",companyRows.count{val old=baseline.optJSONObject(it.s("id"));old!=null&&(old.s("description")!=JobBodyStore.hash(it)||old.s("grade")!=it.s("suggestedGrade"))});merged.arr("tasks").first{it.s("id")==id}.put("result",result.put("total",RefreshJournal.total(j)).put("savedDetails",RefreshJournal.facts(j).length()).put("rated",RefreshJournal.facts(j).length()-RefreshJournal.pendingRatings(j,prefs,fingerprints::get).size));synchronizeCounts(merged,j,state,prefs,fingerprints::get)}
  }
  try{
   report(id,"resuming","running","继续已保存任务：${RefreshJournal.facts(j).length()}个详情，${RefreshJournal.facts(j).length()-RefreshJournal.pendingRatings(j,prefs,fingerprints::get).size}个评级，无需从零开始")
   val q=RefreshJournal.queue(j);val facts=RefreshJournal.facts(j)
   if(!reviewOnly&&!RefreshJournal.nativeComplete(j)){
   // Upgrade/import old cached details only after their official IDs appear in this run's queue.
   val reusable=(if(j.optBoolean("allowOldCache",false))readJobs(current().arr("jobs").filter{it.s("companyId")==companyId})else emptyList()).filter{it.s("lastVerifiedAt").isNotBlank()&&it.s("description").length>=80}.associateBy{runCatching{RefreshJournal.key(it.s("url"))}.getOrDefault(it.s("url"))}
   val gate=CaptureFlushGate(q.length()+facts.length())
   suspend fun publishIfDue(){if(gate.due(q.length()+facts.length())){flush();gate.committed(q.length()+facts.length())}}
   val collect:RefreshCollector=collector?:{c,journal,savePoint,onFact,onStage->PhoneCrawler(app).collectInto(c,journal,savePoint,onFact,onStage)}
   collect(company,j,{
    for(k in q.keys().asSequence().toList()){if(!facts.has(k)){val old=reusable[k];if(old!=null&&j.optBoolean("allowOldCache",false)){
      val fact=JSONObject(old.toString());val description=fact.s("description");val marker=description.indexOf("职位描述\n");if(marker>=0)fact.put("description",description.substring(marker+5).substringBefore("申请职位\n校招项目"));if(fact.s("ratingFingerprint")==PhoneJobs.fingerprint(fact,prefs)&&fact.s("suggestedGrade") in listOf("S","A","B","U"))fact.put("grade",fact.get("suggestedGrade")).put("ratingReceived",true)else{fact.remove("ratingFingerprint");fact.put("ratingReceived",false)};facts.put(k,fact);RefreshJournal.markFact(j,k)
    }}};save()
   },{_,_->publishIfDue()},{s,m->publishIfDue();report(id,s,"running",m)})
   flush()
   }
   if(captureOnly){
    val published=current().arr("jobs").filter{it.s("companyId")==companyId}.map{RefreshJournal.key(it.s("url"))}.toSet()
    if(facts.keys().asSequence().any{it !in published})flush()
    val pending=RefreshJournal.pendingRatings(j,prefs,fingerprints::get).size
    val outcome=CaptureRunOutcome.finish(j,pending)
    if(outcome=="complete")flush("complete")else save()
    report(id,outcome,if(outcome=="waiting_rating")"queued"else outcome,CaptureRunOutcome.message(j,pending))
    return
   }
   withContext(Dispatchers.IO){for(f in RefreshJournal.objects(facts))fingerprints.get(f)}
   val previous=current().arr("jobs").filter{it.s("companyId")==companyId}.map{json("title" to it.s("title"),"url" to it.s("url"),"grade" to it.opt("grade"),"status" to it.s("status"),"notes" to it.s("notes"))}
   val cached=current().arr("jobs").filter{it.s("companyId")==companyId}.associateBy{runCatching{RefreshJournal.key(it.s("url"))}.getOrDefault(it.s("url"))}
   for(k in facts.keys().asSequence().toList()){val f=facts.getJSONObject(k);val old=cached[k];if(!f.optBoolean("ratingReceived")&&old?.s("ratingFingerprint")==fingerprints.get(f)&&old.s("suggestedGrade") in listOf("S","A","B","U")){f.put("grade",old.get("suggestedGrade")).put("reason",old.s("reason")).put("pendingConditions",old.s("pendingConditions")).put("ratingFingerprint",old.s("ratingFingerprint")).put("ratingReceived",true);RefreshJournal.markFact(j,k)}}
   flush()
   j.put("retryableReview",false)
   var ratingFailure:String?=null
   val failedThisRun=mutableSetOf<String>()
   while(RefreshJournal.pendingRatings(j,prefs,fingerprints::get).any{RefreshJournal.key(it.s("url")) !in failedThisRun}){
    currentCoroutineContext().ensureActive()
    val candidates=RefreshJournal.pendingRatings(j,prefs,fingerprints::get).filter{RefreshJournal.key(it.s("url")) !in failedThisRun}
    val prepared=withContext(Dispatchers.IO){CompanyRatingBatch.next(company.s("name"),prefs,candidates,previous)}
    val batch=prepared.jobs
    val done=facts.length()-RefreshJournal.pendingRatings(j,prefs,fingerprints::get).size
    report(id,"rating","running","公司合并审核·已保存 $done/${facts.length()}，本次 ${batch.size} 岗，剩余待审 ${candidates.size}；仅超出容量时分批")
    val batchKeys=batch.map{RefreshJournal.key(it.s("url"))}.toSet()
    val prompt=prepared.prompt
    try{
     val point="rating:"+batchKeys.sorted().joinToString("|")
     val ratings=retryRefreshPoint(onRetry={attempt,error->
      j.getJSONObject("ratingErrors").put(point,error);save()
      report(id,"rating","running","同一批 ${batch.size} 个岗位审核失败，自动重试 $attempt：$error")
     }){
      val reply=rate(prompt){role,payload,status->emit(id,role,payload,status)}
      PhoneJobs.ratings(reply,batch.indices.map{it.toString()}.toSet())
     }
     j.getJSONObject("ratingErrors").remove(point)
     for((i,f)in batch.withIndex()){val r=ratings.getValue(i.toString());f.put("ratingReceived",true).put("grade",r.opt("grade")?:JSONObject.NULL).put("reason",if(r.s("grade") in listOf("S","A","B"))r.s("reason") else if(r.isNull("grade"))"资料不足，待核实" else "GPT评级：U").put("pendingConditions","").put("ratingFingerprint",fingerprints.get(f));RefreshJournal.markFact(j,RefreshJournal.key(f.s("url")))}
     j.getJSONObject("ratingErrors").remove("last");j.remove("batchSize");flush()
    }catch(e:Exception){if(e is CancellationException||e is GptQuotaExhausted)throw e;ratingFailure=e.message?:"评级失败";if(RefreshFailures.temporary(e))j.put("retryableReview",true);j.remove("batchSize");j.getJSONObject("ratingErrors").put("last",ratingFailure);for(key in batchKeys){failedThisRun.add(key);j.getJSONObject("ratingErrors").put(key,ratingFailure)};save();report(id,"rating","running","同一批审核多次重试仍失败，已保留这 ${batch.size} 岗，继续下一批：$ratingFailure")}
   }
   val complete=RefreshJournal.nativeComplete(j)&&RefreshJournal.pendingRatings(j,prefs,fingerprints::get).isEmpty()&&ratingFailure==null
   j.put("fullyComplete",complete).put("state",if(complete)"complete" else "paused");flush(if(complete)"complete" else "partial")
   val problems=j.arr("entries").mapNotNull{it.s("error").takeIf{v->v.isNotBlank()}}+j.getJSONObject("failedDetails").let{errors->errors.keys().asSequence().map{errors.s(it)}.toList()}
   val missing=(q.length()-facts.length()).coerceAtLeast(0)
   val gap=if(missing>0)"尚缺 $missing 条详情" else "入口完整性尚未确认"
   val reviewFinished=reviewOnly&&RefreshJournal.pendingRatings(j,prefs,fingerprints::get).isEmpty()&&ratingFailure==null
   val message=if(reviewFinished&&!complete)"已保存的 ${facts.length()} 条详情已全部审核，待审核 0。网页抓取$gap；请用抓取按钮处理。"+(problems.firstOrNull()?.let{" 抓取记录：$it"}?:"")else if(complete)"刷新完成：${q.length()}个唯一岗位，详情和评级已保存。" else if(ratingFailure!=null)"评级暂停：$ratingFailure。已保存${facts.length()}个详情；再次刷新将从未评级岗位继续，不重抓网页。" else "已保存${facts.length()}/${q.length()}个岗位详情。"+(problems.firstOrNull()?:"尚有详情待重试")+"。再次刷新继续剩余项。"
   report(id,if(complete)"complete" else if(reviewFinished)"review_complete" else "partial",if(complete)"complete" else "partial",message)
  }catch(e:Exception){withContext(NonCancellable){j.put("state","paused").put("lastError",e.message?:"任务中断");runCatching{if(e is RefreshResourcePause)save()else flush("partial")};runCatching{report(id,"paused","partial","任务暂停，断点已保存：${e.message?:"任务中断"}。再次刷新继续，不从零开始。")}};if(e is CancellationException||e is GptQuotaExhausted)throw e}
 }
}
