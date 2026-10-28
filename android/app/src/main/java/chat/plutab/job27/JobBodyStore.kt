package chat.plutab.job27

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.io.Writer

/** SQLite substr counts Unicode code points; Reader buffers use UTF-16 code units. */
internal class SnapshotTextReader(private val load:(Int)->String):Reader(){
 private var next=1;private var chunk="";private var position=0;private var closed=false
 override fun read(buffer:CharArray,offset:Int,length:Int):Int{
  check(!closed);require(offset>=0&&length>=0&&offset+length<=buffer.size);if(length==0)return 0
  if(position==chunk.length){chunk=load(next);position=0;if(chunk.isEmpty())return -1;next+=chunk.codePointCount(0,chunk.length)}
  val size=minOf(length,chunk.length-position);chunk.toCharArray(buffer,offset,position,position+size);position+=size;return size
 }
 override fun close(){closed=true;chunk=""}
}

/** An immutable disk-backed description; conversion loads one body without retaining it. */
internal class DeferredDescription(val contentHash:String,private val read:()->String){override fun toString():String=read()}

/** Immutable content-addressed JDs. The list cache retains metadata, never all bodies. */
class JobBodyStore(context:Context){
 private val directory=File(context.filesDir,"job-bodies")
 private fun file(ref:String):File{require(Regex("[a-f0-9]{64}").matches(ref));return File(directory,"$ref.txt")}
 fun compactJob(row:JSONObject):JSONObject{
  val deferred=row.opt("description") as? DeferredDescription
  if(deferred!=null)return RefreshEfficiency.shallow(row).apply{remove("description");put("_bodyRef",deferred.contentHash);put("descriptionHash",deferred.contentHash)}
  if(!row.has("description"))return row
  val body=row.s("description");val copy=RefreshEfficiency.shallow(row);copy.remove("description")
  val ref=PhoneJobs.textHash(body)
  if(body.isEmpty())copy.remove("_bodyRef") else {
   val target=AtomicFile(file(ref));directory.mkdirs()
   if(!target.baseFile.exists()){
    val output=target.startWrite()
    try{val writer=output.bufferedWriter(Charsets.UTF_8);writer.write(body);writer.flush();target.finishWrite(output)}catch(e:Exception){target.failWrite(output);throw e}
   }
   copy.put("_bodyRef",ref)
  }
  return copy.put("descriptionHash",ref)
 }
 /** Keep the same fact identity, so pending batches and rating updates never target stale copies. */
 internal fun defer(row:JSONObject,stored:JSONObject=compactJob(row)):JSONObject{
  if(row.opt("description") is DeferredDescription)return row
  val ref=stored.s("_bodyRef")
  if(ref.isNotBlank())row.put("_bodyRef",ref).put("descriptionHash",ref).put("description",DeferredDescription(ref){AtomicFile(file(ref)).openRead().bufferedReader(Charsets.UTF_8).use{it.readText()}})
  return row
 }
 internal fun readJournal(input:Reader):JSONObject=JsonReader(input).use{reader->JSONObject().apply{
  reader.beginObject();while(reader.hasNext()){
   val name=reader.nextName()
   if(name in listOf("facts","queue")&&reader.peek()==JsonToken.BEGIN_OBJECT){
    val rows=JSONObject();reader.beginObject();while(reader.hasNext()){val key=reader.nextName();val row=value(reader) as JSONObject;rows.put(key,if(name=="facts")defer(row)else compactJob(row))};reader.endObject();put(name,rows)
   }else put(name,value(reader))
  };reader.endObject()
 }}
 fun compact(snapshot:JSONObject):JSONObject=RefreshEfficiency.shallow(snapshot).put("jobs",JSONArray(snapshot.arr("jobs").map(::compactJob)))
 fun body(row:JSONObject):String=if(row.has("description"))row.s("description")else row.s("_bodyRef").takeIf{it.isNotBlank()}?.let{AtomicFile(file(it)).openRead().bufferedReader(Charsets.UTF_8).use{r->r.readText()}}.orEmpty()
 fun hydrate(row:JSONObject):JSONObject=RefreshEfficiency.shallow(row).apply{if(has("_bodyRef")){put("description",body(row));remove("_bodyRef")}}
 private fun value(reader:JsonReader):Any=when(reader.peek()){
  JsonToken.BEGIN_OBJECT->JSONObject().apply{reader.beginObject();while(reader.hasNext()){val name=reader.nextName();if(name=="jobs"&&reader.peek()==JsonToken.BEGIN_ARRAY){val jobs=JSONArray();reader.beginArray();while(reader.hasNext()){val row=value(reader);jobs.put(if(row is JSONObject)compactJob(row)else row)};reader.endArray();put(name,jobs)}else put(name,value(reader))};reader.endObject()}
  JsonToken.BEGIN_ARRAY->JSONArray().apply{reader.beginArray();while(reader.hasNext())put(value(reader));reader.endArray()}
  JsonToken.NULL->{reader.nextNull();JSONObject.NULL}
  JsonToken.BOOLEAN->reader.nextBoolean()
  JsonToken.NUMBER->reader.nextString().let{it.toLongOrNull()?:it.toDouble()}
  else->reader.nextString()
 }
 /** Discard old progress while reading, rather than allocating the entire historic log first. */
 private fun readTask(reader:JsonReader):JSONObject=JSONObject().apply{
  reader.beginObject();while(reader.hasNext()){
   val name=reader.nextName()
   if(name=="events"&&reader.peek()==JsonToken.BEGIN_ARRAY){
    val recent=java.util.ArrayDeque<JSONObject>();reader.beginArray()
    while(reader.hasNext()){val event=value(reader);if(event is JSONObject){if(recent.size==RefreshEvents.maxRecentEvents)recent.removeFirst();recent.addLast(event)}}
    reader.endArray();put(name,JSONArray(RefreshEvents.compact(recent.toList())))
   }else put(name,value(reader))
  };reader.endObject()
 }
 internal fun readTask(input:Reader):JSONObject=JsonReader(input).use{readTask(it)}
 /** Also migrates legacy large cache files one job at a time. */
 fun readSnapshot(input:Reader):JSONObject=JsonReader(input).use{reader->JSONObject().apply{
  reader.beginObject();while(reader.hasNext()){
   val name=reader.nextName()
   if(name=="jobs"){
    val jobs=JSONArray();reader.beginArray();while(reader.hasNext())jobs.put(compactJob(value(reader) as JSONObject));reader.endArray();put(name,jobs)
   }else if(name=="tasks"&&reader.peek()==JsonToken.BEGIN_ARRAY){
    val tasks=JSONArray();reader.beginArray();while(reader.hasNext())tasks.put(readTask(reader));reader.endArray();put(name,tasks)
   }else put(name,value(reader))
  };reader.endObject()
 }}
 /** Self-contained exported/companion documents, resolving only one body at a time. */
 fun stream(value:Any?,writer:Writer){when(value){
  is JSONObject->{val row=if(value.has("_bodyRef"))hydrate(value)else value;writer.write("{");var first=true;for(key in row.keys()){if(!first)writer.write(",");first=false;streamJson(key,writer);writer.write(":");stream(row.opt(key),writer)};writer.write("}")}
  is JSONArray->{writer.write("[");for(i in 0 until value.length()){if(i>0)writer.write(",");stream(value.opt(i),writer)};writer.write("]")}
  else->streamJson(value,writer)
 }}
 fun writeDocument(target:AtomicFile,value:JSONObject){val output=target.startWrite();try{val writer=output.bufferedWriter(Charsets.UTF_8);stream(value,writer);writer.flush();target.finishWrite(output)}catch(e:Exception){target.failWrite(output);throw e}}
 companion object{fun hash(row:JSONObject)=(row.opt("description") as? DeferredDescription)?.contentHash?:if(row.has("description"))PhoneJobs.textHash(row.s("description"))else row.s("descriptionHash",PhoneJobs.textHash(""))}
}
