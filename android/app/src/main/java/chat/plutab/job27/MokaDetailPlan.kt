package chat.plutab.job27
import org.json.JSONObject
/** Unknown legacy page locations use saved detail URLs instead of rescanning every page. */
object MokaDetailPlan {
 fun pages(entry:JSONObject,facts:JSONObject):List<Int>{
  val index=entry.optJSONObject("cardPageKeys")?:return emptyList()
  val keys=entry.strings("keys").toSet()
  return index.keys().asSequence().filter{page->index.strings(page).any{it in keys&&!facts.has(it)}}.mapNotNull{it.toIntOrNull()?.takeIf{n->n>0}}.sorted().toList()
 }
}
