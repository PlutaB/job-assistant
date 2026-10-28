package chat.plutab.job27
import org.json.JSONObject
import org.json.JSONArray
import java.io.Writer

/** Never allocate a string/byte array containing every job description at once. */
fun streamJson(value:Any?,out:Writer){
 when{
  value==null||value===JSONObject.NULL->out.write("null")
  value is JSONObject->{out.write("{");var first=true;for(key in value.keys()){
   if(!first)out.write(",");first=false;writeQuotedJson(key,out);out.write(":");streamJson(value.get(key),out)
  };out.write("}")}
  value is JSONArray->{out.write("[");for(i in 0 until value.length()){if(i>0)out.write(",");streamJson(value.get(i),out)};out.write("]")}
  value is Number->out.write(JSONObject.numberToString(value))
  value is Boolean->out.write(value.toString())
  else->writeQuotedJson(value.toString(),out)
 }
}

private fun writeQuotedJson(text:String,out:Writer){
 out.write("\"");var start=0
 for(i in text.indices){val c=text[i];val escape=when(c){
  '\"'->"\\\""; '\\'->"\\\\"; '\b'->"\\b"; '\u000C'->"\\f"; '\n'->"\\n"; '\r'->"\\r"; '\t'->"\\t"
  else->if(c.code<32)"\\u"+c.code.toString(16).padStart(4,'0')else null
 }
 if(escape!=null){if(i>start)out.write(text,start,i-start);out.write(escape);start=i+1}
 }
 if(start<text.length)out.write(text,start,text.length-start)
 out.write("\"")
}
