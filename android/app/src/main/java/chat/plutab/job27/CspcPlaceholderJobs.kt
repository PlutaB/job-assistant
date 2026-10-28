package chat.plutab.job27
import org.json.*

/** User explicitly requested removal of CSPC jobs whose official detail is just 1 / 1. */
internal object CspcPlaceholderJobs {
 fun confirmed(reason:String)=reason.startsWith("官网职责和任职要求均为占位符“1”")
 fun remove(j:JSONObject,rows:List<JSONObject>,confirmedIds:Set<String>):Int {
  val excluded=j.optJSONObject("excludedCspcPlaceholders")?:JSONObject().also{j.put("excludedCspcPlaceholders",it)}
  var removed=0
  for(row in rows){
   val key=RefreshJournal.key(row.s("url"))
   // A previously saved full JD is evidence worth retaining, even if the source later degrades.
   if(row.s("officialId") !in confirmedIds||RefreshJournal.facts(j).has(key))continue
   excluded.put(key,json("officialId" to row.s("officialId"),"title" to row.s("title"),"url" to row.s("url"),"reason" to "用户要求移除官网正文仅为1的岗位","at" to java.time.Instant.now().toString()))
   for(e in j.arr("entries")){
    val old=e.strings("keys");val kept=old.filter{it!=key}
    if(old.size!=kept.size){
     if(!e.has("sourceTotal")&&!e.isNull("total"))e.put("sourceTotal",e.optInt("total"))
     e.put("keys",JSONArray(kept));if(!e.isNull("total"))e.put("total",maxOf(0,e.optInt("total")-(old.size-kept.size)))
    }
   }
   if(RefreshJournal.queue(j).has(key)){RefreshJournal.queue(j).remove(key);removed++}
   for(field in listOf("failedDetails","retryableDetails","detailFetchCount","ratingErrors"))j.optJSONObject(field)?.remove(key)
  }
  return removed
 }
}
