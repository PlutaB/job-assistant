package chat.plutab.job27

import org.json.JSONArray
import org.json.JSONObject

/** Copy only metadata that changes, never all job descriptions for a progress tick. */
object RefreshEfficiency {
 fun shallow(value:JSONObject)=JSONObject().apply{for(k in value.keys())put(k,value.get(k))}
 fun progress(data:JSONObject,id:String,stage:String,text:String,state:String,at:String):JSONObject {
  val next=shallow(data);val old=data.arr("tasks").first{it.s("id")==id};val task=shallow(old)
  if(stage !in listOf("loading","retry"))task.put("progressStage",stage).put("progressMessage",text)
  task.put("stage",stage).put("message",text).put("state",state).put("updatedAt",at)
  task.put("events",JSONArray(RefreshEvents.compact(old.arr("events")+json("at" to at,"stage" to stage,"message" to text))))
  if(state !in listOf("queued","running"))task.put("finishedAt",at)
  next.put("tasks",JSONArray(data.arr("tasks").map{if(it.s("id")==id)task else it}))
  if(state !in listOf("queued","running"))next.put("companies",JSONArray(data.arr("companies").map{c->if(c.s("id")==task.s("companyId"))shallow(c).apply{applyRefreshWarning(this,state,text)}else c}))
  return next
 }
}
class CaptureFlushGate(initialWork:Int,private val now:()->Long={System.nanoTime()/1000000}) {
 private var savedWork=initialWork;private var savedAt=now()
 fun due(work:Int)=work!=savedWork&&(work-savedWork>=50||now()-savedAt>=30000)
 fun committed(work:Int){savedWork=work;savedAt=now()}
}
object WebResourcePolicy {
 fun blocked(url:String):Boolean=runCatching{
  val uri=java.net.URI(url);val host=uri.host.orEmpty().lowercase()
  val media=Regex("(?i)\\.(?:png|jpe?g|gif|webp|avif|ico|svg|woff2?|ttf|otf|mp4|webm|mp3|m4a|ogg)(?:$)").containsMatchIn(uri.path.orEmpty())
  media||host=="hm.baidu.com"||host=="bat.bing.com"||host=="www.googletagmanager.com"||host.endsWith(".google-analytics.com")||host=="google-analytics.com"
 }.getOrDefault(false)
}
