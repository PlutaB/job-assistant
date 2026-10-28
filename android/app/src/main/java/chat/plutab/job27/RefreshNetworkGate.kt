package chat.plutab.job27
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class RefreshNetworkGate(
 private val online:()->Boolean,
 private val waitOnline:suspend()->Unit={delay(1000)},
 private val changed:suspend(Boolean)->Unit={}
):AbstractCoroutineContextElement(Key){
 companion object Key:CoroutineContext.Key<RefreshNetworkGate>
 fun offline()=!online()
 suspend fun awaitOnline(){if(online())return;changed(true);try{while(!online()){currentCoroutineContext().ensureActive();waitOnline()}}finally{changed(false)}}
}
