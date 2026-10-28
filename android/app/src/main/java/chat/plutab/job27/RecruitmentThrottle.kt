package chat.plutab.job27
import java.net.URI
import org.json.JSONObject

/** A website's 429 pauses only that source for this invocation, without a per-job retry storm. */
internal class RecruitmentThrottle(private val journal:JSONObject?=null) {
 private val paused=mutableSetOf<String>()
 private fun group(url:String):String=URI(url).host.let{if(it in listOf("www.linkedin.com","cn.linkedin.com","hk.linkedin.com"))"linkedin.com"else it}
 fun paused(url:String)=group(url) in paused
 fun failure(url:String,error:Exception):Exception {
  if(RefreshFailures.status(error)!=429)return error
  paused.add(group(url))
  journal?.let{j->
   for(entry in j.arr("entries"))if(paused(entry.s("url")))entry.put("retryableError",false)
   val retryable=j.optJSONObject("retryableDetails")
   if(retryable!=null)for(key in retryable.keys().asSequence().toList()){
    val link=RefreshJournal.queue(j).optJSONObject(key)
    if(link!=null&&paused(link.s("url")))retryable.remove(key)
   }
  }
  return RefreshPointFailure("官网请求限流（HTTP 429），本轮已暂停该来源，断点已保存，稍后手动继续",error)
 }
}
