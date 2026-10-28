package chat.plutab.job27
import org.json.*

/** Metadata follows explicit source labels, never the job title or positional header slots. */
internal object JobMetadataFacts {
 private val labels=mapOf("所属部门" to "department","招聘部门" to "department","部门" to "department","Department" to "department","工作地点" to "location","工作城市" to "location","工作地址" to "location","招聘城市" to "location","工作地区" to "location","Location" to "location","学历要求" to "education","学历" to "education","Education" to "education","工作性质" to "employmentType","职位性质" to "employmentType","Employment type" to "employmentType","职位编号" to "jobCode","岗位编号" to "jobCode","职位编码" to "jobCode","Job ID" to "jobCode","招聘类型" to "recruitmentType")
 private val boundaries=labels.keys+setOf("职位名称","发布日期","发布时间","职位类别","职位信息","工作经验","经验要求","所属公司","招聘单位","招聘人数","薪资范围","薪资待遇")
 private val cities=listOf("北京","上海","天津","重庆","广州","深圳","杭州","宁波","南京","苏州","无锡","常州","成都","武汉","西安","长沙","郑州","合肥","济南","青岛","烟台","石家庄","哈尔滨","长春","沈阳","大连","福州","厦门","珠海","佛山","中山","东莞","惠州","南昌","南宁","昆明","贵阳","海口","太原","兰州","香港","澳门","台北")
 private val tags=Regex("<[^>]*>")
 private fun clean(s:String)=s.replace(tags," ").replace("&nbsp;"," ").replace('\u00a0',' ').trim()
 private val employmentPattern=Regex("全职|兼职|实习|正式|正式员工|full[ -]?time|part[ -]?time|intern(?:ship)?",RegexOption.IGNORE_CASE)
 fun employment(s:String)=employmentPattern.matches(clean(s))
 private val educationPattern=Regex("(?:博士|硕士|本科|大专|中专|高中|博士研究生|硕士研究生)(?:及以上|以上|及以下)?|学历不限|不限学历|Ph[.]?D[.]?|Master'?s?|Bachelor'?s?",RegexOption.IGNORE_CASE)
 fun education(s:String)=educationPattern.matches(clean(s))
 private val codePattern=Regex("(?:MJ|JR|REQ|J)[- ]?[0-9]{3,}",RegexOption.IGNORE_CASE)
 fun code(s:String)=codePattern.matches(clean(s))
 private val companyPattern=Regex("公司|集团|分公司|事业单位|Corporation|Co[., ]*Ltd",RegexOption.IGNORE_CASE)
 private fun company(s:String)=companyPattern.containsMatchIn(s)
 private val nonPlace=Regex("部|中心|研究院|实验室|平台|团队|研究员|科学家|工程师|Department|Research|Lab|Team|Division|Centre|Center",RegexOption.IGNORE_CASE)
 fun place(s:String)=!nonPlace.containsMatchIn(s)&&(s=="全国"||cities.any{s.contains(it)}||Regex("[\\p{IsHan}]{2,8}(?:省|市|自治区|特别行政区)(?:$|[ ·,，/、])").containsMatchIn(s))
 fun location(raw:String):String {
  val s=clean(raw)
  if(s in listOf("","未知","地点待核实","待核实")||employment(s)||education(s)||code(s))return ""
  if(company(s))return cities.filter{s.contains(it)}.joinToString(" · ")
  return s
 }
 fun header(parts:List<String>):JSONObject=JSONObject().apply{
  for(raw in parts){val v=clean(raw);when{employment(v)->put("employmentType",v);education(v)->put("education",v);code(v)->put("jobCode",v);place(v)->put("location",location(v))}}
 }
 private val sectionStart=Regex("(?:职位描述|岗位职责|工作职责|任职要求|任职资格|Job description|Responsibilities)(?:[:：].*)?",RegexOption.IGNORE_CASE)
 fun labelled(body:String):JSONObject {
  val lines=body.take(16000).lineSequence().map{clean(it)}.filter{it.isNotBlank()}.takeWhile{!sectionStart.matches(it.trimEnd(':','：'))}.toList()
  val result=JSONObject()
  for((i,line)in lines.withIndex()){
   val key=line.substringBefore('：').substringBefore(':').trim();val field=labels.entries.firstOrNull{it.key.equals(key,true)}?.value?:continue
   var value=if(line.contains('：'))line.substringAfter('：').trim()else if(line.contains(':'))line.substringAfter(':').trim()else ""
   if(value.isBlank())for(next in lines.drop(i+1).take(4)){
    if(next.equals(key,true))continue
    if(boundaries.any{it.equals(next.trimEnd(':','：'),true)}||next.contains('：')||next.contains(':'))break
    value=next;break
   }
   if(value.isNotBlank()&&!result.has(field))result.put(field,value)
  }
  if(result.has("department"))result.put("departmentEvidence","official-labelled-field")
  return result
 }
 fun normalize(job:JSONObject,body:String=""):Boolean {
  var changed=false;val values=labelled(body)
  val archived=job.optJSONObject("metadataSourceValues")?:JSONObject()
  fun set(field:String,value:String){val old=job.s(field);if(old==value)return;if(old.isNotBlank()&&!archived.has(field))archived.put(field,old);job.put(field,value);changed=true}
  for(field in listOf("department","location","education","employmentType","jobCode","recruitmentType"))if(values.has(field)&&!(field=="department"&&(job.optBoolean("manualDepartment")||job.optBoolean("manual")))&&!(field=="location"&&job.optBoolean("manual")))set(field,values.s(field))
  if(values.has("departmentEvidence")&&job.s("departmentEvidence")!=values.s("departmentEvidence")){job.put("departmentEvidence",values.s("departmentEvidence"));changed=true}
  if(!job.optBoolean("manualDepartment")&&!job.optBoolean("manual")){
   val d=clean(job.s("department"))
   val host=runCatching{java.net.URI(job.s("url")).host}.getOrNull()
   val categoryOnly=job.s("departmentEvidence").isBlank()&&(host in listOf("xyz.51job.com","www.zhaopin.com","jobs.boehringer-ingelheim.com","www.novonordisk.com")||d=="研发类博士招聘")
   when{
    categoryOnly&&d.isNotBlank()->{set("jobCategory",d);set("department","")}
    employment(d)->{set("employmentType",d);set("department","")}
    education(d)->{set("education",d);set("department","")}
    code(d)->{set("jobCode",d);set("department","")}
    place(d)||company(d)->{if(job.s("location").isBlank())set("location",location(d));set("department","")}
   }
  }
  if(!job.optBoolean("manual")){
   val l=job.s("location");if(employment(l))set("employmentType",l);if(education(l))set("education",l);if(code(l))set("jobCode",l)
   set("location",location(l))
  }
  if(archived.length()>0)job.put("metadataSourceValues",archived)
  return changed
 }
 fun repairSnapshot(snapshot:JSONObject):Boolean {var changed=false;for(job in snapshot.arr("jobs")){
  val body=(job.opt("description") as? String).orEmpty()
  if(normalize(job,body))changed=true
 };return changed}
}
