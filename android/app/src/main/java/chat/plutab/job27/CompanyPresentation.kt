package chat.plutab.job27

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

/** Remove white matte while keeping colored artwork and its existing alpha. */
fun transparentCompanyLogo(source:Bitmap):Bitmap {
 val pixels=IntArray(source.width*source.height)
 source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
 for(i in pixels.indices){
  val p=pixels[i];val white=minOf((p ushr 16) and 255,(p ushr 8) and 255,p and 255)
  if(white>240){val alpha=((p ushr 24)*(255-white)/15);pixels[i]=(p and 0x00ffffff) or (alpha shl 24)}
 }
 return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
}

@Composable fun companyFlag(company:JSONObject):String {
 val context=LocalContext.current
 val countries=remember{JSONObject(context.assets.open("company-countries.json").bufferedReader().use{it.readText()})}
 val code=company.s("countryCode").ifBlank{countries.optString(company.s("name"))}.uppercase()
 return if(code.length==2&&code.all{it in 'A'..'Z'})code.map{String(Character.toChars(0x1F1E6+it.code-'A'.code))}.joinToString("") else ""
}

/** Merge archived reference material for viewing without changing personal edits or records. */
@Composable fun companyReference(company:JSONObject):JSONObject {
 val context=LocalContext.current
 val originals by produceState(emptyList<JSONObject>()){value=withContext(Dispatchers.IO){JSONObject(context.assets.open("initial-snapshot.json").bufferedReader().use{it.readText()}).arr("companies")}}
 return remember(company,originals){
  val original=originals.firstOrNull{it.s("id")==company.s("id")}?:originals.firstOrNull{it.s("name")==company.s("name")}
  RefreshEfficiency.shallow(company).apply {
   if(original!=null){
    put("media",JSONArray((company.strings("media")+original.strings("media")).distinct()))
    put("links",JSONArray((company.arr("links")+original.arr("links")).distinctBy{it.s("url")}))
    if(s("notes").isBlank())put("notes",original.s("notes"))
    if(!has("legacy"))original.optJSONObject("legacy")?.let{put("legacy",it)}
   }
  }
 }
}
