package chat.plutab.job27

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.remember
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Outline geometry follows the three icons in the user's original navigation drawing. */
val RefreshIconColor=Color(0xFFAFABFF)

@Composable fun SketchIcon(kind:String,tint:Color=Color.White,modifier:Modifier=Modifier) {
 val original=when(kind){
  "job_location"->R.drawable.user_job_location;"job_time"->R.drawable.user_job_time;"job_department"->R.drawable.user_job_department
  "company"->R.drawable.user_company;"jobs"->R.drawable.user_jobs;"profile"->R.drawable.user_profile
  "refresh"->R.drawable.user_refresh;"clock"->R.drawable.user_job_time;"back"->R.drawable.user_back;"search"->R.drawable.user_search
  else->null
 }
 if(original!=null){Image(painterResource(original),contentDescription=null,modifier=modifier,contentScale=ContentScale.Fit,colorFilter=if(kind=="refresh")androidx.compose.ui.graphics.ColorFilter.tint(RefreshIconColor)else null);return}
 Canvas(modifier) {
  withTransform({scale(size.width/48f,size.height/48f,pivot=Offset.Zero)}) {
   val stroke=Stroke(width=2.1f,cap=StrokeCap.Round,join=StrokeJoin.Round)
   fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(tint,Offset(x,y),Offset(x2,y2),strokeWidth=2.1f,cap=StrokeCap.Round)
   when(kind) {
    "pause"->{drawRoundRect(tint,Offset(14f,10f),Size(7f,28f),CornerRadius(1.5f));drawRoundRect(tint,Offset(27f,10f),Size(7f,28f),CornerRadius(1.5f))}
    "filter"->{val p=Path().apply{moveTo(5f,8f);lineTo(43f,8f);lineTo(28f,25f);lineTo(28f,39f);lineTo(20f,39f);lineTo(20f,25f);close()};drawPath(p,tint,style=stroke)}
    "add"->{line(24f,5f,24f,43f);line(5f,24f,43f,24f)}
   }
  }
 }
}
/** Original outlines; selected SVG assets preserve the user’s contour, fills and colors. */
@Composable fun NavigationGlyph(kind:String,modifier:Modifier=Modifier,selected:Boolean=false){
 val light=Color(0xFFF8F8FF)
 Box(modifier,contentAlignment=Alignment.Center){
  if(kind=="settings")Canvas(Modifier.fillMaxSize()){
   withTransform({scale(size.width/48f,size.height/48f,pivot=Offset.Zero)}){
    val gear=Path();for(i in 0 until 32){val angle=(i*360f/32f-90f)*kotlin.math.PI/180;val radius=if(i%4 in 1..2)22f else 17f;val x=24f+radius*kotlin.math.cos(angle).toFloat();val y=24f+radius*kotlin.math.sin(angle).toFloat();if(i==0)gear.moveTo(x,y)else gear.lineTo(x,y)};gear.close()
    if(selected){drawPath(gear,Color(0xFF7B61FF));drawCircle(Color(0xFF8A80FF),7.5f,Offset(24f,24f))}
    drawPath(gear,light,style=Stroke(2.8f,join=StrokeJoin.Round));drawCircle(light,7.5f,Offset(24f,24f),style=Stroke(2.8f))
   }
  }else SketchIcon(kind,modifier=Modifier.fillMaxSize())
 }
}
@Composable fun SketchNavigation(selected:Int,onSelect:(Int)->Unit){
 Column(Modifier.fillMaxWidth().background(Purple).navigationBarsPadding()){
  Row(Modifier.fillMaxWidth().height(56.dp).selectableGroup(),verticalAlignment=Alignment.CenterVertically){
   listOf("company" to "公司","jobs" to "岗位","profile" to "个人信息","settings" to "设置").forEachIndexed{index,(kind,label)->
    Box(Modifier.weight(1f).fillMaxHeight().selectable(selected=selected==index,interactionSource=remember{MutableInteractionSource()},indication=null,onClick={onSelect(index)},role=Role.Tab).semantics{contentDescription=label},contentAlignment=Alignment.Center){
     val chosen=when(kind){"company"->R.drawable.user_selected_company;"jobs"->R.drawable.user_selected_jobs;"profile"->R.drawable.user_selected_profile;else->null}
     if(selected==index&&chosen!=null){
      Image(painterResource(chosen),contentDescription=null,modifier=Modifier.size(if(kind=="jobs")44.dp else 40.dp),contentScale=ContentScale.Fit)
     }else if(selected==index){
      // Settings uses a circular selection background and matching center fill.
      Box(Modifier.size(44.dp).background(Color(0xFF8A80FF),CircleShape),contentAlignment=Alignment.Center){NavigationGlyph(kind,Modifier.size(36.dp),selected=true)}
     }else NavigationGlyph(kind,Modifier.size(28.dp).graphicsLayer {if(kind=="jobs"){scaleX=32f/28f;scaleY=32f/28f;transformOrigin=TransformOrigin(0f,1f)}})

    }
   }
  }
 }
}
