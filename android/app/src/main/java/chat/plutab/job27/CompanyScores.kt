package chat.plutab.job27
import org.json.JSONObject

/** Default score = reversed one-based company rank + 50. Manual scores survive refreshes. */
object CompanyScores {
 fun valid(score:Double)=score.isFinite()&&score>=0
 fun value(company:JSONObject,index:Int,total:Int):Double {
  val manual=company.opt("score")?.toString()?.toDoubleOrNull()
  return if(company.optBoolean("manualScore")&&manual!=null&&valid(manual))manual else (total-index+50).toDouble()
 }
 fun ensure(snapshot:JSONObject):Boolean {
  val companies=snapshot.arr("companies");var changed=false
  companies.forEachIndexed{index,c->val score=value(c,index,companies.size)
   if(c.optDouble("score",Double.NaN)!=score){c.put("score",score);changed=true}
  };return changed
 }
 fun edit(snapshot:JSONObject,id:String,score:Double?){
  require(score==null||valid(score)){"请输入有效的非负评分"}
  val c=snapshot.arr("companies").firstOrNull{it.s("id")==id}?:error("公司不存在")
  c.put("manualScore",score!=null);if(score!=null)c.put("score",score);ensure(snapshot)
 }
 fun display(score:Double)=score.toString().removeSuffix(".0")
}
