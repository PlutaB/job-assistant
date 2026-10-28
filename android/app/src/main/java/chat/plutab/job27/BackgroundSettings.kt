package chat.plutab.job27

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

object BackgroundSettings {
 fun isXiaomi()=listOf(Build.MANUFACTURER,Build.BRAND).any{it.lowercase() in listOf("xiaomi","redmi","poco")}
 fun batteryAllowed(context:Context)=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
 fun notificationsAllowed(context:Context)=context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
 fun restricted(context:Context)=Build.VERSION.SDK_INT>=28&&context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
 private fun appInfo(context:Context)=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))
 private fun open(context:Context,intents:List<Intent>):Boolean{
  for(intent in intents){try{context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true}catch(_:RuntimeException){}}
  return false
 }
 fun autostart(context:Context):Boolean{
  val candidates=mutableListOf<Intent>()
  if(isXiaomi())candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity")).putExtra("package_name",context.packageName))
  candidates.add(appInfo(context));return open(context,candidates)
 }
 fun requestBattery(context:Context)=open(context,listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${context.packageName}")),Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),appInfo(context)))
 fun manageBattery(context:Context)=open(context,listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
 fun batterySettings(context:Context)=open(context,listOf(appInfo(context)))
 fun notifications(context:Context)=open(context,listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName),appInfo(context)))
}

@Composable fun BackgroundSettingsPanel(onMessage:(String)->Unit){
 val context=LocalContext.current
 val lifecycleOwner=LocalLifecycleOwner.current
 var revision by remember{mutableIntStateOf(0)}
 DisposableEffect(lifecycleOwner){
  val lifecycle=lifecycleOwner.lifecycle
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
  lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
 }
 val battery=remember(revision){BackgroundSettings.batteryAllowed(context)}
 val notices=remember(revision){BackgroundSettings.notificationsAllowed(context)}
 val restricted=remember(revision){BackgroundSettings.restricted(context)}
 fun result(ok:Boolean){if(!ok)onMessage("系统未提供此入口，请在系统设置中找到求职助手的应用信息")}
 Block("后台刷新"){
  Text("用于你主动启动的岗位刷新。任务结束后停止后台服务；切换页面或息屏时继续保存进度。",fontSize=14.sp)
  ProfileEntry("自启动管理",if(BackgroundSettings.isXiaomi())"在系统中允许求职助手后台自启动；需手动确认" else "在应用信息中检查后台启动设置"){result(BackgroundSettings.autostart(context))}
  Text("自启动开关需在系统页面确认，应用无法直接读取。",fontSize=12.sp,color=Muted)
  ProfileEntry("系统电池优化",if(battery)"已豁免 · 点击打开系统电池优化管理" else "尚未豁免 · 点击打开系统电池优化管理"){result(BackgroundSettings.manageBattery(context))}
  if(!battery)TextButton(onClick={result(BackgroundSettings.requestBattery(context))}){Text("申请允许后台运行")}
  ProfileEntry("应用省电策略",if(restricted)"后台受限 · 进入应用信息修改省电策略" else "进入应用信息 → 省电策略，小米请选择“无限制”"){result(BackgroundSettings.batterySettings(context))}
  ProfileEntry("刷新通知",if(notices)"已允许通知" else "通知已关闭，点击开启"){result(BackgroundSettings.notifications(context))}
  Text("运行保护：严重过热、未充电且电量低于10%，或内存接近上限时，自动暂停并保存断点；高负载时自动放慢。",fontSize=12.sp,color=Muted)
  Text("设置完成返回后会重新读取电池和通知状态。自启动及省电策略请以系统页面为准。",fontSize=12.sp,color=Muted)
 }
}
