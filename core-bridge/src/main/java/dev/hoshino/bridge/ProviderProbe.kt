package dev.hoshino.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Explicit Settings action, with bounded responses and no authentication redirects. */
class ProviderProbe {
    private val http=OkHttpClient.Builder().followRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    suspend fun models(provider: Provider,key: String,headers: Map<String,String>): JSONArray=withContext(Dispatchers.IO) {
        val uri=java.net.URI(provider.baseURL)
        require(uri.scheme in listOf("http","https") && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null)
        val base=provider.baseURL.trimEnd('/')
        val url=base+if(provider.type=="anthropic" && !base.endsWith("/v1"))"/v1/models" else "/models"
        val request=Request.Builder().url(url)
        headers.forEach { (name,value) -> request.header(name,value) }
        if(provider.type=="anthropic")request.header("x-api-key",key).header("anthropic-version","2023-06-01")
        else if(key.isNotEmpty())request.header("Authorization","Bearer $key")
        http.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) { "模型列表接口返回 HTTP ${response.code}" }
            val body=response.body ?: error("接口没有返回模型列表")
            val cap=1024*1024;require(body.contentLength()<=cap)
            val bytes=body.byteStream().use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break;check(output.size()+count<=cap) { "模型列表过大" };output.write(buffer,0,count) }
                output.toByteArray()
            }
            val data=JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("data")
            require(data.length()<=10000)
            JSONArray((0 until data.length()).map { index ->
                val row=data.getJSONObject(index);val id=row.getString("id");require(id.length in 1..512)
                JSONObject().put("id",id).put("name",row.optString("display_name",id))
            })
        }
    }
}
