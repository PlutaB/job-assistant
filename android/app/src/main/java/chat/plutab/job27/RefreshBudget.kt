package chat.plutab.job27

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class RefreshResourcePause(reason:String):CancellationException("自动暂停：$reason；断点已保存")
object RefreshBudgetPolicy {
 const val heapLimitPercent=85
 fun reason(level:Int,scale:Int,plugged:Boolean,thermal:Int,usedHeap:Long,maxHeap:Long):String?=when{
  thermal>=3->"系统报告严重过热，请降温后继续"
  !plugged&&scale>0&&level>=0&&level.toLong()*100<scale.toLong()*10->"电量低于10%，请充电后继续"
  maxHeap>0&&usedHeap.toDouble()/maxHeap>=heapLimitPercent/100.0->"Java内存接近运行上限，请稍后继续"
  else->null
 }
 /** Cooperative target: half of one CPU core on average, not an OS-enforced CPU cap. */
 fun delayMillis(cpu:Long,elapsed:Long)=if(elapsed<500)0L else (cpu*2-elapsed).coerceIn(0L,1500L)
 fun currentReason(context:Context,checkHeap:Boolean=true):String?{
  val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val power=context.getSystemService(PowerManager::class.java)
  val thermal=if(Build.VERSION.SDK_INT>=29)power.currentThermalStatus else 0
  val runtime=Runtime.getRuntime()
  return reason(battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1,battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1,
   (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0,thermal,if(checkHeap)runtime.totalMemory()-runtime.freeMemory()else 0,runtime.maxMemory())
 }
}
class RefreshBudget(private val context:Context){
 private val memory=MemoryPressure()
 suspend fun prepareStart():String?=RefreshBudgetPolicy.currentReason(context,checkHeap=false)?:memory.reason(forceCleanup=true)
 suspend fun safetyReason():String?=RefreshBudgetPolicy.currentReason(context,checkHeap=false)?:memory.reason()
 private var checkedAt=-1000L;private var pauseReason:String?=null
 private var started=android.os.SystemClock.elapsedRealtime();private var cpu=Process.getElapsedCpuTime()
 fun reset(){started=android.os.SystemClock.elapsedRealtime();cpu=Process.getElapsedCpuTime();checkedAt=-1000L;pauseReason=null}
 suspend fun checkpoint(checkSafety:Boolean=true){
  if(checkSafety){val now=android.os.SystemClock.elapsedRealtime();if(now-checkedAt>=1000){pauseReason=safetyReason();checkedAt=now};pauseReason?.let{throw RefreshResourcePause(it)}}
  val elapsed=android.os.SystemClock.elapsedRealtime()-started
  if(elapsed>=10000){reset();return}
  val pause=RefreshBudgetPolicy.delayMillis(Process.getElapsedCpuTime()-cpu,elapsed)
  if(pause>0)delay(pause)
 }
}
