package chat.plutab.job27

import kotlinx.coroutines.*

class RefreshPointFailure(message:String,cause:Throwable):java.io.IOException(message,cause)

/** Three retries after the initial attempt, always at the same checkpoint. */
suspend fun <T> retryRefreshPoint(
 retries:Int=3,
 onRetry:suspend(Int,String)->Unit={_,_->},
 wait:suspend(Long)->Unit={delay(it)},
 block:suspend()->T
):T {
 var attempt=0
 while(true){
  val network=currentCoroutineContext()[RefreshNetworkGate]
  network?.awaitOnline()
  try{return block()}catch(e:Exception){
   if(e is RefreshPointFailure||e is GptQuotaExhausted)throw e
   if(e is CancellationException&&(e !is TimeoutCancellationException||!currentCoroutineContext().isActive))throw e
   if(network?.offline()==true){network.awaitOnline();continue}
   val limit=if(RefreshFailures.temporary(e))maxOf(retries,5)else retries
   if(e is RefreshHttpFailure&&e.status in listOf(400,401,403,404))throw RefreshPointFailure(e.message?:"网站拒绝请求",e)
   if(attempt>=limit)throw RefreshPointFailure((if(e is TimeoutCancellationException)"读取超时" else e.message?:"读取失败")+"（同一点已重试${limit}次）",e)
   attempt++
   onRetry(attempt,e.message?:"读取失败")
   wait(RefreshFailures.delay(attempt,e))
  }
 }
 error("Unreachable")
}

/** A local step timeout is a recoverable failure, not cancellation of the refresh service. */
suspend fun <T> boundedRefreshStep(millis:Long,block:suspend()->T):T = try{withTimeout(millis){block()}}catch(e:TimeoutCancellationException){
 if(!currentCoroutineContext().isActive)throw e
 throw java.io.IOException("分页恢复超过${millis/1000}秒，断点已保留",e)
}
