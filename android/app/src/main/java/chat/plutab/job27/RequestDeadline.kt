package chat.plutab.job27

import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Socket inactivity timeouts do not bound an endless stream of keepalives. */
internal class RequestDeadline(connection:HttpURLConnection,millis:Long):java.io.Closeable {
 private val future=timer.schedule({connection.disconnect()},millis,TimeUnit.MILLISECONDS)
 override fun close(){future.cancel(false)}
 companion object {private val timer=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"job27-request-deadline").apply{isDaemon=true}}}
}
