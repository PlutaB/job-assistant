package chat.plutab.job27

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancelling refresh closes a blocked socket instead of waiting for its read timeout. */
suspend fun <T> cancellableHttp(block:suspend((HttpURLConnection)->Unit)->T):T=coroutineScope {
 currentCoroutineContext()[RefreshNetworkGate]?.awaitOnline()
 val owner=this
 suspendCancellableCoroutine{continuation->
  val connection=AtomicReference<HttpURLConnection?>()
  val worker=owner.launch(Dispatchers.IO){
   try{
    val value=block{c->connection.set(c);if(!continuation.isActive){c.disconnect();throw CancellationException("请求已停止")}}
    if(continuation.isActive)continuation.resume(value)
   }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
  }
  continuation.invokeOnCancellation{connection.get()?.disconnect();worker.cancel()}
 }
}
