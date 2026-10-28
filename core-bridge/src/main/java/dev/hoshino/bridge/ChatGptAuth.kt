package dev.hoshino.bridge

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** OAuth/PKCE port of openai/codex login (Apache-2.0); see licenses/OpenAI-Codex-NOTICE.txt. */
data class ChatGptAuthState(val status:String="未登录",val signedIn:Boolean=false,val waiting:Boolean=false)
data class ChatGptOAuthEndpoints(val issuer:String="https://auth.openai.com",val clientId:String="app_EMoamEEZ73f0CkXaXp7hrann",val callbackPort:Int=1455)
class ChatGptAuth(private val context:Context,private val endpoints:ChatGptOAuthEndpoints=ChatGptOAuthEndpoints()) {
    private val vault=ProviderStore(context)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex()
    private val http=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).connectTimeout(2,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val epoch=java.util.concurrent.atomic.AtomicInteger()
    private var listener:ServerSocket?=null
    private var login:Job?=null
    val state=MutableStateFlow(ChatGptAuthState(signedIn=vault.hasSecret(ACCESS),status=if(vault.hasSecret(ACCESS))"已登录"else"未登录"))
    init {scope.launch{while(isActive){delay(60000);if(vault.hasSecret(ACCESS)&&vault.list().any{it.id=="openai-codex"&&it.metadata.optBoolean("enabled",true)}&&(vault.resolve(EXPIRES)?.toLongOrNull()?:0)<System.currentTimeMillis()+90000)runCatching{accessToken(true)}}}}
    private fun random(size:Int)=ByteArray(size).also{SecureRandom().nextBytes(it)}.let{Base64.encodeToString(it,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}
    fun beginLogin():String {
        val attempt=epoch.incrementAndGet()
        login?.cancel();listener?.close()
        val server=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),endpoints.callbackPort));soTimeout=600000};listener=server
        val verifier=random(32);val csrf=random(32)
        val challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val redirect="http://127.0.0.1:${server.localPort}/auth/callback"
        val url=(endpoints.issuer+"/oauth/authorize").toHttpUrl().newBuilder().addQueryParameter("response_type","code").addQueryParameter("client_id",endpoints.clientId).addQueryParameter("redirect_uri",redirect).addQueryParameter("scope","openid profile email offline_access api.connectors.read api.connectors.invoke").addQueryParameter("code_challenge",challenge).addQueryParameter("code_challenge_method","S256").addQueryParameter("state",csrf).addQueryParameter("id_token_add_organizations","true").addQueryParameter("codex_cli_simplified_flow","true").addQueryParameter("originator","hoshino-harness").build()
        state.value=ChatGptAuthState("等待浏览器登录",state.value.signedIn,true)
        login=scope.launch {
            try {
                var accepted=false
                while(isActive&&!accepted){server.accept().use{socket->
                    socket.soTimeout=2000
                    val input=socket.getInputStream().bufferedReader();val first=input.readLine().orEmpty();require(first.length<=16384)
                    val parts=first.split(' ');val request=runCatching{("http://127.0.0.1"+parts.getOrElse(1){"/"}).toHttpUrl()}.getOrNull()
                    val candidate=request?.queryParameter("state").orEmpty()
                    val valid=parts.firstOrNull()=="GET"&&request?.encodedPath=="/auth/callback"&&MessageDigest.isEqual(candidate.toByteArray(),csrf.toByteArray())
                    if(!valid){respond(socket,false,"登录回调无效，请返回 Hoshino 重试");return@use}
                    val code=request?.queryParameter("code")
                    if(code.isNullOrBlank()){respond(socket,false,"登录未完成，请返回 Hoshino 重试");throw IllegalStateException("登录已取消或授权失败")}
                    try{lock.withLock{exchange(FormBody.Builder().add("grant_type","authorization_code").add("client_id",endpoints.clientId).add("code",code).add("redirect_uri",redirect).add("code_verifier",verifier).build(),attempt)};respond(socket,true,"登录成功，可以返回 Hoshino");accepted=true}
                    catch(failure:Exception){respond(socket,false,"授权交换失败，请返回 Hoshino 重新登录");throw failure}
                }}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(epoch.get()==attempt)state.value=ChatGptAuthState("登录未完成，请重新登录",vault.hasSecret(ACCESS))}
            finally{runCatching{server.close()};if(listener===server)listener=null}
        }
        return url.toString()
    }
    private fun respond(socket:java.net.Socket,ok:Boolean,message:String){val body="<!doctype html><meta charset=utf-8><title>Hoshino</title><p>$message</p>".toByteArray();socket.getOutputStream().apply{write("HTTP/1.1 ${if(ok)"200 OK"else"400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}}
    private fun exchange(form:FormBody,attempt:Int=epoch.get()){
        http.newCall(Request.Builder().url(endpoints.issuer+"/oauth/token").post(form).build()).execute().use{response->
            if(!response.isSuccessful)throw IllegalStateException(if(response.code in setOf(400,401,403))"ChatGPT 登录已失效，请重新登录"else"ChatGPT 授权暂时失败（HTTP ${response.code}）")
            val body=JSONObject(response.body?.string().orEmpty());val access=body.optString("access_token");require(access.isNotBlank()){"授权响应缺少 access_token"}
            if(epoch.get()!=attempt)throw CancellationException("Login was replaced")
            vault.putSecret(ACCESS,access);body.optString("refresh_token").takeIf{it.isNotBlank()}?.let{vault.putSecret(REFRESH,it)};body.optString("id_token").takeIf{it.isNotBlank()}?.let{vault.putSecret(ID,it)}
            val expires=body.optLong("expires_in",3600).coerceAtLeast(1);val jwt=runCatching{JSONObject(String(Base64.decode(access.split('.')[1],Base64.URL_SAFE or Base64.NO_WRAP)))}.getOrNull();val expiry=jwt?.optLong("exp")?.takeIf{it>0}?.times(1000)?:System.currentTimeMillis()+expires*1000
            vault.putSecret(EXPIRES,expiry.toString());state.value=ChatGptAuthState("已登录",true)
        }
    }
    suspend fun accessToken(forceRefresh:Boolean=false):String=withContext(Dispatchers.IO){
        if(!forceRefresh){val cached=vault.resolve(ACCESS);val expiry=vault.resolve(EXPIRES)?.toLongOrNull()?:0;if(cached!=null&&expiry>System.currentTimeMillis()+5000)return@withContext cached}
        lock.withLock{
        val access=vault.resolve(ACCESS)?:throw IllegalStateException("ChatGPT 尚未登录，请到模型设置登录")
        val expires=vault.resolve(EXPIRES)?.toLongOrNull()?:0L
        if(!forceRefresh&&expires>System.currentTimeMillis()+30000)return@withLock access
        val refresh=vault.resolve(REFRESH)?:throw IllegalStateException("ChatGPT 登录已失效，请重新登录")
        try{exchange(FormBody.Builder().add("grant_type","refresh_token").add("client_id",endpoints.clientId).add("refresh_token",refresh).build());vault.resolve(ACCESS)?:error("请重新登录")}
        catch(failure:Exception){state.value=ChatGptAuthState("登录已失效，请重新登录",false);throw failure}
    }}
    fun close(){epoch.incrementAndGet();login?.cancel();runCatching{listener?.close()};scope.cancel()}
    fun logout(){epoch.incrementAndGet();login?.cancel();listener?.close();for(ref in listOf(ACCESS,REFRESH,ID,EXPIRES))vault.removeSecret(ref);state.value=ChatGptAuthState()}
    companion object{
        const val ACCESS="CHATGPT_ACCESS_TOKEN";const val REFRESH="CHATGPT_REFRESH_TOKEN";const val ID="CHATGPT_ID_TOKEN";const val EXPIRES="CHATGPT_TOKEN_EXPIRES"
        @Volatile private var instance:ChatGptAuth?=null
        fun installForTest(context:Context,endpoints:ChatGptOAuthEndpoints):ChatGptAuth{check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0);check(java.net.URI(endpoints.issuer).host in setOf("127.0.0.1","localhost"));instance?.close();return ChatGptAuth(context.applicationContext,endpoints).also{instance=it}}
        fun get(context:Context)=instance?:synchronized(this){instance?:ChatGptAuth(context.applicationContext).also{instance=it}}
    }
}
