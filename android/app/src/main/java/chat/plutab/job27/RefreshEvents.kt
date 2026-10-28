package chat.plutab.job27
import org.json.JSONObject

object RefreshEvents {
 const val maxRecentEvents=100
 private val retry=Regex("自动重试\\s*(\\d+)")
 private fun retryInfo(event:JSONObject):Pair<String,Int>? {
  val text=event.s("message");val match=retry.find(text)?:return null
  return (event.s("stage")+":"+text.substring(0,match.range.first)) to match.groupValues[1].toInt()
 }
 fun concise(message:String):String {
  val rating=Regex("公司合并审核[·：]已保存\\s*(\\d+)/(\\d+)，本次\\s*(\\d+)\\s*岗").find(message)
  if(rating!=null)return "评分 ${rating.groupValues[1]}/${rating.groupValues[2]} · 本批 ${rating.groupValues[3]} 岗"
  val complete=Regex("已保存的\\s*(\\d+)\\s*条详情已全部审核").find(message)
  if(complete!=null){
   val failure=message.substringAfter("抓取记录：","").trim().trimEnd('。')
   return "评分完成 ${complete.groupValues[1]}/${complete.groupValues[1]}"+(if(failure.isNotBlank())" · 抓取失败：$failure"else "")
  }
  var text=message
  for(phrase in listOf("；仅超出容量时分批","。再次刷新继续，不从零开始。","再次刷新继续剩余项。","再次刷新继续剩余项","再次刷新继续，不从零开始。","无需从零开始","请用抓取按钮处理。","请用抓取按钮处理","断点已保存，","断点已保存。"))text=text.replace(phrase,"")
  text=text.replace("继续已保存任务：","继续任务：").replace("任务暂停，断点已保存：","已暂停：").replace("（直接读取）","")
  text=Regex("同一岗位详情自动重试\\s*(\\d+)").replace(text,"详情重试 $1 次")
  text=Regex("同一页面自动重试\\s*(\\d+)").replace(text,"页面重试 $1 次")
  text=Regex("；已保存\\s*\\d+\\s*个详情").replace(text,"")
  text=Regex("核对详情\\s*(\\d+)/(\\d+)").replace(text,"详情 $1/$2")
  return text.trim().trimEnd('，','；','。')
 }
 fun compact(events:List<JSONObject>):List<JSONObject>{
  val result=mutableListOf<JSONObject>()
  for(event in events){val next=retryInfo(event);val previous=result.lastOrNull()?.let{retryInfo(it)}
   if(next!=null&&previous!=null&&next.first==previous.first&&next.second>previous.second)result[result.lastIndex]=event
   else result.add(event)
  }
  return result.takeLast(maxRecentEvents)
 }
}
