package chat.plutab.job27
import org.json.JSONObject

fun hasRefreshWarning(company:JSONObject)=company.optBoolean("refreshWarning")||company.s("lastResultState") in listOf("partial","failed","interrupted")
fun applyRefreshWarning(company:JSONObject,state:String,message:String){
 if(state in listOf("partial","failed","interrupted"))company.put("refreshWarning",true).put("refreshError",message)
 if(state=="complete"){company.remove("refreshWarning");company.remove("refreshError");company.put("lastResultState","complete")}
}
