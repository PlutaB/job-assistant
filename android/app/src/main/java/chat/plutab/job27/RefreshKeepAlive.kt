package chat.plutab.job27
import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

/** Owns refresh execution, wakefulness and restart; the journal is the durable source of work. */
class RefreshKeepAlive:Service(){
 private var wake:PowerManager.WakeLock?=null
 private val renewal=android.os.Handler(android.os.Looper.getMainLooper())
 private var lastText=""
 private val progress=object:Runnable{override fun run(){
  val model=(application as Job27Application).jobs
  val text=if(model.waitingForNetwork)"等待网络恢复，断点保留；联网后继续"else RefreshProgress.notification(model.snapshot)
  if(text!=lastText){lastText=text;getSystemService(NotificationManager::class.java).notify(2701,notification(text))}
  if(!(application as Job27Application).jobs.refreshNetworkAvailable)wake?.let{if(it.isHeld)it.release()}
  (application as Job27Application).jobs.inspectRefreshResources{wake?.let{if(it.isHeld)it.release()}}
  renewal.postDelayed(this,5000)
 }}
 private fun notification(text:String):Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return Notification.Builder(this,"job27-refresh").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("求职助手正在刷新").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true).build()
 }
 private val renewWake=object:Runnable{override fun run(){if(RefreshBudgetPolicy.currentReason(this@RefreshKeepAlive,checkHeap=false)==null&&(application as Job27Application).jobs.busy&&(application as Job27Application).jobs.refreshNetworkAvailable)wake?.acquire(30000L);renewal.postDelayed(this,15000L)}}
 override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("job27-refresh","岗位刷新",NotificationManager.IMPORTANCE_LOW))
  startForeground(2701,notification("正在读取任务断点"));renewal.post(progress)
  wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"job27:refresh").apply{setReferenceCounted(false);acquire(30000L)}
  renewal.postDelayed(renewWake,15000L)
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{(application as Job27Application).jobs.serviceStartRefresh(intent?.getStringExtra("companyId")){stopSelf()};return START_STICKY}
 override fun onTimeout(startId:Int,fgsType:Int){(application as Job27Application).jobs.pauseRefresh();stopSelf()}
 override fun onDestroy(){renewal.removeCallbacksAndMessages(null);(application as Job27Application).jobs.pauseRefresh();wake?.let{if(it.isHeld)it.release()};super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
}
