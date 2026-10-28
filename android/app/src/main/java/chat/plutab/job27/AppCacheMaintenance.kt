package chat.plutab.job27

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

/** Only disposable OS cache files. Never touch filesDir, databases, cookies or local storage. */
object AppCacheMaintenance {
 internal fun removeTemporaryFiles(directory:File):Long {
  val root=directory.canonicalFile
  fun remove(file:File):Long {
   if(Files.isSymbolicLink(file.toPath())){file.delete();return 0}
   if(!file.canonicalPath.startsWith(root.path+File.separator))return 0
   if(file.isDirectory){val bytes=file.listFiles().orEmpty().sumOf(::remove);file.delete();return bytes}
   val bytes=file.length();return if(file.delete())bytes else 0
  }
  return root.listFiles().orEmpty().sumOf(::remove)
 }
 private fun used():Long=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
 suspend fun clear(context:Context):String {
  val before=used()
  val bytes=withContext(Dispatchers.IO){removeTemporaryFiles(context.cacheDir)}
  withContext(Dispatchers.Main){val view=WebView(context.applicationContext);try{view.clearCache(true)}finally{view.destroy()}}
  withContext(Dispatchers.Default){System.gc()};delay(200)
  return "已清理临时文件 ${"%.1f".format(java.util.Locale.ROOT,bytes/1048576.0)} MB及网页缓存；Java内存 ${before/1048576} → ${used()/1048576} MB"
 }
}
