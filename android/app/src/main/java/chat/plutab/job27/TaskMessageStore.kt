package chat.plutab.job27
import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Append one atomic record. Legacy arrays are streamed, never loaded as a whole. */
class TaskMessageStore(context:Context){
 private val root=File(context.filesDir,"task-messages")
 private fun safe(id:String):String{require(Regex("^[a-zA-Z0-9_-]{1,100}$").matches(id));return id}
 private fun dir(task:String)=File(root,safe(task)+".records")
 fun append(task:String,message:JSONObject){
  val file=AtomicFile(File(dir(task),safe(message.s("id"))+".json"));file.baseFile.parentFile?.mkdirs()
  val out=file.startWrite();try{val writer=out.bufferedWriter(Charsets.UTF_8);streamJson(message,writer);writer.flush();file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
 }
 private fun value(r:JsonReader):Any=when(r.peek()){
  JsonToken.BEGIN_OBJECT->JSONObject().apply{r.beginObject();while(r.hasNext())put(r.nextName(),value(r));r.endObject()}
  JsonToken.BEGIN_ARRAY->JSONArray().apply{r.beginArray();while(r.hasNext())put(value(r));r.endArray()}
  JsonToken.NULL->{r.nextNull();JSONObject.NULL}
  JsonToken.BOOLEAN->r.nextBoolean()
  JsonToken.NUMBER->{val n=r.nextString();n.toLongOrNull()?:n.toDouble()}
  else->r.nextString()
 }
 private fun record(r:JsonReader,includePayload:Boolean):JSONObject=JSONObject().apply{
  r.beginObject();while(r.hasNext()){val name=r.nextName();if(name=="payload"&&!includePayload)r.skipValue()else put(name,value(r))};r.endObject()
 }
 fun summaries(task:String):List<JSONObject>{
  val result=mutableListOf<JSONObject>();val old=File(root,safe(task)+".json")
  if(old.exists())JsonReader(old.reader(Charsets.UTF_8).buffered()).use{r->r.beginArray();while(r.hasNext())result.add(record(r,false));r.endArray()}
  for(file in dir(task).listFiles().orEmpty().filter{it.extension=="json"})JsonReader(file.reader(Charsets.UTF_8).buffered()).use{result.add(record(it,false))}
  return result.distinctBy{it.s("id")}.sortedBy{it.s("at")}
 }
 fun payload(task:String,id:String):Any?{
  val file=File(dir(task),safe(id)+".json")
  if(file.exists())return JsonReader(file.reader(Charsets.UTF_8).buffered()).use{record(it,true).opt("payload")}
  val old=File(root,safe(task)+".json");if(!old.exists())return null
  JsonReader(old.reader(Charsets.UTF_8).buffered()).use{r->r.beginArray();while(r.hasNext()){
   // Load one legacy record at a time, releasing preceding payloads.
   val row=record(r,true);if(row.s("id")==id)return row.opt("payload")
  };r.endArray()};return null
 }
 fun all(task:String)=summaries(task).map{it.put("payload",payload(task,it.s("id"))?:JSONObject.NULL)}
}
