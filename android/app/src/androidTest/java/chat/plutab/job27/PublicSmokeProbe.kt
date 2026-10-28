package chat.plutab.job27
import android.app.*
import android.os.Bundle
import kotlinx.coroutines.*
class PublicSmokeProbe:Instrumentation(){
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
 override fun onStart(){val result=Bundle();try{runBlocking{
  val app=targetContext.applicationContext as Job27Application;val vm=app.jobs
  (Job27Model::class.java.getDeclaredField("dataReady").apply{isAccessible=true}.get(vm) as CompletableDeferred<*>).await()
  check(vm.companies.isEmpty()&&vm.jobs.isEmpty()&&vm.resumes.isEmpty())
  for(key in listOf("name","email","phone","education","experience","skills","preferences","photo"))check(vm.profile.s(key).isBlank())
  val activity=startActivitySync(android.content.Intent(app,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));delay(1000)
  fun nodes(n:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
  fun tree():List<android.view.accessibility.AccessibilityNodeInfo>{uiAutomation.clearCache();return nodes(uiAutomation.rootInActiveWindow)}
  fun click(label:String){var n=tree().first{it.text?.toString()==label||it.contentDescription?.toString()==label};while(!n.isClickable&&n.parent!=null)n=n.parent;check(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))}
  fun shot(name:String){val b=uiAutomation.takeScreenshot()!!;java.io.File(app.filesDir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
  delay(2000);uiAutomation.waitForIdle(500,5000);shot("public-empty.png");check(tree().any{it.text?.toString()=="求职助手"}){tree().mapNotNull{it.text?.toString()}.joinToString("|")}
  click("设置");delay(800);check(tree().any{it.text?.toString()=="作者 PlutaB"});check(tree().any{it.text?.toString()=="github.com/PlutaB"});shot("public-author.png")
  result.putString("report","Blank application starts; companies, jobs, profile and resumes empty; independent package; author visible in settings. No network or GPT requests.")
  runOnMainSync{activity.finish()}
 };finish(-1,result)}catch(e:Throwable){result.putString("error",e.stackTraceToString());finish(0,result)}}
}
