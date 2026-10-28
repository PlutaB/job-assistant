package chat.plutab.job27

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

private object CompanyDocumentWrites {
 private val saved=ConcurrentHashMap<String,Pair<String,Long>>()
 fun write(file:File,content:JSONObject,writer:(AtomicFile)->Unit){
  val digest=MessageDigest.getInstance("SHA-256")
  val sink=DigestOutputStream(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},digest).bufferedWriter(Charsets.UTF_8)
  streamJson(content,sink);sink.flush()
  val hash=digest.digest().joinToString(""){"%02x".format(it)}
  val manifest=AtomicFile(File(file.path+".digest.json"))
  val previous=saved[file.absolutePath]?:runCatching{JSONObject(manifest.openRead().bufferedReader().use{it.readText()}).let{it.s("hash") to it.getLong("bytes")}}.getOrNull()
  if(file.exists()&&previous?.first==hash&&previous.second==file.length())return
  writer(AtomicFile(file))
  val record=hash to file.length()
  writeAtomicJson(manifest,json("hash" to hash,"bytes" to record.second));saved[file.absolutePath]=record
 }
}

/** Durable, per-company copies. Only replace a complete JSON file, never write in place. */
fun saveCompanyFiles(context:Context,snapshot:JSONObject,preserveExisting:Boolean=false){
 val dir=File(context.filesDir,"company-jobs").apply{mkdirs()}
 val provided=snapshot.arr("companyFiles").associateBy{it.s("companyId")}
 val jobs=snapshot.arr("jobs").groupBy{it.s("companyId")}
 val tasks=snapshot.arr("tasks")
 for(company in snapshot.arr("companies")){
  val id=company.s("id");require(id.isNotEmpty())
  val filename=if(Regex("^[a-zA-Z0-9_-]{1,100}$").matches(id))id else MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}
  if(preserveExisting&&!company.optBoolean("sourceIdentityCorrected")&&File(dir,"$filename.list.json").exists()&&File(dir,"$filename.matched.json").exists())continue
  val archived=jobs[id].orEmpty().filterNot{CompanySourceIdentity.currentJob(it)}
  val rows=jobs[id].orEmpty().filter{CompanySourceIdentity.currentJob(it)}
  val supplied=provided[id]?.takeUnless{company.optBoolean("sourceIdentityCorrected")}
  val cleanCompany=JSONObject(company.toString()).apply{put("logo","")}
  val summaries=JSONArray(rows.map{RefreshEfficiency.shallow(it).apply{remove("description")}})
  val document=supplied?.optJSONObject("list")?:json("schemaVersion" to 2,"companyId" to id,"company" to cleanCompany,
   "lastSuccessfulRefresh" to company.opt("lastSuccess"),"latestRefresh" to tasks.firstOrNull{it.s("companyId")==id&&it.s("kind")=="refresh"},
   "confirmedCounts" to company.opt("snapshot"),"appliedCount" to rows.count{it.s("appliedAt").isNotEmpty()},"jobs" to summaries,"archivedSourceJobs" to JSONArray(archived.map{RefreshEfficiency.shallow(it).apply{remove("description")}}))
  val matched=supplied?.optJSONObject("matched")?:json("schemaVersion" to 2,"companyId" to id,"company" to cleanCompany,
   "lastSuccessfulRefresh" to document.opt("lastSuccessfulRefresh"),"latestRefresh" to document.opt("latestRefresh"),
   "jobs" to JSONArray(rows.filter{it.s("grade") in listOf("S","A","B")}))
  for((kind,content) in listOf("list" to document,"matched" to matched)){

  require(content.s("companyId")==id)
  CompanyDocumentWrites.write(File(dir,"$filename.$kind.json"),content){atomic->
   if(kind=="matched")JobBodyStore(context).writeDocument(atomic,content)else writeAtomicJson(atomic,content)
  }
  }
  val legacy=File(dir,"$filename.json")
  if(legacy.exists()){val old=runCatching{JSONObject(legacy.readText())}.getOrNull();if(old?.s("companyId")==id)legacy.delete()}
 }
 val taskDir=File(context.filesDir,"task-messages").apply{mkdirs()}
 for((id,messages) in snapshot.arr("taskMessages").groupBy{it.s("taskId")}){
  if(!Regex("^[a-zA-Z0-9_-]{1,100}$").matches(id))continue
  val atomic=AtomicFile(File(taskDir,"$id.json"));writeAtomicJson(atomic,JSONArray(messages))
 }
}
