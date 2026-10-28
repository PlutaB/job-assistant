package chat.plutab.job27
import android.app.Application

/** The refresh runtime belongs to the process/service, never to an Activity's ViewModelStore. */
class Job27Application:Application(){override fun onCreate(){super.onCreate();RuntimeDiagnostics.install(this)};val jobs:Job27Model by lazy{Job27Model(this)}}
