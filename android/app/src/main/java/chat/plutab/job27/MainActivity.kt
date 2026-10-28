package chat.plutab.job27

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Purple=Color(0xFFAFA5FF)
val Ink=Color(0xFF39383F)
val Muted=Color(0xFF8D8999)
val Tint=Color(0xFFEEEBFF)
val Pale=Color(0xFFFAF9FF)
val Accent=Color(0xFF7664E8)
class MainActivity:ComponentActivity(){override fun onStart(){super.onStart();(application as Job27Application).jobs.setForeground(true);(application as Job27Application).jobs.resumeRefreshIfNeeded()};override fun onStop(){(application as Job27Application).jobs.setForeground(false);super.onStop()};override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Accent,secondary=Purple,background=Color.White,surface=Color.White,surfaceVariant=Tint,onSurface=Ink,onBackground=Ink)){Job27App()}}}}
fun date(s:String):String=runCatching{DateTimeFormatter.ofPattern("yy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(s))}.getOrDefault(if(s.isEmpty())"尚未完整刷新" else s)
fun ago(s:String):String=runCatching{val m=(Instant.now().epochSecond-Instant.parse(s).epochSecond).coerceAtLeast(0)/60;when{m<1->"刚刚";m<60->"${m}分钟前";m<1440->"${m/60}小时前";else->"${m/1440}天前"}}.getOrDefault("等待首次核实")
@Composable fun Label(text:String,modifier:Modifier=Modifier,color:Color=Muted){Text(text,modifier,color=color,fontSize=12.sp)}
val NeutralBadge=Color(0xFFE6E6E9)
@Composable fun Badge(text:String,bg:Color=Tint,fg:Color=Accent,bold:Boolean=false){
 val density=androidx.compose.ui.platform.LocalDensity.current
 Surface(color=bg,shape=RoundedCornerShape(4.dp)){
  if(bold&&text=="×"){
   Box(Modifier.padding(horizontal=3.dp).size(with(density){9.sp.toDp()},with(density){16.sp.toDp()}).semantics{contentDescription="拒绝"},contentAlignment=Alignment.Center){
    Canvas(Modifier.size(with(density){9.sp.toDp()})){
     val inset=size.width/12f;val end=size.width-inset
     drawLine(fg,androidx.compose.ui.geometry.Offset(inset,inset),androidx.compose.ui.geometry.Offset(end,end),strokeWidth=with(density){2.sp.toPx()},cap=androidx.compose.ui.graphics.StrokeCap.Round)
     drawLine(fg,androidx.compose.ui.geometry.Offset(inset,end),androidx.compose.ui.geometry.Offset(end,inset),strokeWidth=with(density){2.sp.toPx()},cap=androidx.compose.ui.graphics.StrokeCap.Round)
    }
   }
  }else Box(Modifier.height(with(density){16.sp.toDp()}),contentAlignment=Alignment.Center){Text(text,Modifier.padding(horizontal=3.dp),color=fg,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)))}
 }
}
fun statusColor(s:String)=when{ s in listOf("AI测评","笔试")->Color(0xFFE3AB00);s.endsWith("面")->Color(0xFFE88316);s=="Offer"->Color(0xFF25865F);s=="拒绝"->Color(0xFFC64C57);s=="未投"->Muted;else->Accent }
@Composable fun Grade(j:JSONObject){val g=j.s("grade","待审核").ifBlank{"待审核"};val pending=g=="待审核";val neutral=g=="U"||pending;Badge(if(pending)"W" else g,if(neutral)NeutralBadge else if(g=="S")Color(0xFFE3F2E6) else Tint,if(neutral)Muted else if(g=="S")Color(0xFF378944) else Accent)}
@Composable fun StatusBadge(status:String){
 if(status=="撤回")return
 val current=status.ifBlank{"未投"}
 val label=when(current){"未投"->"未";"AI测评"->"AI";"拒绝"->"×";else->current}
 Badge(label,if(current=="未投")NeutralBadge else statusColor(current).copy(alpha=.13f),statusColor(current),bold=current=="拒绝")
}
@Composable fun Logo(name:String,raw:String="",size:Int=48,rounded:Boolean=false){
 val context=LocalContext.current
 val bitmap=remember(raw,name){runCatching{
  val bytes=if(raw.startsWith("data:image/"))Base64.decode(raw.substringAfter(","),Base64.DEFAULT)else{
   val logos=JSONObject(context.assets.open("company-logos.json").bufferedReader().use{it.readText()})
   context.assets.open("logos/"+logos.getString(name)).use{it.readBytes()}
  }
  BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.let { decoded ->
   // Built-in artwork uses uneven margins; use each brand mark without its wordmark.
   val isBundled=raw.isEmpty()||runCatching {
    val logos=JSONObject(context.assets.open("company-logos.json").bufferedReader().use{it.readText()})
    context.assets.open("logos/"+logos.getString(name)).use{it.readBytes()}.contentEquals(bytes)
   }.getOrDefault(false)
   val bounds=if(isBundled)when(name){
    "辉瑞"->intArrayOf(54,54,85,85)
    "晶泰科技"->intArrayOf(10,62,68,68)
    "凯莱英"->intArrayOf(10,85,84,20)
    "石药集团"->intArrayOf(13,74,41,42)
    "国药集团"->intArrayOf(15,66,50,45)
    else->null
   }else null
   transparentCompanyLogo(if(bounds!=null)android.graphics.Bitmap.createBitmap(decoded,bounds[0],bounds[1],bounds[2],bounds[3])else decoded).asImageBitmap()
  }
 }.getOrNull()}
 Box(Modifier.size(size.dp).clip(RoundedCornerShape(if(rounded)12.dp else 0.dp)).background(if(bitmap==null)Tint else Color.Transparent),contentAlignment=Alignment.Center){if(bitmap!=null)Image(bitmap,"$name Logo",Modifier.fillMaxSize())else Text(name.take(1),color=Accent,fontSize=(size/2.7).sp,fontWeight=FontWeight.Bold)}
}
@Composable fun Search(value:String,onChange:(String)->Unit,hint:String,modifier:Modifier=Modifier){
 var focused by remember{mutableStateOf(false)}
 BasicTextField(value,onChange,modifier.fillMaxWidth().heightIn(min=44.dp).onFocusChanged{focused=it.isFocused}
  .border(1.dp,if(focused)Accent else Color(0xFFDEDAF4),RoundedCornerShape(10.dp)).padding(horizontal=14.dp,vertical=8.dp),
  singleLine=true,textStyle=LocalTextStyle.current.copy(color=Ink,fontSize=14.sp),cursorBrush=SolidColor(Accent),
  decorationBox={inner->Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f)){if(value.isEmpty())Text(hint,color=Muted,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);inner()};Spacer(Modifier.width(8.dp));SketchIcon("search",Purple,Modifier.size(26.dp))}})
}
@Composable fun Block(title:String,edit:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=Pale)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=16.sp);if(edit!=null)IconButton(onClick=edit,Modifier.size(32.dp)){Icon(Icons.Outlined.Edit,"编辑$title",tint=Accent,modifier=Modifier.size(19.dp))}};content()}}}
@Composable fun Empty(text:String){Column(Modifier.fillMaxWidth().padding(vertical=42.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Outlined.WorkOutline,null,tint=Purple,modifier=Modifier.size(40.dp));Text(text,color=Muted)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Job27App(vm:Job27Model=(LocalContext.current.applicationContext as Job27Application).jobs){
 val listPages=rememberSaveableStateHolder()
 var tab by rememberSaveable{mutableIntStateOf(0)};var companyId by rememberSaveable{mutableStateOf<String?>(null)};var jobId by rememberSaveable{mutableStateOf<String?>(null)}
 var messageTask by remember{mutableStateOf<String?>(null)}
 var batchOpen by rememberSaveable{mutableStateOf(false)}
 var taskFromBatch by rememberSaveable{mutableStateOf(false)}
 val batchScrollState=rememberScrollState()
 var previewTask by remember{mutableStateOf<JSONObject?>(null)}
 var companyEditor by remember{mutableStateOf<JSONObject?>(null)};var jobEditor by remember{mutableStateOf<JSONObject?>(null)};var taskId by rememberSaveable{mutableStateOf<String?>(null)}
 val company=vm.displayCompanies.find{it.s("id")==companyId};val job=vm.jobs.find{it.s("id")==jobId};val task=vm.currentRefreshTasks.firstOrNull{it.s("id")==taskId}?:vm.tasks.find{it.s("id")==taskId}?:previewTask?.takeIf{it.s("id")==taskId};val scope=rememberCoroutineScope();val snackbar=remember{SnackbarHostState()}
 val notifyContext=LocalContext.current
 val notifyPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
 LaunchedEffect(vm.busy){if(vm.busy&&android.os.Build.VERSION.SDK_INT>=33&&androidx.core.content.ContextCompat.checkSelfPermission(notifyContext,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
 LaunchedEffect(vm.message){val text=vm.message;if(text.isNotEmpty()){snackbar.showSnackbar(text);if(vm.message==text)vm.message=""}}
 val dialogReady=vm.message.isEmpty()&&snackbar.currentSnackbarData==null
 BackHandler(enabled=jobId!=null||companyId!=null){if(jobId!=null)jobId=null else companyId=null}
 Scaffold(containerColor=Color.White,snackbarHost={SnackbarHost(snackbar)},bottomBar={SketchNavigation(tab){index->tab=index;companyId=null;jobId=null}},topBar={Column{
  if(company!=null&&job==null)CompanyPageTopBar(company,{companyId=null},{companyEditor=company})
  else TopAppBar(title={Text(if(job!=null)"岗位详情" else if(tab==0)"求职助手" else if(tab==1)"岗位" else if(tab==2)"个人信息" else "设置",fontWeight=FontWeight.Bold,fontSize=if(tab==0&&job==null)28.sp else 20.sp,fontStyle=androidx.compose.ui.text.font.FontStyle.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={if(job!=null)IconButton(onClick={jobId=null}){SketchIcon("back",modifier=Modifier.size(28.dp).semantics{contentDescription="返回"})}},actions={if(job==null&&company==null&&tab==0)TextButton(onClick={vm.openRefreshControls{batchOpen=true}}){SketchIcon("refresh",modifier=Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(if(vm.batchActive)"查看队列" else if(vm.refreshBatch?.s("state") in listOf("paused","partial"))"继续全部" else "刷新全部",fontSize=12.sp)};if(job==null&&tab==0)IconButton(onClick={companyEditor=JSONObject()}){SketchIcon("add",Purple,Modifier.size(36.dp).semantics{contentDescription="添加公司"})}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.White))

 Box(Modifier.fillMaxWidth().height(4.dp)){if(vm.busy&&(companyId==null||vm.currentRefreshTask(companyId!!)?.s("state")=="running"))LinearProgressIndicator(Modifier.fillMaxWidth(),color=Accent,trackColor=Tint)}
 }}){padding->
 Box(Modifier.padding(padding).fillMaxSize()){
 when{job!=null->JobDetail(vm,job,{scope.launch{try{jobEditor=vm.loadJob(job)}catch(e:Exception){vm.message=e.message?:"岗位正文读取失败"}}},{taskId=it.s("id")});company!=null->listPages.SaveableStateProvider("company-jobs:"+company.s("id")){CompanyScreen(vm,company,{jobId=it.s("id")},{jobEditor=json("companyId" to company.s("id"))},{vm.refresh(company.s("id")){previewTask=it;taskId=it.s("id")}})};tab==0->listPages.SaveableStateProvider("companies"){CompanyList(vm,{companyId=it.s("id")},{vm.refresh(it.s("id")){t->previewTask=t;taskId=t.s("id")}})};tab==1->listPages.SaveableStateProvider("all-jobs"){JobList(vm,vm.jobs,{jobId=it.s("id")},showCompany=true)};tab==2->ProfileScreen(vm);else->SettingsScreen(vm)}
 }
 }
 if(batchOpen&&dialogReady)vm.displayRefreshBatch?.let{batch->BatchRefreshDialog(batch,vm.currentRefreshTasks,{batchOpen=false},{vm.pauseRefresh()},{if(batch.optBoolean("quotaBlocked"))vm.resumeAfterQuota{batchOpen=true}else vm.refreshAll{batchOpen=true}},{item->val t=vm.currentRefreshTask(item.s("companyId"));if(t!=null){batchOpen=false;taskFromBatch=true;taskId=t.s("id")}},controlLane={vm.controlBatchLane(it)},controlCompany={vm.controlBatchCompany(it)},scrollState=batchScrollState,canControlCompany={vm.companyRefreshControlEnabled(it)},controlsEnabled=!vm.busy||vm.batchActive)}
 if(messageTask!=null&&dialogReady){val t=vm.tasks.find{it.s("id")==messageTask};if(t!=null)TaskMessages(vm,t.s("companyId"),messageTask){messageTask=null}}
 if(companyEditor!=null&&dialogReady)CompanyEditor(vm,companyEditor!!){companyEditor=null}
 if(jobEditor!=null&&dialogReady)JobEditor(vm,jobEditor!!){jobEditor=null}
 if(taskId!=null&&dialogReady)RefreshTaskDialog(vm,taskId!!,task,{taskId=null;if(taskFromBatch){batchOpen=true;taskFromBatch=false}},{id->messageTask=id;taskId=null},{id->taskId=id},backToBatch=if(taskFromBatch)({taskId=null;batchOpen=true;taskFromBatch=false})else null)
}

@Composable fun CompanyList(vm:Job27Model,open:(JSONObject)->Unit,refresh:(JSONObject)->Unit){var q by rememberSaveable{mutableStateOf("")};var warningCompany by remember{mutableStateOf<JSONObject?>(null)};if(warningCompany!=null){val c=warningCompany!!;AlertDialog(onDismissRequest={warningCompany=null},title={Text(c.s("name")+" · 刷新未完成")},text={Text(c.s("refreshError").ifBlank{vm.currentRefreshTask(c.s("id"))?.s("message")?:"有未完成项，已保存断点，可继续刷新。"})},confirmButton={TextButton(onClick={warningCompany=null}){Text("关闭")}})};Column(Modifier.fillMaxSize().padding(horizontal=20.dp).padding(top=16.dp)){Search(q,{q=it},"");Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=4.dp),horizontalArrangement=Arrangement.End){Label("已投 / 匹配 / 全部",Modifier.padding(end=46.dp))};LazyColumn{items(vm.displayCompanies.filter{it.s("name").contains(q,true)||it.s("englishName").contains(q,true)},key={it.s("id")}){c->val companyPaused=vm.refreshBatch?.let{b->RefreshBatchQueue.entries(b).firstOrNull{it.s("companyId")==c.s("id")}?.optBoolean("companyPaused")}==true||vm.currentRefreshTask(c.s("id"))?.s("state")=="paused";val active=vm.currentRefreshTask(c.s("id"))?.takeIf{it.s("state")=="running"};Row(Modifier.fillMaxWidth().heightIn(min=82.dp),verticalAlignment=Alignment.CenterVertically){Row(Modifier.weight(1f).clickable{open(c)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Logo(c.s("name"),c.s("logo"),48);Column(Modifier.weight(1f)){Text(c.s("name"),fontSize=18.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);Row(Modifier.padding(top=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){val flag=companyFlag(c);if(flag.isNotEmpty())Text(flag,fontSize=15.sp);if(c.s("englishName").isNotEmpty())Label(c.s("englishName"))}};Column(horizontalAlignment=Alignment.End){val count=c.optJSONObject("snapshot");Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){Text("${c.optInt("applied")} / ${countValue(count,"matched")} / ${countValue(count,"total")}",fontWeight=FontWeight.SemiBold,fontSize=13.sp);if(hasRefreshWarning(c))Text("⚠️",Modifier.clickable{warningCompany=c}.semantics{contentDescription="刷新未完成，查看原因"},fontSize=13.sp)};Label(if(companyPaused)"任务暂停" else ago(c.s("lastAttempt",c.s("lastSuccess"))),Modifier.padding(top=5.dp));if(c.s("lastSuccess").isNotEmpty())Label(date(c.s("lastSuccess")))}};IconButton(onClick={refresh(c)},modifier=Modifier.semantics{contentDescription="刷新"+c.s("name")}){if(companyPaused)SketchIcon("pause",RefreshIconColor,Modifier.size(18.dp))else if(active!=null&&vm.busy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp,color=Purple)else SketchIcon("refresh",Purple,Modifier.size(30.dp).semantics{contentDescription="刷新${c.s("name")}"})}};HorizontalDivider(color=Tint)};if(vm.companies.isEmpty())item{Empty("暂无公司，点击右上角添加")}}}}

@Composable fun CompanyPageTopBar(c:JSONObject,back:()->Unit,edit:()->Unit) {
 val context=LocalContext.current
 Row(Modifier.fillMaxWidth().background(Pale).statusBarsPadding().padding(start=8.dp,end=20.dp,top=4.dp,bottom=8.dp),verticalAlignment=Alignment.Top) {
  Column(Modifier.weight(1f).padding(end=12.dp)) {
   Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
    IconButton(onClick=back,modifier=Modifier.size(48.dp)){SketchIcon("back",modifier=Modifier.size(28.dp).semantics{contentDescription="返回"})}
    Text(c.s("name"),Modifier.weight(1f).padding(start=4.dp),fontSize=20.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
   }
   TextButton(onClick={openUrl(context,c.s("website"))},contentPadding=PaddingValues(0.dp),modifier=Modifier.padding(start=12.dp).height(32.dp)){Text("招聘官网 ↗",fontSize=13.sp)}
   Text(c.s("description").replace("\n"," · "),Modifier.padding(start=12.dp,top=2.dp),fontSize=14.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
  }
  Column(Modifier.padding(top=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
   Logo(c.s("name"),c.s("logo"),56)
   IconButton(onClick=edit,Modifier.size(32.dp)){Icon(Icons.Outlined.Edit,"编辑公司",tint=Purple,modifier=Modifier.size(20.dp))}
  }
 }
}

@Composable fun CompanyScreen(vm:Job27Model,c:JSONObject,open:(JSONObject)->Unit,add:()->Unit,refresh:()->Unit) {
 var materialsOpen by remember(c.s("id")){mutableStateOf(false)}
 var scoreOpen by remember(c.s("id")){mutableStateOf(false)}
 val score=CompanyScores.value(c,vm.companies.indexOfFirst{it.s("id")==c.s("id")},vm.companies.size)

 var warningOpen by remember(c.s("id")){mutableStateOf(false)}
 val companyJobs=remember(vm.snapshot,c.s("id")){vm.jobs.filter{it.s("companyId")==c.s("id")&&CompanySourceIdentity.currentJob(it)}}
 JobList(vm,companyJobs,open,showCompany=false,add=add,header={
  Column(Modifier.fillMaxWidth().background(Pale).padding(start=20.dp,end=20.dp,top=0.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   TextButton(onClick={scoreOpen=true},contentPadding=PaddingValues(0.dp)){
    Text("公司评分："+CompanyScores.display(score),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
    Spacer(Modifier.width(6.dp));Icon(Icons.Outlined.Edit,"编辑公司评分",modifier=Modifier.size(17.dp))
   }
   Row(verticalAlignment=Alignment.CenterVertically) {
    SketchIcon("clock",modifier=Modifier.size(17.dp))
    Column(Modifier.weight(1f)){Label("  "+ago(c.s("lastAttempt",c.s("lastSuccess"))));if(c.s("lastAttempt").isNotEmpty())Label("  "+date(c.s("lastAttempt")))}
    IconButton(onClick=refresh){SketchIcon("refresh",Purple,Modifier.size(30.dp).semantics{contentDescription="刷新公司"})}
   }
   if(hasRefreshWarning(c))TextButton(onClick={warningOpen=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=24.dp)){Text("⚠️ 刷新有未完成项",fontSize=12.sp)}
   val count=c.optJSONObject("snapshot")
   HorizontalDivider(color=Tint,thickness=2.dp)
   Row(Modifier.fillMaxWidth().padding(vertical=10.dp)) {
    listOf("全部岗位" to countValue(count,"total"),"匹配岗位" to countValue(count,"matched"),"已投递" to c.optInt("applied").toString()).forEach{(label,value)->
     Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=21.sp,fontWeight=FontWeight.Bold);Label(label)}
    }
   }
   HorizontalDivider(color=Tint,thickness=2.dp)
  }
  CompanyMaterialsPreview(vm,c){materialsOpen=true}
 })
 if(scoreOpen)CompanyScoreDialog(vm,c,score){scoreOpen=false}
 if(materialsOpen)CompanyMaterials(vm,c){materialsOpen=false}
 if(warningOpen)AlertDialog(onDismissRequest={warningOpen=false},title={Text(c.s("name")+" · 刷新未完成")},text={Text(c.s("refreshError").ifBlank{vm.currentRefreshTask(c.s("id"))?.s("message")?:"未完成项已保留，可继续刷新。"})},confirmButton={TextButton(onClick={warningOpen=false}){Text("关闭")}})
}

@Composable fun CompanyScoreDialog(vm:Job27Model,c:JSONObject,score:Double,close:()->Unit){
 var text by remember(c.s("id")){mutableStateOf(CompanyScores.display(score))}
 var saving by remember{mutableStateOf(false)}
 val number=text.toDoubleOrNull();val valid=number!=null&&CompanyScores.valid(number)
 fun save(value:Double?){saving=true;vm.setCompanyScore(c.s("id"),value){ok->saving=false;if(ok)close()}}
 AlertDialog(onDismissRequest={if(!saving)close()},title={Text("公司评分")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(c.s("name"),fontWeight=FontWeight.SemiBold)
  OutlinedTextField(text,{text=it},singleLine=true,label={Text("评分")},isError=!valid,
   keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
  Text("默认评分＝公司排名逆序＋50。手动修改后保留，岗位排序使用此评分。",fontSize=13.sp,color=Muted)
  TextButton(onClick={save(null)},enabled=!saving){Text("恢复默认评分")}
 }},confirmButton={TextButton(onClick={save(number!!)},enabled=valid&&!saving){Text(if(saving)"保存中…"else "保存")}},dismissButton={TextButton(onClick=close,enabled=!saving){Text("取消")}})
}

data class JobFilters(val grade:String="全部",val status:String="全部",val location:String="",val active:String="全部",val onlyNew:Boolean=false)
@OptIn(ExperimentalLayoutApi::class)
@Composable fun JobList(vm:Job27Model,jobs:List<JSONObject>,open:(JSONObject)->Unit,showCompany:Boolean,modifier:Modifier=Modifier,header:(@Composable ()->Unit)?=null,add:(()->Unit)?=null) {
 var q by rememberSaveable{mutableStateOf("")}
 var filterOpen by remember{mutableStateOf(false)}
 var f by rememberSaveable(stateSaver=listSaver<JobFilters,Any>(save={listOf(it.grade,it.status,it.location,it.active,it.onlyNew)},restore={JobFilters(it[0] as String,it[1] as String,it[2] as String,it[3] as String,it[4] as Boolean)})){mutableStateOf(JobFilters())}
 var statusDescending by rememberSaveable{mutableStateOf(true)}
 var gradeDescending by rememberSaveable{mutableStateOf(true)}
 var companyDescending by rememberSaveable{mutableStateOf(true)}
 var appliedDescending by rememberSaveable{mutableStateOf(true)}
 val companiesById=remember(vm.companies){vm.companies.associateBy{it.s("id")}}
 val searched by produceState(jobs,jobs,q,companiesById){value=vm.searchJobs(jobs,q,companiesById)}
 val selected=remember(searched,vm.snapshot,q,f,statusDescending,gradeDescending,companyDescending,appliedDescending){JobSorting.sort(searched.filter{j->
  val company=companiesById[j.s("companyId")]
  visible(j)&&
   (f.grade=="全部"||j.s("grade","待审核")==f.grade)&&
   (f.status=="全部"||j.s("status")==f.status)&&j.s("location").contains(f.location,true)&&
   (f.active=="全部"||j.s("active")==f.active)&&
   (!f.onlyNew||j.s("newInTask").isNotEmpty()&&j.s("newInTask")==company?.s("latestTask"))
 },vm.companies,gradeDescending,companyDescending,appliedDescending,statusDescending)}
 LazyColumn(modifier.fillMaxSize()) {
  if(header!=null)item{header()}
  item{Column(Modifier.padding(start=20.dp,end=20.dp,top=16.dp,bottom=4.dp)){
   Search(q,{q=it},if(showCompany)"搜索岗位、公司或关键词" else "搜索公司岗位")
   Spacer(Modifier.height(8.dp))
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    TextButton(onClick={statusDescending=!statusDescending},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)){Text("状态 "+if(statusDescending)"↓" else "↑",fontSize=12.sp,maxLines=1)}
    TextButton(onClick={gradeDescending=!gradeDescending},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)){Text("评级 "+if(gradeDescending)"↓" else "↑",fontSize=12.sp,maxLines=1)}
    TextButton(onClick={companyDescending=!companyDescending},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)){Text("公司评分 "+if(companyDescending)"↓" else "↑",fontSize=12.sp,maxLines=1)}
    TextButton(onClick={appliedDescending=!appliedDescending},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)){Text("已投 "+if(appliedDescending)"↓" else "↑",fontSize=12.sp,maxLines=1)}
    IconButton(onClick={filterOpen=true}){Icon(Icons.Outlined.FilterList,"筛选岗位",tint=Purple,modifier=Modifier.size(24.dp))}
    if(add!=null)IconButton(onClick=add){SketchIcon("add",Purple,Modifier.size(26.dp).semantics{contentDescription="添加岗位"})}
   }
  }}
  item{Label("${selected.size} 个岗位",Modifier.padding(horizontal=20.dp,vertical=6.dp))}
  items(selected,key={it.s("id")}){j->
   val company=CompanySourceIdentity.displayCompany(j,companiesById[j.s("companyId")])
   JobListRow(j,company,showCompany){open(j)}
   HorizontalDivider(Modifier.padding(horizontal=28.dp),color=Color(0xFFCCCCCC))
  }
  if(selected.isEmpty())item{Empty("暂无符合条件的岗位")}
 }
 if(filterOpen)AlertDialog(onDismissRequest={filterOpen=false},title={Text("筛选岗位")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Choice("评级",listOf("全部","S","A","B","待审核","U"),f.grade){f=f.copy(grade=it)}
  Choice("投递状态",listOf("全部")+statuses,f.status){f=f.copy(status=it)}
  OutlinedTextField(f.location,{f=f.copy(location=it)},label={Text("地点关键词")})
  Choice("在招状态",listOf("全部","open","closed","unknown"),f.active){f=f.copy(active=it)}
  Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.onlyNew,{f=f.copy(onlyNew=it)});Text("仅本轮新发现")}
 }},confirmButton={TextButton(onClick={filterOpen=false}){Text("完成")}},dismissButton={TextButton(onClick={f=JobFilters()}){Text("重置")}})
}

@Composable fun Choice(label:String,options:List<String>,value:String,change:(String)->Unit){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp)){Text("$label：${when(value){"open"->"在招";"closed"->"已关闭";"unknown"->"待核实";else->value}}",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)};DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=340.dp)){options.forEach{o->DropdownMenuItem(text={Text(when(o){"open"->"在招";"closed"->"已关闭";"unknown"->"待核实";else->o})},onClick={change(o);expanded=false})}}}}
fun openUrl(context:android.content.Context,url:String){runCatching{val u=Uri.parse(url);if(u.scheme in listOf("http","https"))context.startActivity(Intent(Intent.ACTION_VIEW,u))}}

@Composable fun JobQuickEdits(j:JSONObject,grade:(String?)->Unit,status:(String)->Unit){
 var gradeOpen by remember(j.s("id")){mutableStateOf(false)}
 var statusOpen by remember(j.s("id")){mutableStateOf(false)}
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
  Box{
   Box(Modifier.heightIn(min=40.dp).widthIn(min=32.dp).clickable{statusOpen=true}.semantics{contentDescription="编辑投递状态："+j.s("status","未投")},contentAlignment=Alignment.Center){StatusBadge(j.s("status","未投"))}
   DropdownMenu(expanded=statusOpen,onDismissRequest={statusOpen=false},modifier=Modifier.heightIn(max=340.dp)){
    statuses.forEach{value->DropdownMenuItem(text={Text(value)},onClick={statusOpen=false;status(value)})}
   }
  }
  Box{
   Box(Modifier.heightIn(min=40.dp).widthIn(min=32.dp).clickable{gradeOpen=true}.semantics{contentDescription="编辑岗位评级："+j.s("grade","待审核")},contentAlignment=Alignment.Center){Grade(j)}
   DropdownMenu(expanded=gradeOpen,onDismissRequest={gradeOpen=false}){
    listOf("S","A","B","U","待审核").forEach{value->DropdownMenuItem(text={Text(value)},onClick={gradeOpen=false;grade(if(value=="待审核")null else value)})}
   }
  }
 }
}

@Composable fun JobDetail(vm:Job27Model,j:JSONObject,edit:()->Unit,showTask:(JSONObject)->Unit){val context=LocalContext.current;val full by produceState<JSONObject?>(null,j){value=try{vm.loadJob(j)}catch(e:Exception){vm.message=e.message?:"岗位正文读取失败";j}};val c=CompanySourceIdentity.displayCompany(j,vm.displayCompanies.find{it.s("id")==j.s("companyId")});var notes by remember(j.s("id"),j.s("notes")){mutableStateOf(j.s("notes"))};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text(j.s("title"),fontSize=25.sp,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Logo(c?.s("name")?:"岗",c?.s("logo")?:"",38);Column(Modifier.weight(1f)){Text(c?.s("name")?:"",fontWeight=FontWeight.SemiBold);Label(j.s("location")+" · "+j.s("recruitmentType"))};JobQuickEdits(j,{vm.patchJob(j.s("id"),json("grade" to it))},{vm.patchJob(j.s("id"),json("status" to it))})};Block("岗位信息",edit){if(j.s("department").isNotBlank())Label("部门："+j.s("department"));Label("官网发布："+if(j.s("publishedAt").isEmpty())"未知" else date(j.s("publishedAt")));Label("首次发现："+if(j.s("firstSeenAt").isEmpty())"历史记录，原始发现时间未知" else date(j.s("firstSeenAt")));Label("最近核实："+if(j.s("lastVerifiedAt").isEmpty())"尚未核实" else date(j.s("lastVerifiedAt")));Label("在招状态："+when(j.s("active")){"open"->"在招";"closed"->"已关闭";else->"待核实"});if(j.s("url").isNotEmpty())TextButton(onClick={openUrl(context,j.s("url"))}){Text("在官网查看 ↗")}};Block("岗位描述"){Text(full?.s("description","尚无完整JD，可编辑补充或让Codex抓取。")?:"加载中…",fontSize=14.sp,lineHeight=23.sp)};Block("匹配评级"){Text(j.s("reason"),fontSize=14.sp);if(j.optBoolean("manualGrade"))Label("已保留人工评级 · Codex建议："+j.s("suggestedGrade","待审核"));if(j.s("pendingConditions").isNotEmpty())Text("待核实："+j.s("pendingConditions"),fontSize=14.sp);OutlinedButton(onClick={vm.action("/jobs/${j.s("id")}/review",done=showTask)}){Text("让 Codex 审核")}};Block("投递记录"){val resumeOptions=listOf("不关联简历")+vm.resumes.map{it.s("name")+" · "+it.s("id").take(6)};val selected=vm.resumes.find{it.s("id")==j.s("resumeId")};Choice("使用简历",resumeOptions,selected?.let{it.s("name")+" · "+it.s("id").take(6)}?:"不关联简历"){value->val index=resumeOptions.indexOf(value);vm.patchJob(j.s("id"),json("resumeId" to if(index<=0)null else vm.resumes[index-1].s("id")))};j.arr("history").reversed().forEach{Label(date(it.s("at"))+"  "+it.s("status")+if(it.s("source")=="import")"（历史状态导入）" else "")}};Block("个人备注"){OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth(),placeholder={Text("添加备注…")},minLines=3);TextButton(onClick={vm.patchJob(j.s("id"),json("notes" to notes))}){Text("保存备注")}};Spacer(Modifier.height(16.dp))}}

@Composable fun CompanyEditor(vm:Job27Model,original:JSONObject,close:()->Unit){var showMessages by remember{mutableStateOf(false)};var showMaterials by remember{mutableStateOf(false)};var name by remember{mutableStateOf(original.s("name"))};var english by remember{mutableStateOf(original.s("englishName"))};var description by remember{mutableStateOf(original.s("description"))};var website by remember{mutableStateOf(original.s("website"))};var links by remember{mutableStateOf(original.arr("links").joinToString("\n"){it.s("url")})};var logo by remember{mutableStateOf(original.s("logo"))};val context=LocalContext.current;val scope=rememberCoroutineScope();val logoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)scope.launch{try{logo=withContext(Dispatchers.IO){val bytes=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()};val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size);val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,160,160,true);val out=java.io.ByteArrayOutputStream();scaled.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);"data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}}catch(e:Exception){vm.message="无法读取Logo"}}};AlertDialog(onDismissRequest=close,title={Text(if(original.s("id").isEmpty())"添加公司" else "编辑公司")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Logo(name,logo);TextButton(onClick={logoPicker.launch("image/*")}){Text("选择公司Logo")}};OutlinedTextField(name,{name=it},label={Text("公司名称")});OutlinedTextField(english,{english=it},label={Text("英文名称")});OutlinedTextField(website,{website=it},label={Text("官网链接")});OutlinedTextField(description,{description=it},label={Text("公司简介")},minLines=3);OutlinedTextField(links,{links=it},label={Text("招聘入口，每行一个链接")},minLines=3);Label("刷新会检查这里配置的全部入口。");if(original.s("id").isNotEmpty()){TextButton(onClick={showMaterials=true}){Text("招聘资料与备注")};TextButton(onClick={showMessages=true}){Text("刷新记录与 GPT 消息")}}}},confirmButton={TextButton(onClick={val body=json("logo" to logo,"name" to name,"englishName" to english,"website" to website,"description" to description,"links" to JSONArray(links.lines().map(String::trim).filter(String::isNotEmpty).map{json("name" to "招聘入口","url" to it)}));vm.action(if(original.s("id").isEmpty())"/companies" else "/companies/${original.s("id")}",if(original.s("id").isEmpty())"POST" else "PATCH",body){close()}},enabled=name.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick=close){Text("取消")}});if(showMessages)TaskMessages(vm,original.s("id")){showMessages=false};if(showMaterials)CompanyMaterials(vm,vm.displayCompanies.find{it.s("id")==original.s("id")}?:original){showMaterials=false}}

@Composable fun JobEditor(vm:Job27Model,original:JSONObject,close:()->Unit){var title by remember{mutableStateOf(original.s("title"))};var url by remember{mutableStateOf(original.s("url"))};var location by remember{mutableStateOf(original.s("location"))};var department by remember{mutableStateOf(original.s("department"))};var description by remember{mutableStateOf(original.s("description"))};AlertDialog(onDismissRequest=close,title={Text(if(original.s("id").isEmpty())"添加岗位" else "编辑岗位")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){OutlinedTextField(title,{title=it},label={Text("岗位名称")});OutlinedTextField(url,{url=it},label={Text("岗位链接")});OutlinedTextField(location,{location=it},label={Text("地点")});OutlinedTextField(department,{department=it},label={Text("部门（选填）")});OutlinedTextField(description,{description=it},label={Text("完整JD")},minLines=5);Label("保存后可在详情点击“让 Codex 审核”。")}},confirmButton={TextButton(onClick={val body=json("companyId" to original.s("companyId"),"title" to title,"url" to url,"location" to location,"department" to department,"description" to description);vm.action(if(original.s("id").isEmpty())"/jobs" else "/jobs/${original.s("id")}",if(original.s("id").isEmpty())"POST" else "PATCH",body){close()}},enabled=title.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})}

@Composable fun ProfileScreen(vm:Job27Model){
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var page by rememberSaveable{mutableStateOf<String?>(null)}
 var editing by remember{mutableStateOf<String?>(null)};var value by remember{mutableStateOf("")};var previewFile by remember{mutableStateOf<File?>(null)}
 var resumeEdit by remember{mutableStateOf<JSONObject?>(null)};var resumeName by remember{mutableStateOf("")}
 val upload=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{val result=withContext(Dispatchers.IO){val bytes=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()};var name="简历.pdf";context.contentResolver.query(uri,null,null,null,null)?.use{c->val index=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(c.moveToFirst()&&index>=0)name=c.getString(index)};name to bytes};vm.uploadResume(result.first,result.second)}catch(e:Exception){vm.message=e.message?:"无法读取简历"}}}
 val photo=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)scope.launch{try{val raw=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use{it.readBytes()}};val bitmap=BitmapFactory.decodeByteArray(raw,0,raw.size);val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,256,256,true);val out=java.io.ByteArrayOutputStream();scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);vm.action("/profile","PATCH",json("photo" to "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)))}catch(e:Exception){vm.message="无法读取照片"}}}

 val sections=vm.profile.arr("factSections")
 fun edit(key:String,text:String){editing=key;value=text}
 val groups=listOf("education" to "教育背景","research" to "研究与项目","internships" to "实习经历","leadership" to "领导与组织经历")
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Card(Modifier.clickable{page=sections.firstOrNull{it.s("group")=="basic"}?.s("id")?:"contacts"},colors=CardDefaults.cardColors(containerColor=Pale),shape=RoundedCornerShape(18.dp)){
   Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
    Box(Modifier.clickable{photo.launch("image/*")}){Logo(vm.profile.s("name","我"),vm.profile.s("photo"),80,rounded=true)}
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(vm.profile.s("name","我的资料"),fontWeight=FontWeight.Bold,fontSize=20.sp);Text(PersonalFacts.identity(sections.filter{it.s("group")=="education"}),fontSize=13.sp,lineHeight=19.sp);Text(vm.profile.s("email"),fontSize=12.sp,lineHeight=18.sp,color=Muted)}
    IconButton(onClick={edit("name",vm.profile.s("name"))}){Icon(Icons.Outlined.Edit,"编辑姓名",tint=Accent)}
   }
  }
  ProfileEntry("简历", "${vm.resumes.size} 份 · "+(vm.resumes.firstOrNull{it.optBoolean("isDefault")}?.s("name")?:"未设置默认版本")){page="resumes"}
  groups.forEach{(key,title)->val rows=sections.filter{it.s("group")==key};ProfileEntry(title,PersonalFacts.overview(key,rows)){page=if(rows.size==1)rows.first().s("id")else key}}
  ProfileEntry("技能",PersonalFacts.skillsOverview(vm.profile)){page="skills"}
  ProfileEntry("求职偏好"){page="preferences"}
  Spacer(Modifier.height(8.dp))
 }
 if(page!=null){val key=page!!;val title=groups.firstOrNull{it.first==key}?.second?:when(key){"basic"->"个人资料";"resumes"->"简历";"skills"->"技能";"preferences"->"求职偏好";"contacts"->"联系方式";"educationNotes"->"教育补充";"experienceNotes"->"研究与工作补充";else->sections.firstOrNull{it.s("id")==key}?.s("title")?:"个人资料"}
  val detail=sections.firstOrNull{it.s("id")==key}
  ProfilePage(title,{page=when{detail!=null->if(sections.count{it.s("group")==detail.s("group")}==1)null else detail.s("group");key=="contacts"->sections.firstOrNull{it.s("group")=="basic"}?.s("id");key=="educationNotes"->"education";key=="experienceNotes"->"research";else->null}},edit=if(detail!=null){{edit("fact:"+key,detail.s("content"))}}else if(key in listOf("skills","preferences")){{edit(key,vm.profile.s(key))}}else null){
   when(key){
    "resumes"->{Button(onClick={upload.launch(arrayOf("application/pdf"))}){Icon(Icons.Outlined.Add,null);Text("添加简历")}
     vm.resumes.forEach{r->Card(colors=CardDefaults.cardColors(containerColor=Pale)){
      Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.PictureAsPdf,null,tint=Color(0xFFEB6868));Column(Modifier.weight(1f).padding(horizontal=10.dp).clickable{scope.launch{try{val file=File(context.filesDir,"resumes/${r.s("id")}.pdf");if(!file.exists()){val bytes=vm.request("/resumes/${r.s("id")}/file");withContext(Dispatchers.IO){file.parentFile?.mkdirs();file.writeBytes(bytes)}};previewFile=file}catch(e:Exception){vm.message="简历文件尚未保存在本机"}}}){Text(r.s("name"),fontSize=15.sp);Label(date(r.s("createdAt"))+" · ${r.optLong("size")/1024} KB");if(r.optBoolean("isDefault"))Label("默认简历",color=Accent)};IconButton(onClick={resumeEdit=r;resumeName=r.s("name")}){Icon(Icons.Outlined.MoreVert,"管理简历",tint=Accent)}}
     }};if(vm.resumes.isEmpty())Empty("暂无简历，点击添加")}
    "skills"->{FactContent(PersonalFacts.skillsOverview(vm.profile,true));Block("个人补充",{edit("skills",vm.profile.s("skills"))}){FactContent(vm.profile.s("skills","待补充"))};val languages=PersonalFacts.languages(vm.profile);if(languages.isNotBlank())Block("语言能力"){FactContent(languages)}}
    "preferences"->FactContent(vm.profile.s(key,"待补充"))
    "contacts"->listOf("name" to "姓名","email" to "邮箱","phone" to "电话").forEach{(field,label)->Block(label,{edit(field,vm.profile.s(field))}){Text(vm.profile.s(field,"待补充"))}}
    "educationNotes","experienceNotes"->{val field=if(key=="educationNotes")"education" else "experience";Block("已有补充资料",{edit(field,vm.profile.s(field))}){Text(vm.profile.s(field,"待补充"),lineHeight=24.sp)}}
    else->{val rows=sections.filter{it.s("group")==key};if(detail!=null){if(detail.s("group")=="basic")ProfileEntry("常用联系方式",vm.profile.s("email")){page="contacts"};FactContent(if(detail.s("group")=="leadership")detail.s("content").replace("### 端午文化日","#### 学联活动：端午文化日")else detail.s("content"))}else{
     rows.forEach{r->ProfileEntry(r.s("title"),PersonalFacts.brief(r.s("content"))){page=r.s("id")}}
     if(key=="basic"){sections.filter{it.s("group")=="basic"}.forEach{r->FactContent(r.s("content"));TextButton(onClick={edit("fact:"+r.s("id"),r.s("content"))}){Text("编辑个人资料")}};ProfileEntry("常用联系方式",""){page="contacts"}}
     if(key=="education"&&vm.profile.s("education").isNotBlank())ProfileEntry("已有补充资料",PersonalFacts.brief(vm.profile.s("education"))){page="educationNotes"}
     if(key=="research"&&vm.profile.s("experience").isNotBlank())ProfileEntry("已有补充资料",PersonalFacts.brief(vm.profile.s("experience"))){page="experienceNotes"}
    }}
   }
  }
 }

 if(previewFile!=null)PdfPreview(previewFile!!){previewFile=null}
 if(editing!=null)AlertDialog(onDismissRequest={editing=null},title={Text("编辑资料")},text={OutlinedTextField(value,{value=it},Modifier.fillMaxWidth().heightIn(max=400.dp).verticalScroll(rememberScrollState()),minLines=if(editing in listOf("name","email","phone"))1 else 8)},confirmButton={TextButton(onClick={val field=editing!!;val patch=if(field.startsWith("fact:")){val next=JSONArray(sections.map{JSONObject(it.toString()).apply{if(s("id")==field.removePrefix("fact:"))put("content",value)}});json("factSections" to next)}else json(field to value);vm.action("/profile","PATCH",patch){editing=null}}){Text("保存")}},dismissButton={TextButton(onClick={editing=null}){Text("取消")}})
 if(resumeEdit!=null)AlertDialog(onDismissRequest={resumeEdit=null},title={Text("管理简历")},text={Column{OutlinedTextField(resumeName,{resumeName=it},label={Text("简历名称")});TextButton(onClick={vm.action("/resumes/${resumeEdit!!.s("id")}","PATCH",json("isDefault" to true)){resumeEdit=null}}){Text("设为默认简历")};TextButton(onClick={vm.action("/resumes/${resumeEdit!!.s("id")}","DELETE",null){resumeEdit=null}}){Text("删除简历",color=Color(0xFFC64C57))}}},confirmButton={TextButton(onClick={vm.action("/resumes/${resumeEdit!!.s("id")}","PATCH",json("name" to resumeName)){resumeEdit=null}}){Text("保存名称")}},dismissButton={TextButton(onClick={resumeEdit=null}){Text("取消")}})

}

@Composable fun SettingsScreen(vm:Job27Model){
 val context=LocalContext.current;val scope=rememberCoroutineScope();var page by rememberSaveable{mutableStateOf<String?>(null)}
 var diagnostics by remember{mutableStateOf<String?>(null)}
 var cacheResult by remember{mutableStateOf<String?>(null)}
 var issueReport by remember{mutableStateOf<String?>(null)};var issueReportBusy by remember{mutableStateOf(false)}
 val exportIssues=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)scope.launch{try{
  val text=issueReport?:withContext(Dispatchers.IO){CaptureIssueReport.read(context,vm.companies)}
  withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri)){"无法打开保存位置"}.bufferedWriter(Charsets.UTF_8).use{it.write(text)}}
  issueReport=null;vm.message="问题列表已导出"
 }catch(e:Exception){vm.message=e.message?:"问题列表导出失败"}}}
 if(issueReport!=null)AlertDialog(onDismissRequest={issueReport=null},title={Text("问题列表")},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(issueReport!!,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)}},confirmButton={TextButton(onClick={issueReport=null}){Text("关闭")}},dismissButton={Row{
  TextButton(onClick={(context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("求职助手问题列表",issueReport));vm.message="问题列表已复制"}){Text("复制")}
  TextButton(onClick={exportIssues.launch("求职助手问题列表-v${BuildConfig.VERSION_NAME}.txt")}){Text("导出文件")}
 }})
 if(cacheResult!=null)AlertDialog(onDismissRequest={cacheResult=null},title={Text("缓存清理完成")},text={Text(cacheResult!!)},confirmButton={TextButton(onClick={cacheResult=null}){Text("关闭")}})
 if(diagnostics!=null)AlertDialog(onDismissRequest={diagnostics=null},title={Text("运行诊断")},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(diagnostics!!,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)}},confirmButton={TextButton(onClick={diagnostics=null}){Text("关闭")}})
 val exportFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)!!.use{vm.exportTo(it)}};vm.message="备份已导出，包含岗位和简历"}catch(e:Exception){vm.message=e.message?:"导出失败"}}}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  if(vm.snapshot.has("gptQuotaBlock")){
   Text("GPT用量已用完，自动审核已暂停；已有岗位保留。",color=Muted,fontSize=13.sp)
   TextButton(onClick={vm.resumeAfterQuota{}}){Text("用量恢复后继续审核")}
  }
  ProfileEntry("ChatGPT",if(vm.directGpt.signedIn)"已登录 · 账户与模型" else "登录与模型设置"){page="gpt"}
  ProfileEntry("后台运行","自启动、允许后台运行与刷新通知"){page="background"}
  ProfileEntry("数据管理","导出备份、清理缓存"){page="data"}
  ProfileEntry("作者 PlutaB","github.com/PlutaB"){context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/PlutaB")))}
  Block("应用版本"){Text("v${BuildConfig.VERSION_NAME}");TextButton(onClick={scope.launch{diagnostics=withContext(Dispatchers.IO){RuntimeDiagnostics.read(context)}}}){Text("运行诊断")};TextButton(enabled=!issueReportBusy,onClick={scope.launch{issueReportBusy=true;try{issueReport=withContext(Dispatchers.IO){CaptureIssueReport.read(context,vm.companies)}}catch(e:Exception){vm.message=e.message?:"生成问题列表失败"}finally{issueReportBusy=false}}}){Text(if(issueReportBusy)"正在生成问题列表"else "问题列表")}}
 }
 if(page!=null)ProfilePage(if(page=="gpt")"ChatGPT"else if(page=="background")"后台运行"else "数据管理",{page=null}){
  if(page=="gpt")DirectGptPanel(vm.directGpt)else if(page=="background")BackgroundSettingsPanel{vm.message=it}else Block("数据备份"){Text("导出公司、岗位、个人资料和简历，选择文件的保存位置。",fontSize=14.sp);Button(onClick={exportFile.launch("求职助手备份.json")}){Text("导出完整备份")}
 Spacer(Modifier.height(12.dp));Text("清理临时文件和网页缓存，保留岗位、评分、备注、断点和登录。",fontSize=14.sp)
 Button(enabled=!vm.busy&&!vm.batchActive&&!vm.directGpt.busy,onClick={issueReport=null;diagnostics=null;vm.clearDisposableCache{page=null;cacheResult=it}}){Text("清理缓存")}
 if(vm.busy||vm.batchActive||vm.directGpt.busy)Text("暂停刷新或评分后可清理",fontSize=12.sp,color=Muted)
 }
 }
}

@Composable fun ProfileEntry(title:String,summary:String="",open:()->Unit){
 Card(Modifier.fillMaxWidth().clickable(onClick=open),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Pale)){
  Row(Modifier.padding(horizontal=16.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   Column(Modifier.weight(1f)){Text(title,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);if(summary.isNotBlank())Text(summary,Modifier.padding(top=4.dp),fontSize=13.sp,lineHeight=20.sp,color=Muted)}
   Icon(Icons.Outlined.ChevronRight,null,tint=Purple,modifier=Modifier.size(22.dp))
  }
 }
}
@Composable fun FactContent(text:String){
 Column(verticalArrangement=Arrangement.spacedBy(14.dp)){text.split(Regex("\\n\\s*\\n")).filter{it.isNotBlank()}.forEach{paragraph->
  val depth=paragraph.trimStart().takeWhile{it=='#'}.length;val heading=depth>0;val clean=paragraph.trim().replace(Regex("(?m)^#{1,6}\\s+"),"").replace(Regex("(?m)^-\\s+"),"• ").replace("`","")
  Text(clean,fontSize=if(depth>=4)14.sp else if(heading)16.sp else 15.sp,fontWeight=if(heading)FontWeight.SemiBold else FontWeight.Normal,lineHeight=25.sp)
 }}
}
@Composable fun ProfilePage(title:String,close:()->Unit,edit:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
 androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize(),color=Color.White){Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()){
   Row(Modifier.fillMaxWidth().background(Pale).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=close){SketchIcon("back",modifier=Modifier.size(26.dp).semantics{contentDescription="返回"})};Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);if(edit!=null)IconButton(onClick=edit){Icon(Icons.Outlined.Edit,"编辑资料",tint=Accent)}}
   Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){content();Spacer(Modifier.height(12.dp))}
  }}
 }
}

@Composable fun RecruitmentImage(vm:Job27Model,ref:String,thumbnail:Boolean=false){
 val context=LocalContext.current
 var loaded by remember(ref){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
 var failed by remember(ref){mutableStateOf(false)}
 LaunchedEffect(ref){
  val result=withContext(Dispatchers.IO){runCatching{
   val bytes=if(ref.startsWith("data:image/"))Base64.decode(ref.substringAfter(','),Base64.DEFAULT)else{
    val name=ref.substringAfterLast('/');val file=File(context.filesDir,"images/"+name)
    if(file.exists())file.readBytes()else runCatching{context.assets.open("company-media/"+name).use{it.readBytes()}}.getOrElse{vm.request("/media/"+name).also{file.parentFile?.mkdirs();file.writeBytes(it)}}
   }
   val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
   val options=BitmapFactory.Options();while(bounds.outWidth/options.inSampleSize.coerceAtLeast(1)>2048||bounds.outHeight/options.inSampleSize.coerceAtLeast(1)>2048)options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
   BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.asImageBitmap()?:error("图片无法读取")
  }}
  loaded=result.getOrNull();failed=result.isFailure
 }
 val image=loaded
 if(image!=null)Image(image,"公司资料图片",if(thumbnail)Modifier.size(112.dp,76.dp).clip(RoundedCornerShape(6.dp))else Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height),contentScale=if(thumbnail)androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit)
 else if(thumbnail)Box(Modifier.size(112.dp,76.dp).background(Tint),contentAlignment=Alignment.Center){Label(if(failed)"图片暂不可用" else "载入中")}
 else Label(if(failed)"图片暂不可用，可稍后重试" else "正在载入图片")
}

@Composable fun CompanyMaterialsPreview(vm:Job27Model,c:JSONObject,open:()->Unit){
 Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
  Text("公司资料",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Bold)
  Label("${c.arr("links").size} 个链接 · ${c.strings("media").size} 张图片")
  Icon(Icons.Outlined.ChevronRight,null,tint=Purple,modifier=Modifier.size(20.dp))
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CompanyMaterials(vm:Job27Model,c:JSONObject,close:()->Unit){
 val material=companyReference(c);val context=LocalContext.current
 var selectedText by remember(c.s("id")){mutableStateOf<Pair<String,String>?>(null)}
 var selectedLink by remember(c.s("id")){mutableStateOf<JSONObject?>(null)}
 var selectedImage by remember(c.s("id")){mutableStateOf<String?>(null)}
 var notes by remember(c.s("id")){mutableStateOf(c.s("notes"))}
 val texts=buildList{
  if(material.s("description").isNotBlank())add("公司简介" to material.s("description"))
  material.optJSONObject("legacy")?.let{legacy->listOf("location" to "原记录地点","evaluation" to "公司评价","recruitmentNotes" to "招聘记录","jobNotes" to "岗位记录").forEach{(key,label)->
   val value=legacy.s(key);if(value.isNotBlank()&&!material.s("notes").contains(value))add(label to value)
  }}
  add("公司备注" to material.s("notes"))
 }
 ModalBottomSheet(onDismissRequest=close,containerColor=Color.White){
  LazyColumn(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)){
   item{Text("公司资料",fontSize=23.sp,fontWeight=FontWeight.Bold)}
   items(texts){entry->TextButton(onClick={selectedText=entry;if(entry.first=="公司备注")notes=entry.second}){Text(entry.first)}}
   items(material.arr("links")){link->TextButton(onClick={selectedLink=link}){Text(link.s("name").ifBlank{"招聘链接"}+" ↗")}}
   items(material.strings("media").size){index->TextButton(onClick={selectedImage=material.strings("media")[index]}){Text("图片 ${index+1}")}}
  }
 }
 selectedText?.let{entry->val editable=entry.first=="公司备注"
  AlertDialog(onDismissRequest={selectedText=null},title={Text(entry.first)},text={
   if(editable)OutlinedTextField(notes,{notes=it},Modifier.fillMaxWidth().heightIn(max=360.dp).verticalScroll(rememberScrollState()),minLines=4)
   else androidx.compose.foundation.text.selection.SelectionContainer{Text(entry.second,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))}
  },confirmButton={if(editable)TextButton(onClick={vm.action("/companies/${c.s("id")}","PATCH",json("notes" to notes)){selectedText=null}}){Text("保存备注")}else TextButton(onClick={selectedText=null}){Text("关闭")}},dismissButton={if(editable)TextButton(onClick={selectedText=null}){Text("取消")}})
 }
 selectedLink?.let{link->AlertDialog(onDismissRequest={selectedLink=null},title={Text(link.s("name").ifBlank{"招聘链接"})},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(link.s("url"))}},confirmButton={TextButton(onClick={openUrl(context,link.s("url"))}){Text("打开链接")}},dismissButton={TextButton(onClick={selectedLink=null}){Text("关闭")}})}
 selectedImage?.let{ref->AlertDialog(onDismissRequest={selectedImage=null},title={Text("公司资料图片")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){RecruitmentImage(vm,ref)}},confirmButton={TextButton(onClick={selectedImage=null}){Text("关闭")}})}
}

@Composable fun PdfPreview(file:File,close:()->Unit) {
 val count by produceState(0,file) {value=withContext(Dispatchers.IO){runCatching{android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use{it.pageCount}}.getOrDefault(0)}}
 androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
  Surface(Modifier.fillMaxSize(),color=Pale){Column {
   Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text("简历预览",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=20.sp);IconButton(onClick=close){Icon(Icons.Outlined.Close,"关闭预览",tint=Accent)}}
   if(count==0)Empty("正在读取简历…")
   LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(12.dp)) {
    items(count){index->
     val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,file,index){value=withContext(Dispatchers.IO){runCatching{android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer->renderer.openPage(index).use{page->val out=android.graphics.Bitmap.createBitmap(1200,(1200f*page.height/page.width).toInt(),android.graphics.Bitmap.Config.ARGB_8888);out.eraseColor(android.graphics.Color.WHITE);page.render(out,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);out.asImageBitmap()}}}.getOrNull()}}
     if(bitmap!=null)Image(bitmap!!,"简历第${index+1}页",Modifier.fillMaxWidth().background(Color.White))
     Label("${index+1} / $count",Modifier.fillMaxWidth())
    }
   }
  }}
 }
}

fun countValue(count:JSONObject?,key:String):String=if(count==null||!count.has(key)||count.isNull(key))"—" else count.optInt(key).toString()


@Composable fun RefreshTaskDialog(vm:Job27Model,taskId:String,task:JSONObject?,close:()->Unit,openMessages:(String)->Unit,openTask:(String)->Unit,backToBatch:(()->Unit)?=null){
 val companyName=vm.companies.firstOrNull{it.s("id")==task?.s("companyId")}?.s("name").orEmpty().ifBlank{task?.s("companyName").orEmpty()}
 val history=vm.historicalRefresh(task)
 val running=!history&&task?.s("state")=="running"
 val limit=if(!history&&task?.s("state") in listOf("queued","running"))10 else 5
 AlertDialog(
  onDismissRequest=close,
  title={Column{if(backToBatch!=null)TextButton(onClick=backToBatch){SketchIcon("back",modifier=Modifier.size(24.dp));Spacer(Modifier.width(4.dp));Text("返回刷新列表")};if(companyName.isNotBlank()){Text(companyName,fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Accent);Spacer(Modifier.height(6.dp))};Text((if(history)"历史记录 · "else "")+(task?.let{if(!running&&it.s("state")!="queued"&&it.s("stage")=="review_complete")"评分完成"else when(it.s("state")){"complete"->"刷新完成";"partial"->"部分完成";"failed","interrupted"->"刷新未完成";"paused"->"任务暂停";"queued"->"等待刷新";else->"正在刷新"}}?:"刷新任务"))}},
  text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   if(running)Box(Modifier.fillMaxWidth().height(58.dp).padding(vertical=10.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(34.dp),color=Accent)}
   if(!history&&task?.s("transport")=="phone"&&task.s("state")!="complete")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    for(lane in listOf("capture","review")){
     val paused=vm.taskLanePaused(task,lane)
     OutlinedButton(onClick={vm.controlTaskLane(taskId,lane)},enabled=vm.companyRefreshControlEnabled(task?.s("companyId").orEmpty()),modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp,vertical=8.dp)){
      Text((if(paused)"启动" else "暂停")+"岗位"+if(lane=="capture")"抓取" else "评分",fontSize=12.sp,maxLines=1)
     }
    }
   }
   Text(RefreshProgress.overview(task,vm.waitingForNetwork),maxLines=2,overflow=TextOverflow.Ellipsis)
   Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
    RefreshProgress.failureDetails(task).takeIf{it.isNotBlank()}?.let{Text(it,fontSize=13.sp,lineHeight=20.sp)}
    TextButton(onClick={openMessages(taskId)}){Text("查看发送内容与回复")}
    task?.optJSONObject("result")?.let{r->
     Text("新增 ${r.optInt("added")} · 变化 ${r.optInt("changed")} · 已投 ${r.optInt("applied")}")
    }
    task?.arr("events")?.let{RefreshEvents.compact(it)}?.takeLast(limit)?.forEach{Label(date(it.s("at"))+"  "+RefreshEvents.concise(it.s("message")))}
   }
  }},
  confirmButton={TextButton(onClick=close){Text(if(running)"继续浏览" else "关闭")}},
  dismissButton={if(taskId==vm.singleRefreshTaskId&&running)TextButton(onClick={vm.stopSingleRefresh(taskId)},enabled=!vm.singleRefreshStopping){Text(if(vm.singleRefreshStopping)"正在停止…" else "停止刷新")}}
 )
}
