package chat.plutab.job27

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Supply the missing issuer, never a new trust anchor or an SSL-error "proceed". */
internal object HutchRecruitmentTls {
 fun supports(url:String):Boolean=runCatching{val u=URI(url);u.scheme=="https"&&u.host=="www.hutch-med.com"&&u.port in listOf(-1,443)&&u.rawUserInfo==null}.getOrDefault(false)
 // CA Issuers from the site's signed leaf certificate:
 // https://example.invalid
 // SHA-256 05dc9edc0fddfa975a1432ef806ec780078b5362ad45af76db15c907630db25d
 internal val issuer:X509Certificate by lazy {CertificateFactory.getInstance("X.509").generateCertificate("""-----BEGIN CERTIFICATE-----
MIIFxjCCBK6gAwIBAgIQDwa7CTBhSCZ/yhtxwduAfTANBgkqhkiG9w0BAQsFADBh
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
MjAeFw0yMjEyMTUwMDAwMDBaFw0zMjEyMTQyMzU5NTlaMFsxCzAJBgNVBAYTAlVT
MRcwFQYDVQQKEw5EaWdpQ2VydCwgSW5jLjEzMDEGA1UEAxMqR2VvVHJ1c3QgRzIg
VExTIENOIFJTQTQwOTYgU0hBMjU2IDIwMjIgQ0ExMIICIjANBgkqhkiG9w0BAQEF
AAOCAg8AMIICCgKCAgEAn1dxOo4OzYa4O1EJd0nhFI5QB/kXAJTHRI6C1J2Gz86B
Ge9+DD8R4vexG7/QnUvV+5887o+G4enlkDwJV1Pehq4i0n+X6VKIPg5cThCx6/o0
3bUkLWld7slhi3Hli/MaosZZuytdU1uCzQlGpaLB2TiTZbDImiVaykdfwl8V6AXP
0Ab4wIcvPggl4qlwyPsBY6NODbP884BmL1ntdXfzecGst30FnAtm4w+PTo0I1T3F
ITYXaNuIJnKonD1xXaN4Ar/rvcpKpntxVyZQ2T1Lb7vlfYISqNF+1ugpTzKnI1z7
xV7tSqXd2vs6Csy6yFN+QLWwMnGWuQkvrO1m+F7V85S9ECpbqn9qNtKl8gIQ7JeZ
BmBdLroW+4j1PDzBWmSWKB+gvRubVSTuoDR3VCtlI4G/Uh+ObF4UStbhKjr1SNCW
JcrB1oF+wWBl8Bvozm4xflyyGDY47KCP/Fn9X5GHDNLQA9R0zqDKRumipp1UXswF
uzH0I+gOuqSdZSsYID9XSVq6adcJ3rIMAcTuODcrPrJssOFKGFUbmY/VxbxMgYHI
/07Zfdxoa+q0osWpofbMhLPZz9UuJFBCLsjgSckkSU02/4itmLm9e3lt6E/4q9Mc
MCaS8+qrLWKG9wQtviC8zGynEXpxjEQE8WyrfeYKyXwZFCPB4rpTw1joJmGFeLUC
AwEAAaOCAX4wggF6MBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFEFOjmmd
9G4l7HgUHH7XzR2Zz/lrMB8GA1UdIwQYMBaAFE4iVCAYlebjbuYP+vq5Eu0GF485
MA4GA1UdDwEB/wQEAwIBhjAdBgNVHSUEFjAUBggrBgEFBQcDAQYIKwYBBQUHAwIw
dAYIKwYBBQUHAQEEaDBmMCMGCCsGAQUFBzABhhdodHRwOi8vb2NzcC5kaWdpY2Vy
dC5jbjA/BggrBgEFBQcwAoYzaHR0cDovL2NhY2VydHMuZGlnaWNlcnQuY24vRGln
aUNlcnRHbG9iYWxSb290RzIuY3J0MEAGA1UdHwQ5MDcwNaAzoDGGL2h0dHA6Ly9j
cmwuZGlnaWNlcnQuY24vRGlnaUNlcnRHbG9iYWxSb290RzIuY3JsMD0GA1UdIAQ2
MDQwCwYJYIZIAYb9bAIBMAcGBWeBDAEBMAgGBmeBDAECATAIBgZngQwBAgIwCAYG
Z4EMAQIDMA0GCSqGSIb3DQEBCwUAA4IBAQAkpDVI+nCKnAEEpDfldytHXUYOr2ys
aGeboE3d1KAN4Gt+eioRvgNdmDKbVFCYY0C6ErIXIvSGXafsprU2dclka/kmH+9U
4q3v5Acx6KwcnEuYLN0QOtrlQ9s3Z4IbIzdYUv8IauXC27a3x99x2WMVxNu1KGJy
0r1Z+Xya1adf9/e1LFj1CGdq9HfQ/HFWP2khzxQZ4u62sOHuZdPgtNidFOhQLC+2
5cNsPgqsaCrGLbSjzni7VN7NhAbTsLhA1oz41vHHB+q4ZzNlC01elmPBOtupe2HM
/lqMA/J/nTxc718THdxpszFllAET1/lFEolnqihpUFPaPjRwU7yZIseS
-----END CERTIFICATE-----""".byteInputStream()) as X509Certificate}
 internal fun supplement(system:X509TrustManager)=object:X509TrustManager {
  override fun getAcceptedIssuers()=system.acceptedIssuers
  override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=system.checkClientTrusted(chain,authType)
  override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String){
   try{system.checkServerTrusted(chain,authType)}catch(original:CertificateException){
    if(chain.isEmpty()||chain.last().issuerX500Principal!=issuer.subjectX500Principal)throw original
    issuer.checkValidity();chain.last().verify(issuer.publicKey)
    system.checkServerTrusted(chain+issuer,authType)
   }
  }
 }
 internal val sockets:SSLSocketFactory by lazy {
  val system=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(null as KeyStore?)}.trustManagers.filterIsInstance<X509TrustManager>().single()
  SSLContext.getInstance("TLS").apply{init(null,arrayOf<TrustManager>(supplement(system)),null)}.socketFactory
 }
 /** Called on WebView's request thread; Chromium receives bytes only after full TLS validation.
  * POST and other hosts keep their existing path. Cookies are shared with the WebView.
  */
 fun read(url:String,headers:Map<String,String>):WebResourceResponse {
  require(supports(url))
  val c=URL(url).openConnection() as HttpsURLConnection
  c.sslSocketFactory=sockets // Default HTTPS hostname verifier remains in place.
  val deadline=RequestDeadline(c,45000)
  try{
   c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000
   for((k,v)in headers)if(!k.equals("Accept-Encoding",true)&&!k.equals("Host",true))c.setRequestProperty(k,v)
   c.setRequestProperty("Accept-Encoding","identity")
   CookieManager.getInstance().getCookie(url)?.let{c.setRequestProperty("Cookie",it)}
   val status=c.responseCode
   // WebResourceResponse cannot represent redirects; do not silently read an unchecked target.
   require(status !in 300..399){"和黄招聘页返回跳转（HTTP $status）：${c.getHeaderField("Location").orEmpty()}"}
   c.headerFields.filterKeys{it?.equals("Set-Cookie",true)==true}.values.flatten().forEach{CookieManager.getInstance().setCookie(url,it)}
   val stream=if(status>=400)c.errorStream else c.inputStream
   val bytes=stream?.use{input->
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4_000_000){"和黄招聘网页异常过大"};out.write(buffer,0,n)}
    out.toByteArray()
   }?:ByteArray(0)
   val type=c.contentType.orEmpty();val mime=type.substringBefore(';').ifBlank{"application/octet-stream"}
   val charset=Regex("charset=([^; ]+)",RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim('"')
   val responseHeaders=c.headerFields.filterKeys{it!=null&&!it.equals("Set-Cookie",true)&&!it.equals("Content-Length",true)}.map{it.key!! to it.value.joinToString(", ")}.toMap()
   return WebResourceResponse(mime,charset,status,c.responseMessage?.takeIf{it.isNotBlank()}?:"HTTP $status",responseHeaders,ByteArrayInputStream(bytes))
  }finally{deadline.close();c.disconnect()}
 }
}
