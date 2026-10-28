package chat.plutab.job27

import org.json.JSONObject

class GptQuotaExhausted(val detail:String):java.io.IOException("GPT用量已用完，全部审核已暂停；岗位和断点已保存。"+detail.take(200))

/** Distinguish exhausted allowance from transient request-rate limiting. */
object GptQuota {
 fun failure(body:JSONObject):GptQuotaExhausted? {
  val err=body.optJSONObject("response")?.optJSONObject("error")?:body.optJSONObject("error")?:body
  val code=(err.s("code")+" "+err.s("type")).lowercase()
  val message=err.s("message",err.s("detail",body.s("detail")))
  val exhausted=listOf("insufficient_quota","usage_limit_reached","quota_exceeded","quota_exhausted","usage_limit_exceeded","plan_limit_exceeded","billing_hard_limit_reached").any{it in code} || Regex("(?i)(?:usage|plan|credit|quota).{0,50}(?:exhausted|used up|limit reached|limit exceeded)|(?:reached|exceeded|hit).{0,40}(?:usage limit|plan limit|quota)|用量.{0,15}(?:用完|耗尽|上限)|额度.{0,10}(?:用完|耗尽)").containsMatchIn(message)
  return if(exhausted)GptQuotaExhausted(message.ifBlank{code})else null
 }
}
