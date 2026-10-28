package chat.plutab.job27

import org.json.JSONObject

/** Run-scoped: rating changes do not rebuild every complete JD. Fact edits still invalidate. */
class RatingFingerprintCache(private val profile:JSONObject){
 private data class Entry(val fields:List<String>,val hash:String)
 private val entries=java.util.IdentityHashMap<JSONObject,Entry>()
 fun get(fact:JSONObject):String {
  val fields=listOf(fact.s("title"),JobBodyStore.hash(fact),fact.s("location"),fact.s("department"),fact.s("recruitmentType"))
  val previous=entries[fact]
  if(previous!=null&&previous.fields==fields)return previous.hash
  return PhoneJobs.fingerprint(fact,profile).also{entries[fact]=Entry(fields,it)}
 }
}
