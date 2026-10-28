package chat.plutab.job27

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable fun BatchRefreshDialog(batch:JSONObject,tasks:List<JSONObject>,close:()->Unit,pause:()->Unit,resume:()->Unit,openTask:(JSONObject)->Unit,controlLane:(String)->Unit={},controlCompany:(String)->Unit={},scrollState:androidx.compose.foundation.ScrollState=rememberScrollState(),canControlCompany:(String)->Boolean={true},controlsEnabled:Boolean=true){
 val entries=RefreshBatchQueue.entries(batch);val statuses=entries.associate{it.s("companyId") to RefreshProgress.rowStatus(it,tasks,batch.optBoolean("quotaBlocked"))}
 val done=entries.count{it.s("review")=="done"};val ok=statuses.values.count{it.complete};val warning=statuses.values.count{it.warning};val active=batch.s("state")=="running"

 AlertDialog(onDismissRequest=close,title={Text(if(active)"刷新全部" else if(batch.s("state")=="complete")"全部刷新完成" else "批量刷新记录")},text={
  Column(Modifier.heightIn(max=440.dp).verticalScroll(scrollState),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("评分已处理 $done/${entries.size} · 全部完成 $ok · ⚠️ $warning",fontWeight=FontWeight.SemiBold)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    for(lane in listOf("capture","review")){
     val running=active&&!batch.optBoolean(lane+"Paused")&&!(lane=="review"&&batch.optBoolean("quotaBlocked"))
     OutlinedButton(onClick={controlLane(lane)},enabled=controlsEnabled,modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=6.dp,vertical=8.dp)){
      Text((if(running)"暂停" else "启动")+"岗位"+(if(lane=="capture")"抓取" else "评分"),fontSize=12.sp)
     }
    }
   }
   Label("岗位抓取与岗位评分独立控制；公司暂停后保留断点，其他公司继续。")
   if(active&&batch.optBoolean("networkWaiting"))Label("等待网络恢复 · 断点保留，不消耗重试次数")
   batch.s("error").takeIf{it.isNotBlank()}?.let{Text(it,color=Accent,fontSize=12.sp)}
   for((i,item) in entries.withIndex()){
    val status=statuses.getValue(item.s("companyId"))
    Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
     val working=tasks.any{it.s("id")==item.s("taskId")&&it.s("state")=="running"}
     val refreshing=working&&!batch.optBoolean("networkWaiting")
     Text("${i+1}. ${item.s("name")}",Modifier.weight(1f).padding(end=6.dp).clickable{openTask(item)},fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
     if(!status.complete)Text((if(status.warning)"⚠ " else "")+status.text,Modifier.widthIn(max=158.dp).clickable{openTask(item)},color=if(status.warning)statusColor("AI测评") else Muted,fontSize=11.sp,textAlign=TextAlign.End,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis)
     Box(Modifier.size(28.dp),contentAlignment=androidx.compose.ui.Alignment.Center){
      IconButton(onClick={if(status.complete)openTask(item)else controlCompany(item.s("companyId"))},enabled=status.complete||canControlCompany(item.s("companyId")),modifier=Modifier.fillMaxSize()){
       when{
        status.complete->Icon(Icons.Default.Check,contentDescription=item.s("name")+"已完成",modifier=Modifier.size(20.dp),tint=Color(0xFF388E3C))
        refreshing->CircularProgressIndicator(Modifier.size(16.dp).semantics{contentDescription=item.s("name")+"刷新中"},strokeWidth=1.25.dp,color=RefreshIconColor)
        else->SketchIcon("refresh",Purple,Modifier.size(16.dp).semantics{contentDescription="刷新该公司 "+item.s("name")})
       }
      }
     }
    }

   }
  }
 },confirmButton={TextButton(onClick=close){Text(if(active)"继续浏览" else "关闭")}},dismissButton={
  if(active)TextButton(onClick=pause){Text("暂停全部")}
 })
}
