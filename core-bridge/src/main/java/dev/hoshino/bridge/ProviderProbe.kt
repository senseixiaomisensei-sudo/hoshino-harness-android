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
    companion object{private val cached=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,String>>()}
    private val http=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).connectTimeout(6,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    suspend fun models(provider: Provider,key: String,headers: Map<String,String>): JSONArray=withContext(Dispatchers.IO) {
        val cacheKey=java.security.MessageDigest.getInstance("SHA-256").digest((provider.id+provider.type+provider.baseURL+key+headers.toString()).toByteArray()).joinToString(""){"%02x".format(it)}
        cached[cacheKey]?.takeIf{it.first>android.os.SystemClock.elapsedRealtime()}?.let{return@withContext JSONArray(it.second)}
        val input=normalizeProviderInput(provider.baseURL)
        val uri=runCatching{java.net.URI(input)}.getOrNull()
        require(uri!=null && uri.scheme in listOf("http","https") && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null) { "Base URL 格式不对，应类似 http://192.168.1.5:11434/v1" }
        val rows=try { fetch(provider.copy(baseURL=input),uri,key,headers) } catch(e: java.io.IOException) { throw IllegalStateException(describeNetworkError(e,uri),e) }
        rows.also{if(cached.size>128)cached.clear();cached[cacheKey]=android.os.SystemClock.elapsedRealtime()+300000 to it.toString()}
    }

    private fun fetch(provider: Provider,uri: java.net.URI,key: String,headers: Map<String,String>): JSONArray {
        val base=providerBaseURL(provider.type,provider.baseURL)
        val url=base+if(provider.type=="anthropic")"/v1/models" else "/models"
        val request=Request.Builder().url(url)
        headers.forEach { (name,value) -> request.header(name,value) }
        val credential=normalizeApiKey(key)
        if(provider.type=="anthropic")request.header("x-api-key",credential).header("anthropic-version","2023-06-01")
        else if(credential.isNotEmpty())request.header("Authorization","Bearer $credential")
        return http.newCall(request.build()).execute().use { response ->
            // Ollama addressed without /v1 (or an old Ollama without the OpenAI API): fall back to its native /api/tags.
            if(response.code==404 && provider.type!="anthropic") return@use ollamaTags(uri,headers) ?: error("模型列表接口返回 HTTP 404，请检查 Base URL（Ollama 应为 http://电脑IP:11434/v1）")
            check(response.code!=401 && response.code!=403) { "接口拒绝了凭据（HTTP ${response.code}），请检查 API Key" }
            check(response.isSuccessful) { "模型列表接口返回 HTTP ${response.code}" }
            val body=response.body ?: error("接口没有返回模型列表")
            val cap=1024*1024;require(body.contentLength()<=cap)
            val bytes=body.byteStream().use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break;check(output.size()+count<=cap) { "模型列表过大" };output.write(buffer,0,count) }
                output.toByteArray()
            }
            val data=runCatching{JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("data")}.getOrElse{error("接口返回的不是模型列表，请检查 Base URL 是否以 /v1 结尾")}
            require(data.length()<=10000)
            JSONArray((0 until data.length()).map { index ->
                val row=data.getJSONObject(index);val id=row.getString("id");require(id.length in 1..512)
                JSONObject().put("id",id).put("name",row.optString("display_name",id))
            })
        }
    }

    /** Ollama native model list: GET {scheme}://{host}:{port}/api/tags → {"models":[{"name":"qwen3:8b"}]}. */
    private fun ollamaTags(uri: java.net.URI,headers: Map<String,String>): JSONArray? {
        val url=java.net.URI(uri.scheme,null,uri.host,uri.port,"/api/tags",null,null).toString()
        val request=Request.Builder().url(url);headers.forEach{(n,v)->request.header(n,v)}
        return http.newCall(request.build()).execute().use { r ->
            if(!r.isSuccessful) return@use null
            val text=r.body?.string()?.take(1024*1024) ?: return@use null
            val models=runCatching{JSONObject(text).getJSONArray("models")}.getOrNull() ?: return@use null
            JSONArray((0 until models.length()).mapNotNull { i -> models.optJSONObject(i)?.optString("name")?.takeIf{it.isNotBlank()}?.let{JSONObject().put("id",it).put("name",it)} })
        }
    }
}

/** A readable Chinese reason for a failed provider request, with the usual Ollama-on-LAN fixes. */
internal fun describeNetworkError(e: Throwable,uri: java.net.URI): String {
    val host=uri.host.orEmpty();val port=if(uri.port>0)uri.port else if(uri.scheme=="https")443 else 80
    val target="$host:$port"
    val loopback=host=="127.0.0.1"||host=="localhost"||host=="::1"
    val loopbackHint=if(loopback)"127.0.0.1 指的是手机自己；局域网电脑上的 Ollama 请填电脑的局域网 IP（如 http://192.168.1.5:11434/v1）。" else ""
    val ollamaHint="若是电脑上的 Ollama：它默认只监听本机，请在电脑设置环境变量 OLLAMA_HOST=0.0.0.0 并重启 Ollama，同时在防火墙放行 $port 端口，手机和电脑连同一个 Wi-Fi。"
    return when(e) {
        is java.net.ConnectException -> "连不上 $target（连接被拒绝）。$loopbackHint$ollamaHint"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "连接 $target 超时。$loopbackHint$ollamaHint"
        is java.net.UnknownHostException -> "找不到主机 $host，请检查地址。"
        is java.net.NoRouteToHostException -> "到 $target 没有路由：手机和电脑不在同一网络，或开了 VPN。"
        is javax.net.ssl.SSLException -> "TLS 握手失败：局域网 Ollama 请用 http:// 而不是 https://。"
        else -> "连接 $target 失败：${e.message ?: e.javaClass.simpleName}"
    }
}
