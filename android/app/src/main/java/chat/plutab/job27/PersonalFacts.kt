package chat.plutab.job27
import org.json.JSONObject
import org.json.JSONArray

/** Add the master file once; keep all existing profile fields and later edits. */
object PersonalFacts{
 fun upgrade(profile:JSONObject,source:JSONObject):JSONObject=JSONObject(profile.toString()).apply{if(!has("factSections")){put("factSections",JSONArray(source.getJSONArray("sections").toString()));put("factsSource",source.s("source"));put("factsVersion",1)}}
 fun ratingContext(p:JSONObject)=json("education" to p.s("education"),"experience" to p.s("experience"),"skills" to p.s("skills"),"preferences" to p.s("preferences"),"preferenceVersion" to p.optInt("preferenceVersion",1),"facts" to JSONArray(p.arr("factSections").filter{it.s("group")!="basic"}.map{json("title" to it.s("title"),"content" to it.s("content"))}))

 private fun chapters(rows:List<JSONObject>):List<Pair<String,String>> = rows.flatMap{r->Regex("(?m)^### (.+)$").findAll(r.s("content")).toList().let{matches->matches.mapIndexed{i,m->m.groupValues[1] to r.s("content").substring(m.range.last+1,matches.getOrNull(i+1)?.range?.first?:r.s("content").length)}}}
 fun identity(rows:List<JSONObject>):String=chapters(rows).firstOrNull()?.let{(title,_)->title.split('，').take(2).joinToString("")+(if(title.contains("至今"))"在读"else "")}.orEmpty()
 fun overview(group:String,rows:List<JSONObject>):String{if(rows.isEmpty())return "待补充";return when(group){
  "education"->chapters(rows).joinToString("\n"){(title,_)->title.split('，').take(2).joinToString("，").replace("理学硕士","硕士").replace("工学学士","学士")}
  "research"->rows.take(3).joinToString("\n"){it.s("title").substringAfter('：').substringBefore('，')}
  "internships"->chapters(rows).joinToString("\n"){(title,_)->title.split('，').take(2).joinToString("，").replace("生物医药股份有限公司","").removePrefix("杭州")}
  "leadership"->chapters(rows).mapNotNull{(title,body)->val role=Regex("(?m)^- 职务：(.+)$").find(body)?.groupValues?.get(1)?.trimEnd('。');role?.let{(if(title.contains("学生组织"))"学生组织"else title.substringBefore('，'))+"，"+it}}.joinToString("\n")
  "cases"->rows.flatMap{it.s("content").lines()}.filter{it.startsWith("- ")}.joinToString("\n"){it.removePrefix("- ").substringBefore('：')}
  else->rows.joinToString("\n"){brief(it.s("content"))}
 }}
 fun skillsOverview(profile:JSONObject,full:Boolean=false):String{
  val facts=profile.arr("factSections").filter{it.s("group")!="basic"}.joinToString("\n"){it.s("content")}+"\n"+profile.s("skills")
  val groups=listOf(
   "实验" to listOf("细胞培养" to "细胞培养","RNAi" to "RNA干扰|RNAi|RNA干预","流式" to "流式","WB" to "Western blot|\\bWB\\b","共聚焦" to "共聚焦","GP/RICS" to "GP/RICS","CCK-8" to "CCK-8","IncuCyte" to "IncuCyte"),
   "生化" to listOf("EMSA" to "EMSA","核酸酶" to "核酸酶","纯化" to "纯化","SPR" to "SPR","质量光度" to "质量光度"),
   "分析与工具" to listOf("HPLC" to "HPLC","Swift" to "Swift","Excel" to "Excel"))
  val result=groups.map{(label,items)->
   val matched=items.filter{Regex(it.second,RegexOption.IGNORE_CASE).containsMatchIn(facts)}
   val shown=if(full)matched else matched.take(4)
   label+"："+shown.joinToString("、"){it.first}.ifBlank{"待补充"}+(if(shown.size<matched.size)"…等"else "")
  }
  return result.joinToString("\n")
 }
 fun languages(profile:JSONObject)=profile.arr("factSections").filter{it.s("group")=="basic"}.flatMap{it.s("content").lines()}.filter{it.contains("中文：")||it.contains("英语：")||it.contains("德语：")}.joinToString("\n"){it.removePrefix("- ")}

 fun brief(text:String)=text.lineSequence().map{it.trim().replace(Regex("^[-#*\\s]+"),"")}.firstOrNull{it.isNotBlank()}.orEmpty().take(64)
}
