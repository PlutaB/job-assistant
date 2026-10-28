package chat.plutab.job27

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun JSONObject.s(k:String, default:String=""):String = if(isNull(k)) default else optString(k,default)
fun JSONObject.arr(k:String):List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONObject.strings(k:String):List<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
fun json(vararg pairs:Pair<String,Any?>) = JSONObject().apply { pairs.forEach { put(it.first,it.second?:JSONObject.NULL) } }
val statuses=listOf("未投","已投","AI测评","笔试","1面","2面","3面","4面","Offer","拒绝")
fun visible(j:JSONObject)=j.s("grade") in listOf("S","A","B") || j.s("appliedAt").isNotEmpty() || (j.optBoolean("manual") && j.s("grade").isEmpty())
fun gradeOrder(j:JSONObject)=when(j.s("grade")){"S"->0;"A"->1;"B"->2;"U"->4;else->3}

@Entity(tableName="snapshot") data class CachedSnapshot(@PrimaryKey val id:Int=1,val body:String,val at:Long)
@Dao interface CacheDao { @Query("SELECT * FROM snapshot WHERE id=1") suspend fun read():CachedSnapshot?; @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(value:CachedSnapshot) }
@Database(entities=[CachedSnapshot::class],version=1,exportSchema=false) abstract class CacheDb:RoomDatabase(){abstract fun cache():CacheDao}
class SecretStore(context:Context) {
 private val prefs=context.getSharedPreferences("pairing",Context.MODE_PRIVATE)
 private fun key():SecretKey {val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};return (ks.getKey("job27",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("job27",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()}
 fun setToken(token:String){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());prefs.edit().putString("token",Base64.encodeToString(c.iv+c.doFinal(token.toByteArray()),Base64.NO_WRAP)).apply()}
 fun token():String=runCatching{val b=Base64.decode(prefs.getString("token","")!!,Base64.NO_WRAP);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));String(c.doFinal(b.copyOfRange(12,b.size)))}.getOrDefault("")
 var address:String get()=prefs.getString("address","")?:"";set(v){prefs.edit().putString("address",v).apply()}
 var addresses:List<String> get()=runCatching{val a=JSONArray(prefs.getString("addresses","[]"));(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList());set(v){prefs.edit().putString("addresses",JSONArray(v.take(12)).toString()).apply()}
 val serverId get()=prefs.getString("serverId","").orEmpty()
 val fingerprint get()=prefs.getString("fingerprint","").orEmpty()
 val computerName get()=prefs.getString("computerName","").orEmpty()
 fun bind(link:ComputerLink,url:String,token:String){setToken(token);addresses=link.addresses;prefs.edit().putString("address",url).putString("serverId",link.serverId).putString("fingerprint",link.fingerprint).putString("computerName",link.name).apply()}
 fun clearConnection(){prefs.edit().clear().apply()}

}
class Job27Model(app:Application):AndroidViewModel(app){
 val directGpt=DirectChatGpt(app,viewModelScope)
 private val bodies=JobBodyStore(app)
 private val dataReady=CompletableDeferred<Unit>()
 private val progressStore=RefreshProgressStore(app)
 private val progressSavedAt=mutableMapOf<String,Long>()
 private val refreshCompute=Dispatchers.Default.limitedParallelism(1)
 private val refreshBudget=RefreshBudget(app)
 private var refreshJob:Job?=null
 var singleRefreshTaskId by mutableStateOf<String?>(null);private set
 var singleRefreshStopping by mutableStateOf(false);private set
 private var batchPreparing=false
 val refreshBatch get()=snapshot.optJSONObject("refreshBatch")
 val batchActive get()=refreshBatch?.s("state")=="running"
 internal var collectorForTests:RefreshCollector?=null
 internal var ratingForTests:(suspend(String,suspend(String,JSONObject,String)->Unit)->String)?=null
 private val secrets=SecretStore(app)
 private val db=Room.databaseBuilder(app,CacheDb::class.java,"job27-cache.db").build()
 var snapshot by mutableStateOf(JSONObject());private set
 var online by mutableStateOf(false);private set
 var messageVersion by mutableIntStateOf(0);private set
 private var lastMessageHash=0
 var message by mutableStateOf("")
 var paired by mutableStateOf(secrets.token().isNotEmpty()&&secrets.fingerprint.isNotEmpty());private set
 var connectionStatus by mutableStateOf(if(paired)"正在自动连接" else if(secrets.token().isNotEmpty())"旧连接需要扫码升级一次" else "尚未配对电脑");private set
 var pairError by mutableStateOf("");private set
 var computerName by mutableStateOf(secrets.computerName);private set
 val remoteConfigured get()=secrets.addresses.any{isRemoteComputerAddress(it)}
 private var lastConnectionCheck=0L
 private var foreground=true
 private var rediscovering=false
 private val syncLock=Mutex()
 private val localLock=Mutex()
 private val messageLock=Mutex()
 private val localMode get()=directGpt.signedIn||snapshot.optBoolean("localOwner")
 private val discovery=ComputerDiscovery(app,{it==secrets.serverId&&paired},{candidate->viewModelScope.launch{rediscover(candidate)}})
 private val connectivity=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
 private fun onlineNow():Boolean=connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let{it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}==true
 private val networkOnline=MutableStateFlow(onlineNow())
 var waitingForNetwork by mutableStateOf(false);private set
 val refreshNetworkAvailable get()=networkOnline.value
 private fun networkChanged(lost:Network?=null){networkOnline.value=if(lost!=null&&connectivity.activeNetwork==lost)false else onlineNow();viewModelScope.launch{waitingForNetwork=busy&&!networkOnline.value;if(batchActive)updateBatch{it.put("networkWaiting",waitingForNetwork)}}}
 private val refreshNetwork=RefreshNetworkGate({networkOnline.value},{networkOnline.first{it}},{_->waitingForNetwork=busy&&!networkOnline.value;if(batchActive)updateBatch{it.put("networkWaiting",waitingForNetwork)}})
 private val networkCallback=object:ConnectivityManager.NetworkCallback(){
  override fun onAvailable(network:Network){networkChanged();viewModelScope.launch{if(paired&&foreground)sync(silent=true)}}
  override fun onLost(network:Network){networkChanged(network)}
  override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){networkChanged()}
 }

 var busy by mutableStateOf(false);private set
 var address by mutableStateOf(secrets.address)
 val companies get()=snapshot.arr("companies")
 val jobs get()=snapshot.arr("jobs")
 val tasks get()=snapshot.arr("tasks")
 private var journalViews by mutableStateOf(JSONObject())
 private var activeBatchTasks by mutableStateOf(emptySet<String>())
 private fun companyJournal(id:String)=RefreshJournal(getApplication(),id){record->
  viewModelScope.launch{journalViews=RefreshEfficiency.shallow(journalViews).put(id,record.put("companyId",id))}
 }
 private suspend fun rebindRefreshBatch(){
  val old=refreshBatch?:return;val next=CompanyRefreshView.rebind(old,journalViews)
  if(old.toString()==next.toString())return
  next.put("checkpointUpdatedAt",java.time.Instant.now().toString())
  snapshot=RefreshEfficiency.shallow(snapshot).put("refreshBatch",next)
  withContext(Dispatchers.IO){progressStore.saveBatch(next)}
 }
 fun currentRefreshTask(id:String):JSONObject?{
  val j=journalViews.optJSONObject(id)
  val task=CompanyRefreshView.select(id,tasks,j)?:return null
  return CompanyRefreshView.phase(task,j,refreshBatch,busy,singleRefreshTaskId,activeBatchTasks)
 }
 val currentRefreshTasks get()=companies.mapNotNull{currentRefreshTask(it.s("id"))}
 val displayCompanies get():List<JSONObject>{
  val grouped=jobs.groupBy{it.s("companyId")}
  return companies.map{CompanyRefreshView.company(it,currentRefreshTask(it.s("id")),grouped[it.s("id")].orEmpty())}
 }
 val displayRefreshBatch get()=refreshBatch?.let{CompanyRefreshView.batch(it,currentRefreshTasks)}
 fun historicalRefresh(task:JSONObject?)=task!=null&&currentRefreshTask(task.s("companyId"))?.s("id")?.let{it!=task.s("id")}==true

 val resumes get()=snapshot.arr("resumes")
 val profile get()=snapshot.optJSONObject("profile")?:JSONObject()
 init{viewModelScope.launch {
  val cached=withContext(Dispatchers.IO){
   val database=db.openHelper.writableDatabase
   val exists=database.query("SELECT id FROM snapshot WHERE id=1").use{it.moveToFirst()}
   if(!exists)null else bodies.readSnapshot(SnapshotTextReader{offset->database.query("SELECT substr(body, ?, 262144) FROM snapshot WHERE id=1",arrayOf<Any>(offset)).use{if(it.moveToFirst())it.getString(0)else ""}})
  }
  if(cached!=null){snapshot=if(cached.s("snapshotFile")=="cache-snapshot.json")withContext(Dispatchers.IO){android.util.AtomicFile(java.io.File(app.filesDir,"cache-snapshot.json")).openRead().bufferedReader(Charsets.UTF_8).use{bodies.readSnapshot(it)}}else cached} else {
   val raw=withContext(Dispatchers.IO){
    val initial=app.assets.open("initial-snapshot.json").bufferedReader().use{it.readText()}
    val data=JSONObject(initial)
    for(r in data.arr("resumes")) {
     val file=java.io.File(app.filesDir,"resumes/${r.s("id")}.pdf")
     if(!file.exists()){file.parentFile?.mkdirs();app.assets.open("resumes/${r.s("id")}.pdf").use{input->file.outputStream().use{input.copyTo(it)}}}
    }
    initial
   }
   snapshot=withContext(Dispatchers.IO){bodies.compact(JSONObject(raw))};db.cache().save(CachedSnapshot(body=raw,at=System.currentTimeMillis()))
  }
  if(!profile.has("factSections")){val source=withContext(Dispatchers.IO){JSONObject(app.assets.open("personal-facts.json").bufferedReader().use{it.readText()})};snapshot.put("profile",PersonalFacts.upgrade(profile,source));snapshot.put("localOwner",true);writeCache(snapshot)}
  snapshot=withContext(Dispatchers.IO){progressStore.restore(snapshot)}
  if(CompanySourceIdentity.snapshot(snapshot))snapshot=RefreshEfficiency.shallow(snapshot)
  if(withContext(refreshCompute){JobMetadataFacts.repairSnapshot(snapshot)})snapshot=RefreshEfficiency.shallow(snapshot)
  val upgraded=mutableMapOf<String,String>()
  val domesticUpdates=mutableMapOf<String,JSONObject>()
  journalViews=withContext(Dispatchers.IO){JSONObject().apply{for(c in companies){val id=c.s("id");val store=RefreshJournal(app,id)
   if(id==HutchDomesticScope.companyId)store.load()?.let{j->
    if(HutchDomesticScope.needs(j)){
     store.save(j);HutchDomesticScope.apply(j)
     val prefs=PersonalFacts.ratingContext(profile);val fingerprints=RatingFingerprintCache(prefs)
     val pending=RefreshJournal.pendingRatings(j,prefs,fingerprints::get).size
     CaptureRunOutcome.finish(j,pending);store.save(j)
     domesticUpdates[j.s("taskId")]=store.viewMetadata()!!.put("message",HutchDomesticScope.reason+"。"+CaptureRunOutcome.message(j,pending))
    }
   }
   if(CompanySourceIdentity.target(c))store.load()?.let{j->
    if(j.arr("entries").any{CompanySourceIdentity.wrongSource(it.s("url"))}){store.save(j);if(CompanySourceIdentity.journal(j)){store.save(j);upgraded[j.s("taskId")]=CompanySourceIdentity.reason}}
   }
   if(c.arr("links").any{it.s("url").trimEnd('/')=="https://example.invalid"})runCatching{store.reopenObsoleteZero()}.getOrNull()?.let{upgraded[it.s("taskId")]=it.s("reason")}
   runCatching{store.viewMetadata()}.getOrNull()?.let{put(id,it)}
  }}}

  // Recover a journal task even when an older cache omitted its current task record.
  val recovered=withContext(Dispatchers.IO){journalViews.keys().asSequence().mapNotNull{key->val id=journalViews.getJSONObject(key).s("taskId");if(tasks.any{it.s("id")==id})null else progressStore.task(id)?:CompanyRefreshView.select(key,emptyList(),journalViews.getJSONObject(key))}.toList()}
  if(recovered.isNotEmpty())snapshot=RefreshEfficiency.shallow(snapshot).put("tasks",JSONArray(recovered+tasks))
  if(upgraded.isNotEmpty()){
   val changed=tasks.map{task->upgraded[task.s("id")]?.let{reason->RefreshEfficiency.shallow(task).put("state","partial").put("stage","paused").put("message",reason).put("updatedAt",java.time.Instant.now().toString())}?:task}
   snapshot=RefreshEfficiency.shallow(snapshot).put("tasks",JSONArray(changed))
   withContext(Dispatchers.IO){for(task in changed)if(task.s("id") in upgraded)progressStore.saveTask(task)}
  }
  if(domesticUpdates.isNotEmpty()){
   val changed=tasks.map{t->domesticUpdates[t.s("id")]?.let{HutchDomesticScope.task(t,it)}?:t}
   snapshot=RefreshEfficiency.shallow(snapshot).put("tasks",JSONArray(changed))
   refreshBatch?.let{batch->domesticUpdates.values.firstOrNull()?.let{record->snapshot=RefreshEfficiency.shallow(snapshot).put("refreshBatch",HutchDomesticScope.batch(batch,record))}}
   withContext(Dispatchers.IO){for(t in changed)if(t.s("id") in domesticUpdates)progressStore.saveTask(t)}
  }
  rebindRefreshBatch()
  val scored=RefreshEfficiency.shallow(snapshot).put("companies",JSONArray(companies.map{RefreshEfficiency.shallow(it)}));if(CompanyScores.ensure(scored)){snapshot=scored;writeCache(snapshot)}
  if(snapshot.optBoolean("localOwner")){for(t in snapshot.arr("tasks"))if(t.s("transport")=="phone"&&t.s("state") in listOf("queued","running")&&!RefreshJournal(app,t.s("companyId")).exists())t.put("state","interrupted").put("stage","interrupted").put("message","旧版任务没有断点；继续刷新可复用已缓存详情")}
  withContext(Dispatchers.IO){saveCompanyFiles(app,snapshot,preserveExisting=true)}
  writeCache(snapshot)
  dataReady.complete(Unit)
  runCatching{connectivity.registerDefaultNetworkCallback(networkCallback)}
  if(paired)sync(silent=true)
  while(true){delay(if(online)3500 else 12000);if(paired&&foreground&&!busy)sync(silent=true)}
 }}
 fun setForeground(value:Boolean){foreground=value;if(!value)discovery.stop() else if(paired)viewModelScope.launch{sync(silent=true)}}
 private suspend fun rediscover(candidate:String){
  if(!paired||online||!foreground||rediscovering||busy)return
  rediscovering=true
  try{val identity=withContext(Dispatchers.IO){JSONObject(String(computerRequest(candidate,secrets.fingerprint,"","/identity"))).optJSONObject("computer")}
   if(identity?.s("id")==secrets.serverId){address=candidate;secrets.address=candidate;sync(silent=true)}
  }catch(_:Exception){}finally{rediscovering=false}
 }
 suspend fun request(path:String,method:String="GET",body:JSONObject?=null):ByteArray=withContext(Dispatchers.IO){
  require(paired){"请先扫描电脑管理网页的二维码"}
  val candidates=if(path=="/snapshot"&&method=="GET")((secrets.addresses.filter{isRemoteComputerAddress(it)}+address+secrets.addresses).distinct()) else listOf(address)
  var last:Exception?=null
  for(candidate in candidates){try{
   if(candidate!=address){val identity=JSONObject(String(computerRequest(candidate,secrets.fingerprint,"","/identity"))).optJSONObject("computer");require(identity?.s("id")==secrets.serverId){"电脑身份不匹配"}}
   val bytes=computerRequest(candidate,secrets.fingerprint,secrets.token(),path,method,body)
   if(candidate!=address){address=candidate;secrets.address=candidate}
   return@withContext bytes
  }catch(e:Exception){last=e;if(e is ComputerApiError)throw e}}
  throw last?:Exception("电脑暂不可达")
 }

 suspend fun sync(silent:Boolean=false)=syncLock.withLock{
  if(!paired||localMode)return@withLock
  if(!online)connectionStatus="正在自动连接 ${computerName.ifBlank{"电脑"}}"
  try{val raw=String(request("/snapshot"));val incoming=withContext(Dispatchers.IO){bodies.readSnapshot(raw.reader())};if(localMode)return@withLock;withContext(Dispatchers.IO){saveCompanyFiles(getApplication(),incoming)}
   val messageHash=incoming.optJSONArray("taskMessages")?.toString()?.hashCode()?:0
   incoming.remove("taskMessages");incoming.remove("companyFiles")
   writeCache(incoming);snapshot=incoming
   if(messageHash!=lastMessageHash){lastMessageHash=messageHash;messageVersion++}
   if(System.currentTimeMillis()-lastConnectionCheck>60000){
    runCatching{val c=JSONObject(String(request("/connection")));if(c.s("computerId")==secrets.serverId){val remote=c.strings("addresses").filter{isRemoteComputerAddress(it)};if(remote.isNotEmpty())secrets.addresses=(remote+secrets.addresses).distinct().take(12)}}
    lastConnectionCheck=System.currentTimeMillis()
   }
   online=true;connectionStatus="已连接 ${computerName.ifBlank{"电脑"}}";discovery.stop();if(!silent)message=""
  }catch(e:Exception){online=false
   connectionStatus=when{
    e is ComputerApiError&&e.status==401->{paired=false;discovery.stop();"配对已被撤销，请重新扫码"}
    e is javax.net.ssl.SSLException->"电脑安全身份无法验证，请在电脑管理页核对后重新扫码"
    else->"电脑暂不可达，正在重连；请检查两端 Tailscale 与电脑后台，可继续离线查看"
   }
   if(paired&&foreground&&!remoteConfigured)discovery.start()
   if(!silent)message=connectionStatus
  }
 }
 fun action(path:String,method:String="POST",body:JSONObject?=json(),done:(JSONObject)->Unit={}){if(localMode){localAction(path,method,body?:json(),done);return};if(!paired){message="已有记录可离线查看；刷新或保存修改前，请在个人信息中连接电脑。";return};viewModelScope.launch{busy=true;try{val result=JSONObject(String(request(path,method,body)));sync();done(result)}catch(e:Exception){message=e.message?:"无法连接电脑，请检查电脑服务"}finally{busy=false}}}
 fun clearPairError(){pairError=""}
 fun pairLink(raw:String,done:()->Unit={}){viewModelScope.launch{
  if(busy)return@launch
  busy=true;pairError=""
  try{val link=ComputerLink.parse(raw);var connected:String?=null;var token="";var last:Exception?=null
   for(candidate in link.addresses){try{
    val response=withContext(Dispatchers.IO){JSONObject(String(computerRequest(candidate,link.fingerprint,"","/pair","POST",json("code" to link.code,"serverId" to link.serverId,"deviceName" to android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL))))}
    require(response.optJSONObject("computer")?.s("id")==link.serverId){"电脑身份不匹配"}
    token=response.s("token");require(token.isNotEmpty()){ "电脑未返回配对授权" };connected=candidate;break
   }catch(e:Exception){last=e;if(e is ComputerApiError)break}}
   if(connected==null)throw last?:Exception("无法连接电脑，请确认两端 Tailscale 已登录同一账户并开启连接")
   secrets.bind(link,connected,token);address=connected;computerName=link.name;paired=true;sync(silent=true);done()
  }catch(e:Exception){pairError=when(e){is java.net.SocketTimeoutException,is java.net.ConnectException->"无法连接电脑。请确认电脑后台在线，两端 Tailscale 已登录同一账户并开启连接。";is javax.net.ssl.SSLException->"电脑安全指纹不匹配，请重新扫描电脑管理页的二维码。";else->e.message?:"配对失败"}}
  finally{busy=false}
 }}
 fun disconnectComputer(){viewModelScope.launch{if(online)runCatching{request("/unpair","POST",json())};secrets.clearConnection();paired=false;online=false;computerName="";address="";connectionStatus="尚未配对电脑";discovery.stop()}}
 private fun localAction(path:String,method:String,body:JSONObject,done:(JSONObject)->Unit){viewModelScope.launch{try{var answer=JSONObject();localSave{data->val parts=path.split('/').filter{it.isNotEmpty()};when{
  parts.firstOrNull()=="jobs"&&parts.size==2&&method=="PATCH"->{val j=data.arr("jobs").firstOrNull{it.s("id")==parts[1]}?:error("岗位不存在");for(k in listOf("title","url","location","description","notes","pendingConditions","recruitmentType"))if(body.has(k))j.put(k,body.get(k));if(body.has("department")){j.put("manualDepartment",true).put("department",body.get("department"))};if(body.has("grade")){require(body.isNull("grade")||body.s("grade") in listOf("S","A","B","U"));j.put("grade",body.get("grade")).put("manualGrade",true)};if(body.has("resumeId"))j.put("resumeId",body.get("resumeId"));if(body.has("status")){val status=body.s("status");require(status in statuses);if(status!=j.s("status")){val history=j.optJSONArray("history")?:JSONArray();history.put(json("status" to status,"at" to java.time.Instant.now().toString()));j.put("status",status).put("history",history);if(status!="未投"&&j.s("appliedAt").isBlank())j.put("appliedAt",java.time.Instant.now().toString())}};answer=j}
  parts.firstOrNull()=="companies"&&parts.size==2&&method=="PATCH"->{val c=data.arr("companies").firstOrNull{it.s("id")==parts[1]}?:error("公司不存在");for(k in listOf("name","englishName","description","website","notes","logo","links"))if(body.has(k))c.put(k,body.get(k));answer=c}
  path=="/profile"&&method=="PATCH"->{val p=data.optJSONObject("profile")?:json("id" to "me");for(k in listOf("name","email","phone","education","experience","skills","preferences","photo","factSections"))if(body.has(k))p.put(k,body.get(k));if(body.has("preferences")||body.has("factSections"))p.put("preferenceVersion",p.optInt("preferenceVersion",1)+1);data.put("profile",p);answer=p}
  path=="/jobs"&&method=="POST"->{require(data.arr("companies").any{it.s("id")==body.s("companyId")});require(body.s("title").isNotBlank());val j=JSONObject(body.toString()).put("id",java.util.UUID.randomUUID().toString()).put("manual",true).put("manualGrade",false).put("grade",JSONObject.NULL).put("firstSeenAt",java.time.Instant.now().toString()).put("status","未投").put("appliedAt",JSONObject.NULL).put("history",JSONArray()).put("active","unknown").put("reason","待审核").put("notes","");data.put("jobs",JSONArray(data.arr("jobs")+j));answer=j}
  else->error("此操作尚未接入手机本地模式，已有记录保留")
 };for(c in data.arr("companies"))c.put("applied",data.arr("jobs").count{it.s("companyId")==c.s("id")&&it.s("appliedAt").isNotBlank()});data};done(answer)}catch(e:Exception){message=e.message?:"手机保存失败"}}}
 private suspend fun writeCache(value:JSONObject){withContext(Dispatchers.IO){val atomic=android.util.AtomicFile(java.io.File(getApplication<Application>().filesDir,"cache-snapshot.json"));val out=atomic.startWrite();try{val writer=out.bufferedWriter(Charsets.UTF_8);streamJson(value,writer);writer.flush();atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}};db.cache().save(CachedSnapshot(body=json("snapshotFile" to "cache-snapshot.json").toString(),at=System.currentTimeMillis()))}
 private suspend fun localSave(copyInput:Boolean=true,update:(JSONObject)->JSONObject){
  if(busy)refreshBudget.checkpoint(checkSafety=false)
  localLock.withLock{
  val current=snapshot
  val expanded=withContext(refreshCompute){val oldTasks=current.arr("tasks").associate{it.s("id") to it.toString()};update(if(copyInput)JSONObject(current.toString())else RefreshEfficiency.shallow(current).put("tasks",JSONArray(current.arr("tasks").map{RefreshEfficiency.shallow(it)})).put("companies",JSONArray(current.arr("companies").map{RefreshEfficiency.shallow(it)})).apply{current.optJSONObject("refreshBatch")?.let{put("refreshBatch",RefreshEfficiency.shallow(it))}}).apply{put("localOwner",true);remove("companyFiles");CompanyScores.ensure(this);optJSONObject("refreshBatch")?.put("checkpointUpdatedAt",java.time.Instant.now().toString());arr("tasks").forEach{if(oldTasks[it.s("id")]!=it.toString())it.put("snapshotSavedAt",java.time.Instant.now().toString())}}}
  val next=withContext(Dispatchers.IO){bodies.compact(expanded)}
  withContext(Dispatchers.IO){saveCompanyFiles(getApplication(),next)};writeCache(next);snapshot=next
  }
 }
 fun clearDisposableCache(done:(String)->Unit){viewModelScope.launch{
  if(busy||batchActive||refreshJob?.isActive==true||directGpt.busy){message="请先暂停刷新或评分，再清理缓存";return@launch}
  busy=true
  try{
   dataReady.await()
   // Bound old progress before serializing; otherwise the cleanup itself copies the unbounded log.
   localLock.withLock{
    val cleaned=RefreshEfficiency.shallow(snapshot).put("tasks",JSONArray(tasks.map{RefreshEfficiency.shallow(it).put("events",JSONArray(RefreshEvents.compact(it.arr("events"))))}))
    withContext(Dispatchers.IO){for(task in cleaned.arr("tasks"))progressStore.saveTask(task);writeCache(cleaned)}
    snapshot=cleaned
   }
   val result=AppCacheMaintenance.clear(getApplication())
   done(result)
  }catch(e:Exception){message=e.message?:"缓存清理失败"}finally{busy=false}
 }}
 fun setCompanyScore(id:String,score:Double?,done:(Boolean)->Unit){viewModelScope.launch{try{localSave{data->CompanyScores.edit(data,id,score);data};done(true)}catch(e:Exception){message=e.message?:"评分保存失败";done(false)}}}
 private suspend fun taskStage(id:String,stage:String,text:String,state:String="running"){
  if(state=="running")refreshBudget.checkpoint()
  localLock.withLock{
  val old=tasks.first{it.s("id")==id}
  if(old.s("stage")==stage&&old.s("message")==text&&old.s("state")==state)return@withLock
  snapshot=RefreshEfficiency.progress(snapshot,id,stage,text,state,java.time.Instant.now().toString())
  val now=android.os.SystemClock.elapsedRealtime()
  if(state !in listOf("queued","running")||now-(progressSavedAt[id]?:Long.MIN_VALUE/2)>=10000){
   val task=tasks.first{it.s("id")==id};withContext(Dispatchers.IO){progressStore.saveTask(task)};progressSavedAt[id]=now
  }
  }
 }
 private suspend fun recordMessage(task:String,role:String,payload:JSONObject,status:String)=messageLock.withLock{withContext(Dispatchers.IO){
  TaskMessageStore(getApplication()).append(task,json("id" to java.util.UUID.randomUUID().toString(),"taskId" to task,"role" to role,"method" to "responses.create","status" to status,"at" to java.time.Instant.now().toString(),"payload" to payload))
 };withContext(Dispatchers.Main){messageVersion++}}
 private suspend fun preparePhoneRefresh(companyId:String,newRound:Boolean=false):Pair<JSONObject,RefreshJournal>{
  val company=companies.first{it.s("id")==companyId};val store=companyJournal(companyId)
  val old=withContext(Dispatchers.IO){store.load(jobs.filter{it.s("companyId")==companyId})}
  if(old!=null)PublicRecruitmentSources.prepareUpgrade(old)
  val j=RefreshResume.prepare(company,old,newRound)
  val retained=JSONArray();val manual=JSONObject()
  for(row in jobs.filter{it.s("companyId")==companyId&&CompanySourceIdentity.currentJob(it)})runCatching{val key=RefreshJournal.key(row.s("url"));if(row.s("grade") in listOf("S","A","B"))retained.put(key);if(row.optBoolean("manualGrade"))manual.put(key,row.opt("grade")?:JSONObject.NULL)}
  j.put("retainedMatches",retained).put("manualGrades",manual)

  j.put("resumeDiagnostic",json("at" to java.time.Instant.now().toString(),"explicitNewRound" to newRound,"previousTaskId" to old?.s("taskId"),"selectedTaskId" to j.s("taskId"),"previousDetails" to old?.let{RefreshJournal.facts(it).length()},"selectedDetails" to RefreshJournal.facts(j).length(),"captureComplete" to RefreshJournal.nativeComplete(j)))
  if(!j.has("baseline")){val base=JSONObject();for(row in jobs.filter{it.s("companyId")==companyId})base.put(row.s("id"),json("description" to JobBodyStore.hash(row),"grade" to row.s("suggestedGrade")));j.put("baseline",base)}
  j.put("state","queued");withContext(Dispatchers.IO){store.save(j)}
  localSave(copyInput=false){data->
   val task=data.arr("tasks").firstOrNull{it.s("id")==j.s("taskId")}?.let{RefreshEfficiency.shallow(it)}?:json("id" to j.s("taskId"),"companyId" to companyId,"kind" to "refresh","transport" to "phone","createdAt" to j.s("createdAt"),"events" to JSONArray())
   task.put("state","queued").put("stage","queued").put("message",if(old!=null&&old.s("taskId")==j.s("taskId"))"继续手机任务，读取已保存断点" else "公司刷新已排队");task.remove("finishedAt")
   val c=data.arr("companies").first{it.s("id")==companyId};c.put("latestTask",j.s("taskId"));if(c.s("lastResultState") in listOf("partial","failed","interrupted"))c.put("refreshWarning",true)
   data.put("tasks",JSONArray(listOf(task)+data.arr("tasks").filter{it.s("id")!=j.s("taskId")}));data
  }
  return j to store
 }
 private fun phoneWorker()=PhoneRefreshRunner(getApplication(),{snapshot},{update->localSave(copyInput=false,update=update)},{id,step,state,text->taskStage(id,step,text,state)},{id,role,payload,status->recordMessage(id,role,payload,status)},{prompt,emit->
   snapshot.optJSONObject("gptQuotaBlock")?.let{throw GptQuotaExhausted(it.s("detail"))}
   try{ratingForTests?.invoke(prompt,emit)?:directGpt.rateJobs(prompt,emit)}catch(e:GptQuotaExhausted){localSave(copyInput=false){it.put("gptQuotaBlock",json("at" to java.time.Instant.now().toString(),"detail" to e.detail))};throw e}
  },collectorForTests,progress={id,counts->localLock.withLock{
   val next=RefreshEfficiency.shallow(snapshot)
   next.put("tasks",JSONArray(tasks.map{if(it.s("id")==id)RefreshEfficiency.shallow(it).put("refreshCounts",counts)else it}))
   snapshot=next
  }},readJobs={rows->withContext(Dispatchers.IO){rows.map{bodies.defer(RefreshEfficiency.shallow(it))}}})
 private fun startRefreshService(companyId:String?=null){
  val intent=android.content.Intent(getApplication(),RefreshKeepAlive::class.java);if(companyId!=null)intent.putExtra("companyId",companyId)
  androidx.core.content.ContextCompat.startForegroundService(getApplication(),intent)
 }
 fun refresh(companyId:String,done:(JSONObject)->Unit){
  val existing=currentRefreshTask(companyId)?.takeIf{it.s("state")!="complete"}
  if(existing!=null){done(existing);return}
  if(batchActive||busy||refreshJob?.isActive==true||directGpt.busy){
   message="当前有其他任务正在处理；可以查看此公司的刷新状态"
   val history=currentRefreshTask(companyId);if(history!=null){done(history);return}
   done(json("id" to "view-company:$companyId","companyId" to companyId,"state" to "paused","stage" to "view","message" to "当前有其他任务正在处理，此公司尚无刷新记录。可稍后启动刷新。","events" to JSONArray()))
   return
  }
  busy=true
  viewModelScope.launch{try{
   dataReady.await();directGpt.ready.await();require(directGpt.planEnabled||ratingForTests!=null){"请先在设置 → ChatGPT 中登录并启用套餐权限"}
   val(j,_)=preparePhoneRefresh(companyId,newRound=true);done(tasks.first{it.s("id")==j.s("taskId")});startRefreshService(companyId)
  }catch(e:Exception){busy=false;message=e.message?:"无法启动刷新，已有断点保留"}}
 }
 fun resumeAfterQuota(done:(JSONObject)->Unit){viewModelScope.launch{localSave(copyInput=false){it.remove("gptQuotaBlock");it};refreshAll(reviewSavedOnly=true,done=done)}}
 fun refreshAll(reviewSavedOnly:Boolean=false,done:(JSONObject)->Unit){
  refreshBatch?.takeIf{it.optBoolean("quotaBlocked")&&snapshot.has("gptQuotaBlock")}?.let{done(it);return}
  if(refreshJob?.isCancelled==true&&refreshJob?.isCompleted==false){message="正在保存暂停进度，请稍后继续";return}
  refreshBatch?.takeIf{it.s("state")=="running"}?.let{done(it);return}
  if(batchPreparing)return
  if(busy&&refreshJob?.isActive!=true){message="当前任务正在准备，请稍后重试";return}
  batchPreparing=true
  viewModelScope.launch{try{
   dataReady.await();directGpt.ready.await();require(!snapshot.has("gptQuotaBlock")){"GPT用量已用完，请在刷新队列中于用量恢复后继续审核"};require(companies.isNotEmpty()){"还没有可刷新的公司"};require(directGpt.planEnabled||ratingForTests!=null){"请先在设置 → ChatGPT 中登录并启用套餐权限"}
   require(!directGpt.busy||refreshJob?.isActive==true){"ChatGPT正在处理请求，请稍后重试"}
   val old=refreshBatch;val batch=if(old!=null&&old.s("state") in listOf("paused","partial"))RefreshBatchQueue.resume(old,tasks.filter{it.s("state")=="complete"}.map{it.s("id")}.toSet(),reviewSavedOnly)else RefreshBatchQueue.create(companies)
   localSave(copyInput=false){it.put("refreshBatch",batch)};done(batch);startRefreshService()
  }catch(e:Exception){message=e.message?:"无法启动刷新全部"}finally{batchPreparing=false}}
 }
 private suspend fun updateBatch(edit:(JSONObject)->Unit)=localLock.withLock{
  val batch=JSONObject((refreshBatch?:error("刷新队列不存在")).toString());edit(batch);batch.put("checkpointUpdatedAt",java.time.Instant.now().toString())
  snapshot=RefreshEfficiency.shallow(snapshot).put("refreshBatch",batch)
  withContext(Dispatchers.IO){progressStore.saveBatch(batch)}
 }
 fun openRefreshControls(done:()->Unit){viewModelScope.launch{try{
  dataReady.await();rebindRefreshBatch()
  if(refreshBatch==null){val batch=RefreshBatchQueue.create(companies).put("state","paused").put("capturePaused",true).put("reviewPaused",true)
   withContext(Dispatchers.IO){for(row in RefreshBatchQueue.entries(batch)){
    val j=RefreshJournal(getApplication(),row.s("companyId")).metadata()
    if(j!=null)row.put("taskId",j.s("taskId")).put("reviewSavedFirst",true)
   }}
   localSave(copyInput=false){it.put("refreshBatch",batch)}
  }
  done()
 }catch(e:Exception){message=e.message?:"无法打开刷新控制"}}}
 fun controlBatchLane(lane:String){
  require(lane in listOf("capture","review"))
  viewModelScope.launch{try{
   dataReady.await();directGpt.ready.await()
   require(!busy||batchActive){"请先暂停单个公司刷新"}
   if(refreshJob?.isCancelled==true&&refreshJob?.isCompleted==false){message="正在保存暂停进度，请稍后继续";return@launch}
   val old=refreshBatch
   if(old==null||(old.s("state")=="complete"&&lane=="capture")){
    val batch=RefreshBatchQueue.create(companies).put("capturePaused",lane!="capture").put("reviewPaused",lane!="review")
    if(lane=="review")withContext(Dispatchers.IO){for(row in RefreshBatchQueue.entries(batch)){
     val j=RefreshJournal(getApplication(),row.s("companyId")).metadata()
     if(j!=null)row.put("taskId",j.s("taskId")).put("capture","done").put("captureComplete",RefreshJournal.indexedCaptureComplete(j))
    }}
    localSave(copyInput=false){it.put("refreshBatch",batch)}
   }else{
    val pause=old.s("state")=="running"&&!old.optBoolean(lane+"Paused")&&!(lane=="review"&&old.optBoolean("quotaBlocked"))
    if(!pause&&lane=="review"){require(directGpt.planEnabled||ratingForTests!=null){"请先登录 ChatGPT"};localSave(copyInput=false){it.remove("gptQuotaBlock");it}}
    updateBatch{b->RefreshLaneControl.batch(b,lane,pause)
     if(!pause){b.put("state","running");if(lane=="review"){b.remove("quotaBlocked");b.remove("error")}
      for(row in RefreshBatchQueue.entries(b))if(!row.optBoolean("companyPaused")&&row.s("state")!="complete"){
       row.put(lane+"Paused",false)
       if(lane=="capture"&&!row.optBoolean("captureComplete"))row.put("capture","queued")
       if(lane=="review"){row.put("review","queued");if(row.s("taskId").isNotBlank()&&row.s("capture")!="done")row.put("reviewSavedFirst",true)}
      }
     }
    }
   }
   if(refreshBatch?.s("state")=="running"&&refreshJob?.isActive!=true)startRefreshService()
  }catch(e:Exception){message=e.message?:"无法更新刷新状态"}}
 }
 fun controlBatchCompany(id:String){viewModelScope.launch{try{
  if(!companyRefreshControlEnabled(id))return@launch
  val running=currentRefreshTask(id)?.s("state")=="running"
  updateBatch{b->val row=RefreshBatchQueue.entry(b,id)
   if(running)row.put("companyPaused",true)
   else RefreshLaneControl.company(b,id,"capture",false)
  }
  if(refreshBatch?.s("state")=="running"&&refreshJob?.isActive!=true)startRefreshService()
 }catch(e:Exception){message=e.message?:"无法更新公司刷新状态"}}}

 private suspend fun runBatch(){
  val pipeline=IndependentRefreshPipeline({refreshBatch?:error("队列不存在")},{edit->updateBatch(edit)},
   capture={id->
    val row=RefreshBatchQueue.entry(refreshBatch!!,id)
    val(j,store)=preparePhoneRefresh(id,newRound=row.s("taskId").isBlank()&&row.optBoolean("newCaptureRound"));updateBatch{RefreshBatchQueue.entry(it,id).put("taskId",j.s("taskId"))};activeBatchTasks=activeBatchTasks+j.s("taskId")
    try{phoneWorker().run(companies.first{it.s("id")==id},j,store,captureOnly=true)}finally{activeBatchTasks=activeBatchTasks-j.s("taskId")}
    BatchStageResult(RefreshJournal.nativeComplete(j),tasks.firstOrNull{it.s("id")==j.s("taskId")}?.s("message").orEmpty(),j.s("taskId"),j.arr("entries").any{it.s("error").isNotBlank()&&it.optBoolean("retryableError")}||(j.optJSONObject("retryableDetails")?.length()?:0)>0)
   },review={id->
    val store=companyJournal(id);val j=withContext(Dispatchers.IO){store.load(jobs.filter{it.s("companyId")==id})}?:error("公司断点不存在")
    activeBatchTasks=activeBatchTasks+j.s("taskId")
    try{phoneWorker().run(companies.first{it.s("id")==id},j,store,reviewOnly=true)}finally{activeBatchTasks=activeBatchTasks-j.s("taskId")}
    BatchStageResult(j.optBoolean("fullyComplete"),tasks.firstOrNull{it.s("id")==j.s("taskId")}?.s("message").orEmpty(),j.s("taskId"),j.optBoolean("retryableReview"))
   },reviewHasPendingRatings={id->
    // Inspect the actual saved ratings and current preferences, not just a nonzero detail count.
    // The pipeline calls this only while neither lane owns this company's journal.
    val prefs=PersonalFacts.ratingContext(snapshot.optJSONObject("profile")?:JSONObject())
    withContext(Dispatchers.IO){
     val j=companyJournal(id).load(jobs.filter{it.s("companyId")==id})
     j!=null&&RefreshJournal.pendingRatings(j,prefs).isNotEmpty()
    }
   })
  pipeline.run()
 }
 fun serviceStartRefresh(companyId:String?,stop:()->Unit){
  if(refreshJob?.isActive==true)return
  refreshBudget.reset()
  refreshJob=viewModelScope.launch(refreshNetwork){var currentCompany=companyId;try{
   dataReady.await();directGpt.ready.await();busy=true
   if(batchActive)updateBatch{it.put("networkWaiting",!networkOnline.value)}
   refreshBudget.prepareStart()?.let{throw RefreshResourcePause(it)}
   refreshBudget.checkpoint()
   if(!directGpt.planEnabled&&ratingForTests==null&&!(batchActive&&refreshBatch?.optBoolean("reviewPaused")==true)&&!(companyId!=null&&journalViews.optJSONObject(companyId)?.optBoolean("reviewPaused")==true)){if(batchActive)updateBatch{it.put("state","paused").put("error","请在设置中登录ChatGPT后继续")};message="断点已保留，请在设置中登录ChatGPT后继续";return@launch}
   var single=companyId?:if(batchActive)null else withContext(Dispatchers.IO){companies.firstOrNull{RefreshResume.canAutoResume(it.s("id"),refreshBatch,RefreshJournal(getApplication(),it.s("id")).peekState())}?.s("id")}
   currentCompany=single
   while(single!=null||batchActive){
    if(single!=null){
     val id=single;currentCompany=id;val company=companies.first{it.s("id")==id};val store=companyJournal(id);val j=withContext(Dispatchers.IO){store.load(jobs.filter{it.s("companyId")==id})}?:break
     if(!batchActive){singleRefreshTaskId=j.s("taskId");singleRefreshStopping=false}
     val capturePaused=!batchActive&&j.optBoolean("capturePaused")
     val reviewPaused=!batchActive&&j.optBoolean("reviewPaused")
     if(capturePaused&&reviewPaused){taskStage(j.s("taskId"),"paused","两个通道都已暂停，断点已保存","partial")}
     else when{
      capturePaused->phoneWorker().run(company,j,store,reviewOnly=true)
      reviewPaused->phoneWorker().run(company,j,store,captureOnly=true)
      else->phoneWorker().run(company,j,store)
     }
     if(batchActive&&RefreshBatchQueue.entries(refreshBatch!!).any{it.s("companyId")==id})updateBatch{RefreshBatchQueue.entry(it,id).put("capture","done").put("review","done").put("captureComplete",RefreshJournal.nativeComplete(j)).put("state",if(j.optBoolean("fullyComplete"))"complete" else "partial").put("taskId",j.s("taskId"))}
     single=null;currentCompany=null
    }else runBatch()
   }
  }catch(e:Exception){withContext(NonCancellable){
   currentCompany?.let{company->runCatching{
    val store=companyJournal(company);val pausedId=withContext(Dispatchers.IO){store.pauseIfActive(e.message?:"任务中断")}
    if(pausedId!=null&&tasks.any{it.s("id")==pausedId})taskStage(pausedId,"paused",e.message?:"任务暂停，断点已保存","partial")
   }}
   if(batchActive)runCatching{updateBatch{it.put("state","paused").put("error",e.message?:"任务中断")}}
  };if(e !is CancellationException||e is RefreshResourcePause)message=e.message?:"刷新暂停，断点保留"}
  finally{singleRefreshTaskId=null;singleRefreshStopping=false;busy=false;waitingForNetwork=false;refreshJob=null;stop()}}
 }
 fun companyRefreshControlEnabled(id:String)=!busy||currentRefreshTask(id)?.s("state")=="running"
 fun taskLanePaused(task:JSONObject?,lane:String):Boolean{
  if(task==null||lane !in listOf("capture","review"))return false
  val row=refreshBatch?.let{b->RefreshBatchQueue.entries(b).firstOrNull{it.s("taskId")==task.s("id")}}
  if(row!=null)return row.optBoolean("companyPaused")||refreshBatch?.s("state")!="running"||refreshBatch?.optBoolean(lane+"Paused")==true||row.optBoolean(lane+"Paused")||row.s(lane)!="running"
  return !busy||singleRefreshTaskId!=task.s("id")||(currentRefreshTask(task.s("companyId"))?:task).optBoolean(lane+"Paused")
 }
 fun controlTaskLane(taskId:String,lane:String){
  require(lane in listOf("capture","review"))
  viewModelScope.launch{try{
   dataReady.await()
   val task=tasks.firstOrNull{it.s("id")==taskId}?:return@launch
   val companyId=task.s("companyId")
   if(!companyRefreshControlEnabled(companyId))return@launch
   require(currentRefreshTask(companyId)?.s("id")==taskId){"这是历史任务，请打开当前刷新"}
   val batchRow=refreshBatch?.let{b->RefreshBatchQueue.entries(b).firstOrNull{it.s("companyId")==companyId&&b.s("state")!="complete"}}
   if(batchRow!=null){
    updateBatch{b->
     val row=RefreshBatchQueue.entries(b).first{it.s("companyId")==companyId};row.put("taskId",taskId)
     val pause=!taskLanePaused(task,lane)
     RefreshLaneControl.company(b,companyId,lane,pause)
     if(!pause){if(row.s(lane)!="done"||(lane=="capture"&&!row.optBoolean("captureComplete")))row.put(lane,"queued")}
    }
    if(refreshBatch?.s("state")=="running"&&refreshJob?.isActive!=true)startRefreshService()
    return@launch
   }
   if((busy||refreshJob?.isActive==true)&&singleRefreshTaskId!=taskId){message="当前有其他任务正在处理，请稍后启动此公司";return@launch}
   val pause=!taskLanePaused(task,lane)
   val wasRunning=busy&&singleRefreshTaskId==taskId
   // Stop the old owner before saving controls: its final flush must not overwrite them.
   val running=refreshJob
   if(running?.isActive==true){running.cancel(CancellationException("刷新通道设置已更新"));running.join()}
   val store=companyJournal(companyId)
   val j=withContext(Dispatchers.IO){store.load(jobs.filter{it.s("companyId")==companyId})}?:return@launch
   if(!wasRunning)j.put((if(lane=="capture")"review" else "capture")+"Paused",true)
   j.put(lane+"Paused",pause)
   withContext(Dispatchers.IO){store.save(j)}
   localSave(copyInput=false){data->data.arr("tasks").firstOrNull{it.s("id")==taskId}?.put("capturePaused",j.optBoolean("capturePaused"))?.put("reviewPaused",j.optBoolean("reviewPaused"));data}
   if(!(j.optBoolean("capturePaused")&&j.optBoolean("reviewPaused")))startRefreshService(companyId)
  }catch(e:Exception){message=e.message?:"无法更新刷新通道"} }
 }
 fun stopSingleRefresh(taskId:String){if(batchActive||singleRefreshTaskId!=taskId)return;singleRefreshStopping=true;pauseRefresh()}
 private var resourceInspection:Job?=null
 fun inspectRefreshResources(onPause:()->Unit={}){if(resourceInspection?.isActive==true||!busy)return
  resourceInspection=viewModelScope.launch{refreshBudget.safetyReason()?.let{onPause();pauseRefresh(it)}}
 }
 fun pauseRefresh(reason:String?=null){refreshJob?.cancel(if(reason==null)CancellationException("任务暂停，断点已保存")else RefreshResourcePause(reason))}
 fun resumeRefreshIfNeeded(){viewModelScope.launch{dataReady.await();if(refreshJob?.isActive==true)return@launch
  val pending=batchActive||withContext(Dispatchers.IO){companies.any{RefreshResume.canAutoResume(it.s("id"),refreshBatch,RefreshJournal(getApplication(),it.s("id")).peekState())}}
  if(pending)runCatching{startRefreshService()}
 }}
 fun patchJob(id:String,body:JSONObject)=action("/jobs/$id","PATCH",body)
 fun uploadResume(name:String,bytes:ByteArray)=action("/resumes",body=json("name" to name,"base64" to Base64.encodeToString(bytes,Base64.NO_WRAP)))
 suspend fun taskMessages(taskId:String):List<JSONObject> = withContext(Dispatchers.IO){TaskMessageStore(getApplication()).summaries(taskId)}
 suspend fun taskMessagePayload(taskId:String,id:String):Any?=withContext(Dispatchers.IO){TaskMessageStore(getApplication()).payload(taskId,id)}
 suspend fun importPreview(raw:String)=JSONObject(String(request("/import/preview","POST",JSONObject(raw))))
 suspend fun loadJob(row:JSONObject):JSONObject=withContext(Dispatchers.IO){bodies.hydrate(row)}
 suspend fun searchJobs(rows:List<JSONObject>,query:String,companies:Map<String,JSONObject>):List<JSONObject> = withContext(Dispatchers.IO){
  if(query.isBlank())rows else rows.filter{row->currentCoroutineContext().ensureActive();(row.s("title")+row.s("department")+companies[row.s("companyId")]?.s("name")).contains(query,true)||bodies.body(row).contains(query,true)}
 }
 suspend fun exportTo(output:java.io.OutputStream){
  if(!localMode){output.write(request("/export"));return}
  withContext(Dispatchers.IO){
   val data=RefreshEfficiency.shallow(snapshot).put("version",1).put("exportedAt",java.time.Instant.now().toString())
   val writer=output.bufferedWriter(Charsets.UTF_8);writer.write("{");var first=true
   for(key in data.keys()){
    if(key in listOf("taskMessages","resumes"))continue
    if(!first)writer.write(",");first=false;streamJson(key,writer);writer.write(":");bodies.stream(data.opt(key),writer)
   }
   if(!first)writer.write(",");writer.write("\"resumes\":[")
   for((i,r) in data.arr("resumes").withIndex()){
    if(i>0)writer.write(",")
    val file=java.io.File(getApplication<Application>().filesDir,"resumes/${r.s("id")}.pdf")
    require(file.exists()){"简历尚未缓存在手机，不能导出不完整备份"}
    streamJson(RefreshEfficiency.shallow(r).put("base64",Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)),writer)
   }
   writer.write("],\"taskMessages\":[");var firstMessage=true;val messages=TaskMessageStore(getApplication())
   for(task in data.arr("tasks"))for(row in messages.summaries(task.s("id"))){
    if(!firstMessage)writer.write(",");firstMessage=false
    streamJson(row.put("payload",messages.payload(task.s("id"),row.s("id"))?:JSONObject.NULL),writer)
   }
   writer.write("]}");writer.flush()
  }
 }
 suspend fun export():ByteArray=java.io.ByteArrayOutputStream().also{exportTo(it)}.toByteArray()

 override fun onCleared(){directGpt.close();discovery.stop();runCatching{connectivity.unregisterNetworkCallback(networkCallback)};db.close();super.onCleared()}
}
