package chat.plutab.job27
import java.io.IOException
import java.net.*
import java.time.*
class RefreshHttpFailure(val status:Int,val retryAfterMillis:Long=0):IOException("网站请求失败：HTTP $status"+if(status==429&&retryAfterMillis>0)"，按网站要求等待 ${retryAfterMillis/1000} 秒"else "")
object RefreshFailures {
 fun status(e:Throwable?):Int?=if(e is RefreshHttpFailure)e.status else if(e!=null&&e.cause!==e)status(e.cause)else null
 fun temporary(e:Throwable?):Boolean=when(e){
  null->false
  is RefreshHttpFailure->e.status==408||e.status==429||e.status in 500..599
  is SocketTimeoutException,is SocketException,is UnknownHostException->true
  else->e.cause!==e&&temporary(e.cause)
 }
 fun retryAfter(value:String?,now:Long=System.currentTimeMillis()):Long{
  if(value==null)return 0
  return value.trim().toLongOrNull()?.coerceIn(0,86400)?.times(1000)?:runCatching{(ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceAtLeast(0)}.getOrDefault(0)
 }
 fun delay(attempt:Int,e:Throwable):Long=if(temporary(e))maxOf(listOf(2000L,5000,10000,20000,30000)[(attempt-1).coerceIn(0,4)],(e as? RefreshHttpFailure)?.retryAfterMillis?:0)else attempt*1500L
}
