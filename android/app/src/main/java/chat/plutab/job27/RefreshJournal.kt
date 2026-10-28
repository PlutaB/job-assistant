package chat.plutab.job27

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID

/** A durable work journal. Cursor, queue, JD and ratings commit before advancing. */
class RefreshJournal(context:Context,companyId:String,private val published:((JSONObject)->Unit)?=null){
 private val file=AtomicFile(File(context.filesDir,"refresh-journal/${companyId.replace(Regex("[^a-zA-Z0-9_-]"),"_")}.json"))
 private val lock=file.baseFile.absolutePath.intern()
 private val factDirectory=File(file.baseFile.parentFile,file.baseFile.nameWithoutExtension+".facts")
 private var persistedKeys=emptySet<String>()
 private val bodies=JobBodyStore(context)
 internal var writtenBytes=0L;private set
 private fun write(target:AtomicFile,value:JSONObject){writtenBytes+=writeAtomicJson(target,value)}
 private fun factFile(key:String)=AtomicFile(File(factDirectory,PhoneJobs.textHash(key)+".json"))
 fun exists()=file.baseFile.exists()||File(file.baseFile.path+".bak").exists()
 fun peekState():String?=synchronized(lock){runCatching{
  file.openRead().reader(Charsets.UTF_8).use{input->android.util.JsonReader(input).use{reader->
   reader.beginObject();var state:String?=null
   while(reader.hasNext()){if(reader.nextName()=="state"){state=reader.nextString();break}else reader.skipValue()};state
  }}
 }.getOrNull()}
 /** Reopen only the exact obsolete, empty Ajinga result; do not load another company's JD bodies. */
 fun reopenObsoleteZero():JSONObject?=synchronized(lock){
  val summary=issueSnapshot()?:return@synchronized null
  if(summary.optInt("queueCount",-1)!=0||summary.optInt("savedCount",-1)!=0)return@synchronized null
  fun target(e:JSONObject)=runCatching{val u=URI(e.s("url"));u.scheme=="https"&&u.host=="www.ajinga.com"&&u.path.trimEnd('/')=="/recruiting/company/12666/job-list"&&u.rawUserInfo==null&&u.port in listOf(-1,443)&&u.rawQuery==null&&u.rawFragment.isNullOrBlank()}.getOrDefault(false)
  if(summary.arr("entries").size!=1||!target(summary.arr("entries").first()))return@synchronized null
  val j=metadata()?:return@synchronized null;val entry=j.arr("entries").single()
  if(j.optBoolean("adapterUpgradePending")&&j.s("state")=="partial")return@synchronized json("taskId" to j.s("taskId"),"reason" to j.s("lastError"))
  if(!entry.optBoolean("done")||entry.isNull("total")||entry.optInt("total",-1)!=0||entry.optInt("publicApiVersion")>=1||j.strings("factKeys").isNotEmpty()||(j.optJSONObject("facts")?.length()?:0)>0)return@synchronized null
  val reason="招聘读取方式已更新，旧版零岗位结果需要重新核实"
  entry.put("done",false).put("previousTotal",0);entry.remove("total")
  j.put("state","partial").put("listingComplete",false).put("fullyComplete",false).put("adapterUpgradePending",true).put("adapterUpgradeAt",Instant.now().toString()).put("lastError",reason)
  val counts=j.optJSONObject("viewCounts")?:json("total" to 0,"savedDetails" to 0,"rated" to 0)
  j.put("viewCounts",counts.put("listingComplete",false).put("captureComplete",false))
  write(file,j)
  json("taskId" to j.s("taskId"),"reason" to reason)
 }
 fun pauseIfActive(reason:String):String?=synchronized(lock){
  if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
  val metadata=JSONObject(String(file.readFully(),Charsets.UTF_8))
  if(metadata.s("state") !in listOf("queued","running"))return@synchronized null
  metadata.put("state","paused").put("lastError",reason);write(file,metadata);metadata.s("taskId")
 }
 fun metadata():JSONObject?=synchronized(lock){if(!exists())null else JSONObject(String(file.readFully(),Charsets.UTF_8))}
 /** Read the current view without loading descriptions, including legacy flat journals. */
 fun viewMetadata():JSONObject?=synchronized(lock){if(!exists())return@synchronized null
  file.openRead().reader(Charsets.UTF_8).use{input->android.util.JsonReader(input).use{r->
   val result=JSONObject();val queueKeys=mutableSetOf<String>();val factKeys=mutableSetOf<String>();val oldFacts=mutableSetOf<String>();var listing=false
   val entryProblems=mutableListOf<JSONObject>();val detailProblems=linkedMapOf<String,String>()
   fun fields():JSONObject{val value=JSONObject();r.beginObject();while(r.hasNext()){val name=r.nextName();if(name !in setOf("url","error","done","kind","source","title","reason")){r.skipValue();continue};when(r.peek()){
    android.util.JsonToken.STRING->value.put(name,r.nextString())
    android.util.JsonToken.BOOLEAN->value.put(name,r.nextBoolean())
    else->r.skipValue()
   }};r.endObject();return value}
   fun keys(target:MutableSet<String>){r.beginObject();while(r.hasNext()){target.add(r.nextName());r.skipValue()};r.endObject()}
   r.beginObject();while(r.hasNext()){when(val name=r.nextName()){
    "taskId","companyId","createdAt","state"->result.put(name,r.nextString())
    "fullyComplete","capturePaused","reviewPaused","adapterUpgradePending"->result.put(name,r.nextBoolean())
    "listingComplete"->listing=r.nextBoolean()
    "queue"->keys(queueKeys)
    "facts"->keys(oldFacts)
    "factKeys"->{r.beginArray();while(r.hasNext())factKeys.add(r.nextString());r.endArray()}
    "entries"->{r.beginArray();while(r.hasNext()){val e=fields();if(e.s("kind")!="reference"&&!e.optBoolean("done")&&e.s("error").isNotBlank())entryProblems.add(json("source" to e.s("url"),"reason" to e.s("error"),"kind" to "listing"))};r.endArray()}
    "failedDetails"->{r.beginObject();while(r.hasNext()){val key=r.nextName();if(r.peek()==android.util.JsonToken.STRING)detailProblems[key]=r.nextString()else r.skipValue()};r.endObject()}
    "viewCounts"->{val c=JSONObject();r.beginObject();while(r.hasNext()){val k=r.nextName();when(r.peek()){
     android.util.JsonToken.BOOLEAN->c.put(k,r.nextBoolean())
     android.util.JsonToken.NUMBER->c.put(k,r.nextInt())
     android.util.JsonToken.BEGIN_ARRAY->if(k=="problems"){val rows=JSONArray();r.beginArray();while(r.hasNext())rows.put(fields());r.endArray();c.put(k,rows)}else r.skipValue()
     else->r.skipValue()
    }};r.endObject();result.put("viewCounts",c)}
    else->r.skipValue()
   }};r.endObject()
   if(!result.has("viewCounts")){val saved=if(factKeys.isNotEmpty())factKeys else oldFacts
    result.put("viewCounts",json("total" to queueKeys.size,"savedDetails" to saved.size,"rated" to if(result.optBoolean("fullyComplete"))saved.size else null,"matched" to null,"listingComplete" to listing,"captureComplete" to (listing&&queueKeys.all{it in saved})))
   }
   val c=result.getJSONObject("viewCounts");if(!c.has("problems")){
    val saved=if(factKeys.isNotEmpty())factKeys else oldFacts
    val problems=entryProblems+detailProblems.filterKeys{it !in saved}.map{(key,reason)->json("source" to key,"reason" to reason,"kind" to "detail")}
    c.put("problems",JSONArray(problems.take(8))).put("problemCount",problems.size)
   }
   result
  }}
 }
 /** Stream only capture errors and link labels, even from legacy journals containing full JDs. */
 fun captureDiagnostic():String?=synchronized(lock){
  if(!exists())return@synchronized null
  val failures=linkedMapOf<String,String>();val labels=mutableMapOf<String,String>();val entries=mutableListOf<String>()
  var company="";var point=""
  file.openRead().reader(Charsets.UTF_8).use{input->android.util.JsonReader(input).use{r->
   fun fields():Map<String,String>{val result=mutableMapOf<String,String>();r.beginObject();while(r.hasNext()){val k=r.nextName();if(k in listOf("url","text","title","error")&&r.peek()==android.util.JsonToken.STRING)result[k]=r.nextString()else r.skipValue()};r.endObject();return result}
   r.beginObject();while(r.hasNext())when(r.nextName()){
    "companyId"->company=r.nextString()
    "currentDetail"->{val f=fields();point=f["url"].orEmpty()}
    "failedDetails"->{r.beginObject();while(r.hasNext()){val k=r.nextName();if(r.peek()==android.util.JsonToken.STRING)failures[k]=r.nextString()else r.skipValue()};r.endObject()}
    "queue"->{r.beginObject();while(r.hasNext()){val key=r.nextName();val f=fields();labels[key]=listOf(f["text"]?:f["title"].orEmpty(),f["url"].orEmpty()).filter{it.isNotBlank()}.joinToString("\n")};r.endObject()}
    "entries"->{r.beginArray();while(r.hasNext()){val f=fields();if(!f["error"].isNullOrBlank())entries.add(f["url"].orEmpty()+"\n"+f["error"])};r.endArray()}
    else->r.skipValue()
   };r.endObject()
  }}
  if(failures.isEmpty()&&entries.isEmpty())return@synchronized null
  buildString{append("公司ID：$company\n");for(e in entries)append("入口：$e\n");if(point.isNotBlank())append("最近详情：$point\n");for((key,error)in failures)append("失败岗位：${labels[key]?:key}\n原因：$error\n")}
 }
 /** Allowlisted diagnostic metadata only. Never materialize JD bodies or profile data. */
 fun issueSnapshot():JSONObject?=synchronized(lock){
  if(!exists())return@synchronized null
  val result=JSONObject();val queue=JSONObject();val saved=mutableSetOf<String>()
  file.openRead().reader(Charsets.UTF_8).use{input->android.util.JsonReader(input).use{r->
   fun fields(allowed:Set<String>):JSONObject{val out=JSONObject();r.beginObject();while(r.hasNext()){
    val k=r.nextName();if(k !in allowed){r.skipValue();continue}
    when(r.peek()){
     android.util.JsonToken.STRING->out.put(k,r.nextString())
     android.util.JsonToken.BOOLEAN->out.put(k,r.nextBoolean())
     android.util.JsonToken.NUMBER->out.put(k,r.nextString().toLongOrNull()?:0)
     android.util.JsonToken.NULL->{r.nextNull();out.put(k,JSONObject.NULL)}
     else->r.skipValue()
    }
   };r.endObject();return out}
   fun errors():JSONObject{val out=JSONObject();r.beginObject();while(r.hasNext()){val k=r.nextName();if(r.peek()==android.util.JsonToken.STRING)out.put(k,r.nextString())else r.skipValue()};r.endObject();return out}
   r.beginObject();while(r.hasNext())when(val k=r.nextName()){
    "companyId","taskId","state","createdAt","lastError"->if(r.peek()==android.util.JsonToken.STRING)result.put(k,r.nextString())else r.skipValue()
    "listingComplete","fullyComplete","capturePaused","reviewPaused"->if(r.peek()==android.util.JsonToken.BOOLEAN)result.put(k,r.nextBoolean())else r.skipValue()
    "viewCounts"->result.put(k,fields(setOf("pagesViewed","total","savedDetails","rated","matched","listingComplete","captureComplete","entryFailures","failedDetails")))
    "currentDetail"->result.put(k,fields(setOf("url","text","title","index")))
    "excludedNavigation"->{var count=0;r.beginObject();while(r.hasNext()){r.nextName();r.skipValue();count++};r.endObject();result.put("excludedNavigationCount",count)}
    "failedDetails","ratingErrors"->result.put(k,errors())
    "queue"->{r.beginObject();while(r.hasNext()){val key=r.nextName();queue.put(key,fields(setOf("url","text","title","officialId")))};r.endObject()}
    "factKeys"->{r.beginArray();while(r.hasNext()){if(r.peek()==android.util.JsonToken.STRING)saved.add(r.nextString())else r.skipValue()};r.endArray()}
    "facts"->{r.beginObject();while(r.hasNext()){saved.add(r.nextName());r.skipValue()};r.endObject()}
    "entries"->{val entries=JSONArray();r.beginArray();while(r.hasNext())entries.put(fields(setOf("url","effectiveListingUrl","error","retryableError","done","kind","total","nextPage","lastReadPage","workdayOffset","resumePage","resumeUrl","domAdapterVersion","reportedInternCount")));r.endArray();result.put(k,entries)}
    else->r.skipValue()
   };r.endObject()
  }}
  val failed=result.optJSONObject("failedDetails")?:JSONObject();val missing=JSONArray()
  for(key in queue.keys())if(key !in saved){val row=queue.getJSONObject(key);missing.put(json("url" to row.s("url",key),"title" to row.s("text",row.s("title")),"error" to failed.s(key).ifBlank{"尚未取得详情；没有已保存的错误原因"}))}
  result.put("missingDetails",missing).put("queueCount",queue.length()).put("savedCount",saved.count{queue.has(it)})
  // Do not expose arbitrary map keys or stale failures that no longer belong to the queue.
  result.remove("failedDetails");result
 }
 /** Quarantined facts retain their original atomic files; historical jobs are not deleted. */
 fun archivedFact(key:String):JSONObject?=synchronized(lock){runCatching{bodies.hydrate(JSONObject(String(factFile(key).readFully(),Charsets.UTF_8)))}.getOrNull()}
 @Suppress("UNUSED_PARAMETER") // Kept for callers; immutable bodies now share content-addressed files.
 fun load(sharedJobs:List<JSONObject> = emptyList()):JSONObject?=synchronized(lock){
  if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@synchronized null
  val value=bodies.readJournal(file.openRead().bufferedReader(Charsets.UTF_8))
  if(value.optInt("factStorageVersion")>=2){
   val all=JSONObject();for(k in value.strings("factKeys"))all.put(k,bodies.defer(JSONObject(String(factFile(k).readFully(),Charsets.UTF_8))))
   value.put("facts",all);persistedKeys=value.strings("factKeys").toSet()
  }
  value
 }
 fun save(value:JSONObject){synchronized(lock){
  if(value.optBoolean("adapterUpgradePending")&&nativeComplete(value))value.remove("adapterUpgradePending")
  val all=facts(value);val keys=all.keys().asSequence().toSet()
  val dirty=synchronized(tracked){tracked[value]?.toSet()}
  val changed=if(dirty==null)keys else (dirty+(keys-persistedKeys)).intersect(keys)
  // Write changed facts first; publish their index only after every atomic fact write succeeds.
  for(k in changed){val row=all.getJSONObject(k);val compact=bodies.compactJob(row);write(factFile(k),compact);bodies.defer(row,compact)}
  var rated=0;var matched=0
  for(k in keys)if(all.getJSONObject(k).optBoolean("ratingReceived"))rated++
  val retained=value.strings("retainedMatches").toSet();val manual=value.optJSONObject("manualGrades")
  for(k in queue(value).keys()){
   val f=all.optJSONObject(k)
   val grade=if(manual?.has(k)==true)manual.s(k)else if(f?.optBoolean("ratingReceived")==true)f.s("grade")else if(k in retained)"S"else f?.s("grade").orEmpty()
   if(grade in listOf("S","A","B"))matched++
  }
  val pages=value.arr("entries").filter{it.s("kind")!="reference"}.sumOf{e->
   if(e.has("workdayOffset"))(e.optInt("workdayOffset")+19)/20 else if(e.has("lastReadPage"))e.optInt("lastReadPage") else (e.optInt("nextPage",1)-(if(e.optBoolean("done"))0 else 1)).coerceAtLeast(0)
  }
  val counts=json("pagesViewed" to pages,"total" to queue(value).length(),"savedDetails" to all.length(),"rated" to rated,"matched" to matched,"listingComplete" to value.optBoolean("listingComplete"),"captureComplete" to nativeComplete(value),"entryFailures" to value.arr("entries").count{it.s("kind")!="reference"&&!it.optBoolean("done")&&it.s("error").isNotBlank()},"failedDetails" to value.getJSONObject("failedDetails").keys().asSequence().count{!all.has(it)})
  val problems=value.arr("entries").filter{it.s("kind")!="reference"&&!it.optBoolean("done")&&it.s("error").isNotBlank()}.map{json("source" to it.s("url"),"reason" to it.s("error"),"kind" to "listing")}+
   value.getJSONObject("failedDetails").keys().asSequence().filter{!all.has(it)}.map{key->json("source" to queue(value).optJSONObject(key)?.s("url",key),"title" to queue(value).optJSONObject(key)?.s("text"),"reason" to value.getJSONObject("failedDetails").s(key),"kind" to "detail")}.toList()
  counts.put("problems",JSONArray(problems.take(8))).put("problemCount",problems.size)
  value.put("viewCounts",counts)
  val metadata=RefreshEfficiency.shallow(value).put("facts",JSONObject()).put("factStorageVersion",3).put("factKeys",JSONArray(keys))
  write(file,metadata);persistedKeys=keys
  published?.invoke(json("taskId" to value.s("taskId"),"companyId" to value.s("companyId"),"createdAt" to value.s("createdAt"),"state" to value.s("state"),"fullyComplete" to value.optBoolean("fullyComplete"),"capturePaused" to value.optBoolean("capturePaused"),"reviewPaused" to value.optBoolean("reviewPaused"),"viewCounts" to counts))
  synchronized(tracked){tracked[value]?.removeAll(changed)}
 }}
 companion object{
  private val tracked=java.util.WeakHashMap<JSONObject,MutableSet<String>>()
  fun trackFacts(j:JSONObject,initialDirty:Boolean=true){synchronized(tracked){tracked[j]=if(initialDirty)facts(j).keys().asSequence().toMutableSet()else mutableSetOf()}}
  fun markFact(j:JSONObject,key:String){synchronized(tracked){tracked[j]?.add(key)}}

  fun key(url:String):String{RecruitmentListingUrl.jobIdentity(url)?.let{return it};val uri=URI(url);val id=Regex("#/job/([^/?#]+)").find(url)?.groupValues?.get(1);return if(id!=null)uri.host+"#job:"+id else PhoneJobs.canonical(url)}
  fun entries(company:JSONObject):List<String>{val result=linkedSetOf<String>();for(link in company.arr("links")){val raw=link.s("url");val u=runCatching{URI(raw)}.getOrNull()?:continue;if(u.host=="mp.weixin.qq.com")continue
   if(u.host=="app.mokahr.com"&&Regex("/(?:campus-recruitment|social-recruitment|campus_apply|apply)/[^/]+/\\d+").containsMatchIn(u.path))result.add("https://${u.host}${u.path.trimEnd('/')}?locale=zh-CN#/jobs")else result.add(raw)
  };return result.toList()}
  fun new(company:JSONObject)=json("version" to 1,"taskId" to UUID.randomUUID().toString(),"companyId" to company.s("id"),"createdAt" to Instant.now().toString(),"state" to "queued","entries" to JSONArray(entries(company).map{json("url" to it,"nextPage" to 1,"done" to false,"keys" to JSONArray(),"total" to JSONObject.NULL)}),"queue" to JSONObject(),"facts" to JSONObject(),"failedDetails" to JSONObject(),"ratingErrors" to JSONObject(),"detailsDone" to false,"listingComplete" to false,"fullyComplete" to false)
  fun queue(j:JSONObject)=j.getJSONObject("queue")
  fun facts(j:JSONObject)=j.getJSONObject("facts")
  fun objects(value:JSONObject)=value.keys().asSequence().map{value.getJSONObject(it)}.toList()
  fun indexedCaptureComplete(j:JSONObject):Boolean{val entries=j.arr("entries").filter{it.s("kind")!="reference"};val saved=if(j.has("factKeys"))j.strings("factKeys").toSet()else facts(j).keys().asSequence().toSet();return entries.isNotEmpty()&&entries.all{it.optBoolean("done")&&!it.isNull("total")&&it.optInt("total",-1)==it.strings("keys").distinct().size}&&queue(j).keys().asSequence().all{it in saved}}
  fun nativeComplete(j:JSONObject):Boolean{val entries=j.arr("entries").filter{it.s("kind")!="reference"};if(entries.isEmpty()||entries.any{!it.optBoolean("done")||it.isNull("total")||it.optInt("total",-1)!=it.strings("keys").distinct().size})return false;val facts=facts(j);return queue(j).keys().asSequence().all{facts.has(it)}}
  fun resetRatings(j:JSONObject,profile:JSONObject,fingerprint:(JSONObject)->String={PhoneJobs.fingerprint(it,profile)}){for(f in objects(facts(j))){if(f.s("ratingFingerprint")!=fingerprint(f)){f.remove("ratingReceived");f.remove("ratingFingerprint");f.put("grade",JSONObject.NULL).put("reason","等待按最新JD和偏好评级");markFact(j,key(f.s("url")))}}}
  fun pendingRatings(j:JSONObject,profile:JSONObject,fingerprint:(JSONObject)->String={PhoneJobs.fingerprint(it,profile)})=queue(j).keys().asSequence().mapNotNull{facts(j).optJSONObject(it)}.toList().filter{!it.optBoolean("ratingReceived")||it.s("ratingFingerprint")!=fingerprint(it)}
  fun finishListing(j:JSONObject){
   val entries=j.arr("entries");val required=entries.filter{it.s("kind")!="reference"};val complete=required.isNotEmpty()&&required.all{it.optBoolean("done")};j.put("listingComplete",complete)
   if(complete){val active=entries.flatMap{it.strings("keys")}.toSet();val q=queue(j);for(key in q.keys().asSequence().toList())if(key !in active){q.remove(key);facts(j).remove(key);j.getJSONObject("failedDetails").remove(key)}}
  }
  fun total(j:JSONObject)=queue(j).length()
  fun addPage(j:JSONObject,entry:JSONObject,rows:List<JSONObject>,total:Int?,hasNext:Boolean){val keys=entry.strings("keys").toMutableSet();val q=queue(j);for(row in rows){val k=key(row.s("url"));keys.add(k);val saved=q.optJSONObject(k)?:RefreshEfficiency.shallow(row);saved.remove("description");saved.put("sources",JSONArray((saved.strings("sources")+entry.s("url")).distinct()));q.put(k,saved)}
   val previous=entry.optInt("total",-1);if(previous>=0&&total!=null&&previous!=total){entry.put("total",total).put("keys",JSONArray()).put("nextPage",1).put("done",false);for(field in listOf("nextUrl","resumeUrl","resumePage","lastReadPage"))entry.remove(field);error("列表总数校验变化：原 $previous，本页 $total，已收集 ${keys.size} 个唯一链接；已保留详情，待重新核对列表")}
   if(total!=null)entry.put("total",total);entry.put("keys",JSONArray(keys));val expected=entry.optInt("total",-1)
   if(expected>=0&&keys.size==expected){entry.put("done",true);entry.remove("error")}
   else if(hasNext){entry.put("nextPage",entry.optInt("nextPage",1)+1);entry.remove("error")}
   else entry.put("error",if(expected>=0)"官网${expected}岗，仅取得${keys.size}个唯一链接，分页尚未核齐" else "未取得官网总数，保留已读取结果")
  }
 }
}
