package chat.plutab.job27

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Official PDF job descriptions, downloaded and parsed one document at a time. */
object RecruitmentDocuments {
 fun supports(url:String)=false // No fixed employer document URLs in public edition.
 private suspend fun download(url:String,target:File)=cancellableHttp{bind->
  val uri=URI(url);require(uri.scheme=="https"&&uri.host=="www.astellas.com"&&uri.port==-1&&uri.userInfo==null)
  val c=URL(url).openConnection() as HttpsURLConnection;bind(c);val deadline=RequestDeadline(c,60000)
  try{c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000;c.setRequestProperty("User-Agent","Mozilla/5.0")
   if(c.responseCode !in 200..299)throw RefreshHttpFailure(c.responseCode)
   c.inputStream.use{input->target.outputStream().use{output->val buffer=ByteArray(16384);var size=0;while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=20000000){"官网文档超过20MB"};output.write(buffer,0,n)}}}
  }finally{deadline.close();c.disconnect()}
 }
 fun links(html:String):List<JSONObject> = Regex("<a\\b[^>]*href=[\"']([^\"']+\\.pdf(?:\\?[^\"']*)?)[\"'][^>]*>([\\s\\S]*?)</a>",RegexOption.IGNORE_CASE).findAll(html).mapNotNull{m->
  val url=URI("https://example.invalid").resolve(m.groupValues[1]).toString();val u=URI(url)
  if(u.host!="www.astellas.com"||!u.path.startsWith("/content/dam/astellas-com/global/zh/"))null else {
   val title=android.text.Html.fromHtml(m.groupValues[2],android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace("arrow_forward","").trim()
   title.takeIf{it.isNotBlank()}?.let{json("url" to url,"text" to it)}
  }
 }.distinctBy{it.s("url")}.toList()
 suspend fun collect(app:Application,entry:JSONObject,j:JSONObject,save:suspend()->Unit,onFact:suspend(JSONObject,JSONObject)->Unit,stage:suspend(String,String)->Unit)=withContext(Dispatchers.IO){
  val temp=File.createTempFile("recruitment-",".download",app.cacheDir)
  try{
   if(entry.optInt("pdfAdapterVersion")!=1){entry.put("done",false).put("keys",org.json.JSONArray()).put("nextPage",1);entry.remove("total");j.put("listingComplete",false)}
   if(!entry.optBoolean("done")){
    download(entry.s("url"),temp);val rows=links(temp.readText());require(rows.isNotEmpty()){"官网未列出PDF岗位，不能确认为零岗位"}
    RefreshJournal.addPage(j,entry,rows,rows.size,false);entry.put("lastReadPage",1).put("pdfAdapterVersion",1);save()
   }
   PDFBoxResourceLoader.init(app)
   for((index,key) in entry.strings("keys").withIndex()){
    currentCoroutineContext().ensureActive();val facts=RefreshJournal.facts(j);if(facts.has(key))continue
    val row=RefreshJournal.queue(j).getJSONObject(key)
    try{
     stage("details","读取PDF职位 ${index+1}/${entry.strings("keys").size}")
     retryRefreshPoint(onRetry={attempt,error->stage("retry","PDF重试 $attempt 次：$error")}){
      download(row.s("url"),temp)
      val text=PDDocument.load(temp,MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir)).use{document->require(!document.isEncrypted){"职位PDF已加密"};PDFTextStripper().getText(document).trim()}
      require(text.length>=80){"PDF没有可提取的职位正文，可能是扫描图片"}
      val fact=json("url" to row.s("url"),"title" to row.s("text"),"description" to text,"sources" to row.getJSONArray("sources"),"active" to "unknown")
      facts.put(key,fact);RefreshJournal.markFact(j,key);j.getJSONObject("failedDetails").remove(key);save();onFact(fact,j)
     }
    }catch(e:Exception){if(e is CancellationException)throw e;j.getJSONObject("failedDetails").put(key,e.message?:"PDF读取失败");save();stage("details","PDF失败：${row.s("text")} · ${e.message}")}
   }
  }catch(e:Exception){if(e is CancellationException)throw e;entry.put("error",e.message?:"PDF列表读取失败");save()}
  finally{temp.delete()}
 }
}
