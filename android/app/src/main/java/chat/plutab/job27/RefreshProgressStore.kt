package chat.plutab.job27

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Small durable progress records are separate from the large job snapshot. */
class RefreshProgressStore(context:Context){
 private val directory=File(context.filesDir,"refresh-progress")
 private val taskReader=JobBodyStore(context)
 private fun file(id:String):AtomicFile{require(Regex("^[a-zA-Z0-9_-]{1,100}$").matches(id));return AtomicFile(File(directory,"$id.json"))}
 fun saveTask(task:JSONObject)=write(file(task.s("id")),recent(task))
 fun saveBatch(batch:JSONObject)=write(file("batch"),batch)
 private fun write(target:AtomicFile,value:JSONObject){directory.mkdirs();val out=target.startWrite();try{out.write(value.toString().toByteArray(Charsets.UTF_8));target.finishWrite(out)}catch(e:Exception){target.failWrite(out);throw e}}
 private fun recent(task:JSONObject)=RefreshEfficiency.shallow(task).put("events",JSONArray(RefreshEvents.compact(task.arr("events"))))
 fun task(id:String)=read(id)?.let(::recent)
 private fun read(id:String)=runCatching{taskReader.readTask(file(id).openRead().bufferedReader(Charsets.UTF_8))}.getOrNull()
 private fun time(value:JSONObject,key:String)=runCatching{Instant.parse(value.s(key))}.getOrDefault(Instant.EPOCH)
 fun restore(data:JSONObject):JSONObject {
  val restored=mutableSetOf<String>()
  data.put("tasks",JSONArray(data.arr("tasks").map{old->val saved=read(old.s("id"));if(saved?.s("id")==old.s("id")&&time(saved,"updatedAt")>=maxOf(time(old,"updatedAt"),time(old,"snapshotSavedAt"))){restored.add(old.s("id"));recent(saved!!)}else recent(old)}))
  for(task in data.arr("tasks").distinctBy{it.s("companyId")})if(task.s("id") in restored&&task.s("state") !in listOf("queued","running")){data.arr("companies").firstOrNull{it.s("id")==task.s("companyId")}?.let{applyRefreshWarning(it,task.s("state"),task.s("message"))}}
  val batch=data.optJSONObject("refreshBatch");val saved=read("batch")
  if(batch!=null&&saved?.s("id")==batch.s("id")&&time(saved,"checkpointUpdatedAt")>=time(batch,"checkpointUpdatedAt"))data.put("refreshBatch",saved)
  return data
 }
}
