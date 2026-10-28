package chat.plutab.job27

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun DirectGptPanel(gpt:DirectChatGpt){
 val context=LocalContext.current;var showAudit by remember{mutableStateOf(false)}
 Block("账户与模型"){
  Text(gpt.state,fontSize=14.sp)
  Label("登录后选择用于岗位定级的模型。")
  if(gpt.accounts.isNotEmpty())Choice("ChatGPT账户",gpt.accounts.map{it.s("label")},gpt.active?.s("label").orEmpty()){label->gpt.accounts.find{it.s("label")==label}?.let{gpt.selectAccount(it.s("client_id"))}}
  if(gpt.busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Purple)
  if(!gpt.signedIn)Button(onClick={gpt.signIn()},enabled=!gpt.busy,colors=ButtonDefaults.buttonColors(containerColor=Color.Black,contentColor=Color.White)){Text("Continue with ChatGPT")}
  if(gpt.signedIn&&!gpt.planEnabled)Button(onClick={gpt.signIn(consent=true)},enabled=!gpt.busy){Text("启用 ChatGPT 套餐权限")}
  if(gpt.loggingIn)TextButton(onClick={gpt.cancelLogin()}){Text("取消此次登录")}
  if(gpt.accounts.isNotEmpty())TextButton(onClick={gpt.signIn(savedClient=null)},enabled=!gpt.busy){Text("添加其他 ChatGPT 账户或工作区")}
  if(gpt.planEnabled){
   TextButton(onClick={gpt.loadModels()},enabled=!gpt.busy){Text("读取可用模型")}
   if(gpt.models.isNotEmpty())Choice("使用模型",gpt.models.map{it.s("display_name",it.s("slug"))+" · "+it.s("slug")},gpt.models.find{it.s("slug")==gpt.model}?.let{it.s("display_name",it.s("slug"))+" · "+it.s("slug")}.orEmpty()){value->gpt.models.find{value.endsWith(" · "+it.s("slug"))}?.let{gpt.selectModel(it.s("slug"))}}
   Label("测试将发送：这是求职助手App的连接测试。只回复：连接成功。不进行岗位抓取，不需要使用工具。")
   Button(onClick={gpt.testConnection()},enabled=!gpt.busy&&gpt.model.isNotBlank()){Text("发送一次连接测试")}
  }
  if(gpt.lastAudit.length()>0)TextButton(onClick={showAudit=!showAudit}){Text(if(showAudit)"收起实际消息与回复" else "查看实际消息与回复")}
  if(showAudit)SelectionContainer{Text(gpt.lastAudit.toString(2),fontSize=12.sp)}
  if(gpt.signedIn)TextButton(onClick={gpt.signOut()},enabled=!gpt.busy){Text("退出 ChatGPT")}
  Label("授权在官方浏览器中完成，登录凭据加密保存在本机。")
  TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com/settings/usage")))}){Text("查看 ChatGPT 套餐用量与应用权限")}
 }
}

@Composable fun DirectGptDialog(gpt:DirectChatGpt,close:()->Unit){
 androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
  Surface(color=Color.White){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
   TextButton(onClick=close){Text("返回个人信息")}
   Column(Modifier.weight(1f).padding(horizontal=16.dp).then(Modifier.verticalScroll(rememberScrollState())),verticalArrangement=Arrangement.spacedBy(12.dp)){DirectGptPanel(gpt);Spacer(Modifier.height(16.dp))}
  }}
 }
}
