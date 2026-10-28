package chat.plutab.job27
import org.json.JSONObject

/** Completion of ratings must never implicitly start a new capture task on resume. */
object RefreshResume {
 fun canAutoResume(companyId:String,batch:JSONObject?,journalState:String?):Boolean=journalState in listOf("queued","running")&&(batch==null||batch.s("state")=="running"||RefreshBatchQueue.entries(batch).none{it.s("companyId")==companyId})
 fun prepare(company:JSONObject,old:JSONObject?,newRound:Boolean=false):JSONObject =
  if(old!=null&&(!newRound||!old.optBoolean("fullyComplete")))old
  else RefreshJournal.new(company).apply{put("allowOldCache",old==null)}
}
