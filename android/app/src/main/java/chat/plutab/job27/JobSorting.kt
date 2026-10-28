package chat.plutab.job27

import org.json.JSONObject

/** Left-to-right sort priority is fixed; each direction is independently reversible. */
object JobSorting {
 private val statusOrder=listOf("拒绝","未投","已投","AI测评","笔试","1面","2面","3面","4面","Offer")
 fun applied(job:JSONObject)=job.s("appliedAt").isNotBlank()||job.s("status") !in listOf("","未投","未")
 private fun compare(a:Double?,b:Double?,descending:Boolean):Int=when{a==null&&b==null->0;a==null->1;b==null->-1;descending->b.compareTo(a);else->a.compareTo(b)}
 fun sort(jobs:List<JSONObject>,companies:List<JSONObject>,gradeDescending:Boolean=true,companyDescending:Boolean=true,appliedDescending:Boolean=true,statusDescending:Boolean=true):List<JSONObject>{
  val companyOrder=companies.mapIndexed{index,company->company.s("id") to CompanyScores.value(company,index,companies.size)}.toMap()
  fun grade(j:JSONObject)=when(j.s("grade")){"S"->4.0;"A"->3.0;"B"->2.0;"U"->1.0;else->null}
  fun status(j:JSONObject)=statusOrder.indexOf(j.s("status").let{if(it.isBlank()||it=="未")"未投"else it}).takeIf{it>=0}?.toDouble()
  return jobs.sortedWith(Comparator{a,b->
   compare(status(a),status(b),statusDescending).takeIf{it!=0}
    ?:compare(grade(a),grade(b),gradeDescending).takeIf{it!=0}
    ?:compare(companyOrder[a.s("companyId")],companyOrder[b.s("companyId")],companyDescending).takeIf{it!=0}
    ?:compare(if(applied(a))1.0 else 0.0,if(applied(b))1.0 else 0.0,appliedDescending).takeIf{it!=0}
    ?:b.s("firstSeenAt").compareTo(a.s("firstSeenAt")).takeIf{it!=0}
    ?:a.s("id").compareTo(b.s("id"))
  })
 }
}
