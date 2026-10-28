package chat.plutab.job27
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
class PublicDistributionTest {
 @Test fun initialDataIsEmptyAndLocal(){
  val s=JSONObject(File("src/main/assets/initial-snapshot.json").readText())
  for(key in listOf("companies","jobs","tasks","resumes"))assertEquals(0,s.getJSONArray(key).length())
  assertTrue(s.getBoolean("localOwner"))
  val p=s.getJSONObject("profile")
  for(key in listOf("name","email","phone","education","experience","skills","preferences","photo"))assertEquals("",p.getString(key))
  assertEquals(0,p.getJSONArray("factSections").length())
 }
 @Test fun noBundledPersonalFactsOrCompanyAssets(){
  for(name in listOf("company-logos.json","company-countries.json"))assertEquals(0,JSONObject(File("src/main/assets/$name").readText()).length())
  assertEquals(0,JSONObject(File("src/main/assets/personal-facts.json").readText()).getJSONArray("sections").length())
  assertFalse(File("src/main/assets/resumes").exists());assertFalse(File("src/main/assets/company-media").exists())
 }
}
