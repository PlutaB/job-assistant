package chat.plutab.job27

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HeapSample(val used:Long,val limit:Long){
 val high get()=limit>0&&used.toDouble()/limit>=0.85
 val description get()="${used/1048576}/${limit/1048576} MB"
}
/** Verify retained heap after a bounded cleanup, not one pre-GC allocation peak. */
class MemoryPressure(
 private val sample:()->HeapSample={val r=Runtime.getRuntime();HeapSample(r.totalMemory()-r.freeMemory(),r.maxMemory())},
 private val cleanup:suspend()->Unit={withContext(Dispatchers.Default){System.gc()};delay(200)},
 private val now:()->Long={System.nanoTime()/1000000}
){
 private val lock=Mutex();private var lastCleanup=Long.MIN_VALUE/2
 suspend fun reason(forceCleanup:Boolean=false):String?=lock.withLock{
  if(!sample().high)return@withLock null
  val at=now()
  if(forceCleanup||at-lastCleanup>=30000){lastCleanup=at;cleanup()}
  val confirmed=sample()
  if(!confirmed.high)return@withLock null
  "Java内存清理后仍偏高（${confirmed.description}），已暂停；请关闭详情页面或重启应用后继续"
 }
}
