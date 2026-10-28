package chat.plutab.job27
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

object RuntimeDiagnostics {
 fun install(context:Context){
  val previous=Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler{thread,error->
   try{val f=File(context.filesDir,"diagnostics/last-crash.txt");f.parentFile?.mkdirs();f.bufferedWriter().use{w->
    w.write("v${BuildConfig.VERSION_NAME}\n${java.time.Instant.now()}\n${error.javaClass.name}\n${error.message?.take(500)}\n")
    error.stackTrace.take(24).forEach{w.write(it.toString());w.newLine()}
   }}catch(_:Throwable){}
   previous?.uncaughtException(thread,error)
  }
 }
 fun read(context:Context):String=buildString{
  val r=Runtime.getRuntime();append("版本 v${BuildConfig.VERSION_NAME}\nJava内存 ${ (r.totalMemory()-r.freeMemory())/1048576}/${r.maxMemory()/1048576} MB\n")
  val f=File(context.filesDir,"diagnostics/last-crash.txt")
  append("\n最近记录的异常：\n");append(if(f.exists())f.readText().take(5000)else "暂无本地异常记录")
  append("\n\n抓取失败记录（不含岗位正文及个人资料）：\n")
  val journals=File(context.filesDir,"refresh-journal").listFiles().orEmpty().filter{it.extension=="json"}.sortedBy{it.name}
  var found=false
  for(file in journals)runCatching{RefreshJournal(context,file.nameWithoutExtension).captureDiagnostic()}.getOrNull()?.let{append(it);append("\n");found=true}
  if(!found)append("暂无已保存的抓取失败记录\n")
  if(Build.VERSION.SDK_INT>=30){append("\n\n系统进程退出记录：\n")
   runCatching{context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName,0,3).forEach{
    append(java.time.Instant.ofEpochMilli(it.timestamp));append(" · ");append(when(it.reason){android.app.ApplicationExitInfo.REASON_LOW_MEMORY->"系统内存不足";android.app.ApplicationExitInfo.REASON_CRASH->"程序异常";android.app.ApplicationExitInfo.REASON_ANR->"无响应";android.app.ApplicationExitInfo.REASON_USER_REQUESTED->"用户或系统停止";else->"原因代码 ${it.reason}"});append("\n");append(it.description.orEmpty().take(300));append("\n")
   }}.onFailure{append("系统未提供退出记录")}
  }
 }
}
