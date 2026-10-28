package chat.plutab.job27

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable fun TaskMessages(vm:Job27Model,companyId:String,initialTaskId:String?=null,close:()->Unit){
 var selected by remember{mutableStateOf(initialTaskId)}
 val tasks=vm.tasks.filter{it.s("companyId")==companyId}
 var messages by remember(selected){mutableStateOf(emptyList<JSONObject>())}
 LaunchedEffect(selected,vm.messageVersion){messages=selected?.let{runCatching{vm.taskMessages(it)}.getOrDefault(emptyList())}?:emptyList()}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(color=androidx.compose.ui.graphics.Color.White){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=16.dp)){
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={if(selected!=null)selected=null else close()}){Text(if(selected!=null)"返回任务列表" else "关闭")};Text("刷新记录与 GPT 消息",Modifier.padding(top=12.dp),fontSize=17.sp)}
   LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
    if(selected==null){
     if(tasks.isEmpty())item{Empty("尚无刷新记录")}
     items(tasks,key={it.s("id")}){task->Card(Modifier.fillMaxWidth().clickable{selected=task.s("id")},colors=CardDefaults.cardColors(containerColor=Pale)){
      Column(Modifier.padding(14.dp)){Text((if(vm.historicalRefresh(task))"历史记录 · "else "当前刷新 · ")+date(task.s("createdAt")));Text(task.s("message"),fontSize=14.sp);Label(task.s("state"))}
     }}
    }else{
     val task=tasks.find{it.s("id")==selected}
     item{if(vm.historicalRefresh(task))Label("历史记录，不代表当前刷新");Text(task?.s("message")?:"任务记录",fontSize=14.sp)}
     if(messages.isEmpty())item{Text("本任务尚无可用消息记录。旧版本任务未保存完整消息；Python 抓取阶段、资料不足或沿用旧评级时也不会发送请求。",fontSize=14.sp)}
     items(messages,key={it.s("id")}){message->var expanded by remember(message.s("id")){mutableStateOf(false)}
      var payload by remember(message.s("id")){mutableStateOf<Any?>(null)}
      LaunchedEffect(expanded){payload=if(expanded)runCatching{vm.taskMessagePayload(selected!!,message.s("id"))}.getOrNull()else null}
      Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Pale)){
       Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(when(message.s("role")){"request"->if(message.s("method")=="thread/start")"GPT 系统要求与会话配置" else "实际发送给 GPT 的消息";"assistant"->"GPT 原始回复";"error"->"错误记录";else->"执行记录"},fontSize=15.sp)
        Label(date(message.s("at"))+" · "+when(message.s("status")){"accepted"->"已受理";"sending"->"正在提交";"received"->"已收到";"saved"->"已保存";"declined"->"已拒绝工具调用";"error"->"失败";else->message.s("status")})
        TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起完整内容" else "展开完整内容")}
        if(expanded)SelectionContainer{Text((payload as? JSONObject)?.toString(2)?:payload?.toString()?:"正在读取内容…",fontSize=12.sp)}
       }
      }
     }
    }
    item{Spacer(Modifier.height(20.dp))}
   }
  }}
 }
}
