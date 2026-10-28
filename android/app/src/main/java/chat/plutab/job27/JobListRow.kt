package chat.plutab.job27

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter


private fun compactCompanyName(name:String):String=when(name){
 "葛兰素史克"->"GSK"
 "勃林格殷格翰"->"勃林格"
 "艾力斯医药"->"艾力斯"
 "中美华东医药"->"华东医药"
 "正大天晴药业"->"正大天晴"
 "天士力生物"->"天士力"
 "扬子江药业"->"扬子江"
 else->name.codePoints().toArray().take(4).joinToString(""){String(Character.toChars(it))}
}

private val compactTextStyle=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false))
private fun publishedDay(value:String)=if(value.isBlank())"未知" else runCatching{
 LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofPattern("yy/MM/dd"))
}.getOrDefault("未知")

@Composable private fun JobMetadata(kind:String,value:String,modifier:Modifier=Modifier){
 Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){
  SketchIcon(kind,modifier=Modifier.size(if(kind=="job_department")9.dp else 12.dp,14.dp))
  Text(value,color=Muted,fontSize=12.sp,lineHeight=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,style=compactTextStyle)
 }
}

/** User reference: logo above company name; two-line title above a single metadata line. */
@Composable fun JobListRow(job:JSONObject,company:JSONObject?,showCompany:Boolean,open:()->Unit){
 Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  if(showCompany)Column(Modifier.width(68.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)){
   Logo(company?.s("name")?:"岗",company?.s("logo")?:"",40)
   Text(compactCompanyName(company?.s("name")?:""),color=Muted,fontSize=11.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,style=compactTextStyle)
  }
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
   Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
    if(job.s("newInTask").isNotBlank()&&job.s("newInTask")==company?.s("latestTask"))Badge("新",Color(0xFFFFEEEE),Color(0xFFD85B70))
    Text(job.s("title"),Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=14.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis,style=compactTextStyle)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){StatusBadge(job.s("status"));Grade(job)}
    Icon(Icons.Outlined.ChevronRight,null,tint=Purple,modifier=Modifier.size(20.dp))
   }
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
    JobMetadata("job_location",job.s("location").ifBlank{"未知"},Modifier.widthIn(max=104.dp))
    JobMetadata("job_time",publishedDay(job.s("publishedAt")))
    if(job.s("department").isNotBlank())JobMetadata("job_department",job.s("department"),Modifier.weight(1f,fill=false))
   }
  }
 }
}
