package chat.plutab.job27

import android.util.AtomicFile
import java.io.FilterOutputStream

/** Fixed-size encoding buffers; no whole-document string, byte array or old-file copy. */
fun writeAtomicJson(file:AtomicFile,value:Any):Long {
 file.baseFile.parentFile?.mkdirs()
 val output=file.startWrite()
 var count=0L
 val counter=object:FilterOutputStream(output){
  override fun write(b:Int){out.write(b);count++}
  override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len);count+=len}
 }
 try{val writer=counter.bufferedWriter(Charsets.UTF_8);streamJson(value,writer);writer.flush();file.finishWrite(output)}
 catch(e:Exception){file.failWrite(output);throw e}
 return count
}
