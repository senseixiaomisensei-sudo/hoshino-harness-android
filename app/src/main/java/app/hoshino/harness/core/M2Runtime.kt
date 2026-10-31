package app.hoshino.harness.core

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.FileProvider
import dev.hoshino.bridge.ProviderStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Android capabilities only: no Agent loop or model decisions live here. */
class M2Runtime(
    private val context:Context,
    /** 交付发生时所属的轮次（sessionId, callId）→ turnId；由宿主客户端提供。 */
    private val turnOf:(String,String?)->String? = {_,_->null},
    /** 新文件交付后通知（宿主据此发出 HarnessEvent.FileDelivered）。 */
    private val onDelivered:suspend(DeliveredFile)->Unit = {},
    private val silentInstall:suspend(File)->JSONObject,
) {
    val deliveries=MutableStateFlow<List<DeliveredFile>>(emptyList())
    private val vault=ProviderStore(context)
    private val records=File(context.filesDir,"hoshino-deliveries.json")
    // 旧记录（M2.6 及以前）没有 turnId/deliveredAt：用文件修改时间作为交付时间，界面据此归到最近的轮次。
    init {runCatching {deliveries.value=DeliveredFileCodec.decode(records.readText()){path->runCatching{workspaceFile(path).lastModified()}.getOrDefault(0L)}}}
    fun workspaceFile(path:String):File {
        val root=File(context.filesDir,"workspace").canonicalFile
        val relative=path.removePrefix("/root/workspace/").removePrefix("/workspace/")
        val file=File(root,relative).canonicalFile
        require(file.path.startsWith(root.path+File.separator) && file.isFile){"文件不在工作区或已失效"}
        return file
    }
    suspend fun handle(body:JSONObject):JSONObject {
        require(body.getInt("version")==1){"不支持的原生桥版本"}
        val args=body.optJSONObject("request")?:JSONObject()
        return when(body.getString("action")) {
            "capabilities" -> JSONObject().put("version",1).put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode).put("actions",JSONArray(listOf("device.battery","file.deliver","file.save","file.share","notification.post","shortcut.add","intent.open","github.request","update.install","web.render")))
            "device.battery" -> JSONObject().put("percent",context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            "scheduler.sync" -> {M2Scheduler.sync(context,args.optJSONArray("entries")?:JSONArray());JSONObject().put("scheduled",true)}
            "backup.metadata" -> {
                val prefs=JSONObject();for(name in listOf("m1-session-metadata","hoshino-ui"))prefs.put(name,JSONObject(context.getSharedPreferences(name,Context.MODE_PRIVATE).all))
                JSONObject().put("providers",JSONArray(vault.list().map{it.json()})).put("preferences",prefs)
            }
            "file.deliver" -> {
                val file=workspaceFile(args.getString("path"));val sessionId=args.optString("sessionId");val callId=args.optString("callId").takeIf{it.isNotBlank()}
                val row=DeliveredFile(UUID.randomUUID().toString(),sessionId,args.getString("path"),args.optString("name",file.name).take(180),file.length(),turnId=turnOf(sessionId,callId),deliveredAt=System.currentTimeMillis(),callId=callId)
                deliveries.update {(it+row).takeLast(500)}
                withContext(Dispatchers.IO){records.writeText(DeliveredFileCodec.encode(deliveries.value))}
                onDelivered(row)
                JSONObject().put("delivered",true).put("id",row.id).put("name",row.name).put("bytes",row.bytes)
            }
            "file.share" -> withContext(Dispatchers.Main){AppMedia(context).shareFile(workspaceFile(args.getString("path")));JSONObject().put("opened",true)}
            "file.save" -> save(workspaceFile(args.getString("path")),args.optString("name"))
            "notification.post" -> {M2Notifications.post(context,args.optString("title","Hoshino"),args.optString("text"));JSONObject().put("posted",true)}
            "intent.open" -> withContext(Dispatchers.Main) {
                val uri=android.net.Uri.parse(args.getString("uri"));require(uri.scheme in setOf("https","http","mailto","tel","geo")){"此 URI 协议不在公开桥白名单"}
                context.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));JSONObject().put("opened",true)
            }
            "shortcut.add" -> {
                val manager=context.getSystemService(android.content.pm.ShortcutManager::class.java)
                val shortcut=android.content.pm.ShortcutInfo.Builder(context,args.getString("id").take(100)).setShortLabel(args.getString("label").take(40)).setIntent(Intent(context,app.hoshino.harness.MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("hoshino.prompt",args.optString("prompt"))).build()
                manager.addDynamicShortcuts(listOf(shortcut));JSONObject().put("registered",true)
            }
            "github.request" -> github(args)
            "update.install" -> install(args)
            "update.artifact" -> installArtifact(args)
            "update.previous" -> {val name=args.getString("name");require(name.matches(Regex("previous-\\d+\\.apk")));finishInstall(File(context.filesDir,"hoshino/updates/$name"))}
            "web.render" -> M2WebRenderer.render(context,args)
            else -> error("未知原生桥能力")
        }
    }
    suspend fun save(file:File,name:String=""):JSONObject=withContext(Dispatchers.IO) {
        val type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())?:"application/octet-stream"
        val values=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name.ifBlank{file.name}.replace('/','_'));put(android.provider.MediaStore.MediaColumns.MIME_TYPE,type);put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/Hoshino");put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)}
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("无法创建下载文件")
        try{context.contentResolver.openOutputStream(uri)!!.use{out->file.inputStream().use{it.copyTo(out,65536)}};values.clear();values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0);context.contentResolver.update(uri,values,null,null)}catch(e:Exception){context.contentResolver.delete(uri,null,null);throw e}
        JSONObject().put("uri",uri.toString()).put("bytes",file.length())
    }
    suspend fun restoreArchive(archive:File)=withContext(Dispatchers.IO){
        var total=0L
        java.util.zip.ZipFile(archive).use{zip->
            val entries=zip.entries().asSequence().toList();require(entries.size<=20000)
            for(entry in entries){val name=entry.name;require(!name.contains('\\')&&!name.split('/').contains(".."));require(name.matches(Regex("^(self/(config|plugins|skills|ui|prompts|modes)|home/(sessions|storages))/.*")));if(entry.isDirectory)continue;require(entry.size in 0..512L*1024*1024);total+=entry.size;require(total<=1024L*1024*1024)}
            for(entry in entries){if(entry.isDirectory)continue;val nativeRoot=File(context.filesDir,if(entry.name.startsWith("self/"))"hoshino"else"dsh-home").canonicalFile;val relative=entry.name.substringAfter('/');val dest=File(nativeRoot,relative).canonicalFile;require(dest.path.startsWith(nativeRoot.path+File.separator));dest.parentFile!!.mkdirs();val temp=File(dest.parentFile,".restore-${UUID.randomUUID()}");var bytes=0L;zip.getInputStream(entry).use{input->temp.outputStream().use{out->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;bytes+=n;require(bytes<=entry.size);out.write(b,0,n)}}};java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}
        }
        val metadata=File(context.filesDir,"hoshino/config/android-metadata.json")
        if(metadata.isFile){val root=JSONObject(metadata.readText());val providers=root.optJSONArray("providers")?:JSONArray();for(i in 0 until providers.length()){val p=providers.getJSONObject(i);val models=p.getJSONArray("models");vault.save(dev.hoshino.bridge.Provider(p.getString("id"),p.getString("type"),p.getString("baseURL"),(0 until models.length()).map{models.getString(it)},p.optJSONObject("metadata")?:JSONObject()),"")};val prefs=root.optJSONObject("preferences")?:JSONObject();for(name in listOf("m1-session-metadata","hoshino-ui")){val values=prefs.optJSONObject(name)?:continue;val edit=context.getSharedPreferences(name,Context.MODE_PRIVATE).edit();values.keys().forEach{key->when(val value=values.opt(key)){is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Double->edit.putFloat(key,value.toFloat());is String->edit.putString(key,value)}};edit.commit()}}
    }
    private suspend fun github(args:JSONObject):JSONObject=withContext(Dispatchers.IO) {
        val path=args.getString("path");require(path.startsWith("/repos/") && !path.contains("..") && !path.contains("\r") && !path.contains("\n"))
        val cfg=File(context.filesDir,"hoshino/config/build.json").let{if(it.isFile)JSONObject(it.readText())else JSONObject()}
        val repository=cfg.optString("repository");require(repository.matches(Regex("[\\w.-]+/[\\w.-]+"))) {"请先设置 GitHub 仓库"}
        require(path=="/repos/$repository" || path.startsWith("/repos/$repository/")){"只允许访问配置的 GitHub 仓库"}
        val token=vault.resolve("GITHUB_TOKEN")?:error("请在凭证页填写自己的 GitHub token")
        val connection=URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try{
            connection.requestMethod=args.optString("method","GET").also{require(it in setOf("GET","POST","PATCH","PUT"))};connection.connectTimeout=20000;connection.readTimeout=60000;connection.setRequestProperty("Authorization","Bearer $token");connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            args.optJSONObject("body")?.let{connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{out->out.write(it.toString().toByteArray())}}
            val code=connection.responseCode;check(code in 200..299){"GitHub 请求失败（HTTP $code），检查仓库与 token 权限"}
            val text=connection.inputStream.bufferedReader().use{it.readText()};if(text.isBlank())JSONObject().put("status",code)else JSONObject(text)
        }finally{connection.disconnect()}
    }
    private suspend fun install(args:JSONObject):JSONObject=withContext(Dispatchers.IO) {
        val url=URL(args.getString("url"));require(url.protocol=="https" && url.userInfo==null){"更新包必须为 HTTPS"}
        val expected=args.getString("sha256").lowercase();require(expected.matches(Regex("[a-f0-9]{64}")))
        val dir=File(context.filesDir,"hoshino/updates").apply{mkdirs()};val apk=File(dir,"candidate.apk");val hash=MessageDigest.getInstance("SHA-256")
        val c=url.openConnection() as HttpURLConnection;c.connectTimeout=20000;c.readTimeout=60000
        try{check(c.responseCode in 200..299);var bytes=0L;c.inputStream.use{input->apk.outputStream().use{out->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;bytes+=n;check(bytes<=300L*1024*1024){"更新包超过 300 MiB"};hash.update(buffer,0,n);out.write(buffer,0,n)}}}}finally{c.disconnect()}
        check(hash.digest().joinToString(""){"%02x".format(it)}==expected){"APK SHA256 不匹配，已阻止安装"}
        finishInstall(apk)
    }
    private suspend fun installArtifact(args:JSONObject):JSONObject=withContext(Dispatchers.IO){
        val cfg=JSONObject(File(context.filesDir,"hoshino/config/build.json").readText());val repo=cfg.getString("repository");require(repo.matches(Regex("[\\w.-]+/[\\w.-]+")))
        val id=args.getLong("artifactId");require(id>0);val token=vault.resolve("GITHUB_TOKEN")?:error("请先填写 GitHub 凭证")
        val dir=File(context.filesDir,"hoshino/updates").apply{mkdirs()};val archive=File(dir,"artifact.zip")
        val first=URL("https://api.github.com/repos/$repo/actions/artifacts/$id/zip").openConnection() as HttpURLConnection;first.instanceFollowRedirects=false;first.setRequestProperty("Authorization","Bearer $token");first.connectTimeout=20000;first.readTimeout=30000
        val redirect=try{check(first.responseCode in setOf(301,302,303,307));URL(first.getHeaderField("Location")).also{require(it.protocol=="https")}}finally{first.disconnect()}
        val download=redirect.openConnection() as HttpURLConnection;download.connectTimeout=20000;download.readTimeout=60000
        try{check(download.responseCode==200);download.inputStream.use{input->archive.outputStream().use{out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;check(total<=500L*1024*1024);out.write(b,0,n)}}}}finally{download.disconnect()}
        val abi=if(Build.SUPPORTED_ABIS.contains("arm64-v8a"))"arm64-v8a"else"x86_64";val name="Hoshino-Harness-0.2.0-m2-$abi-debug.apk";val apk=File(dir,"candidate.apk")
        java.util.zip.ZipFile(archive).use{zip->val entries=zip.entries().asSequence().toList();val entry=entries.singleOrNull{it.name.substringAfterLast('/')==name}?:error("产物中没有本机 ABI 的 APK");require(entry.size in 1..300L*1024*1024);val sums=entries.singleOrNull{it.name.substringAfterLast('/')=="SHA256SUMS-0.2.0-m2.txt"}?:error("产物缺少校验清单");require(sums.size<10000);val expected=zip.getInputStream(sums).bufferedReader().use{it.readLines().firstOrNull{line->line.trim().endsWith(name)}?.substringBefore(' ')?.lowercase()}?:error("缺少 APK 哈希");val digest=MessageDigest.getInstance("SHA-256");zip.getInputStream(entry).use{input->apk.outputStream().use{out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;check(total<=300L*1024*1024);digest.update(b,0,n);out.write(b,0,n)}}};check(digest.digest().joinToString(""){"%02x".format(it)}==expected){"构建产物哈希不一致"}}
        finishInstall(apk)
    }
    private suspend fun finishInstall(apk:File):JSONObject=withContext(Dispatchers.IO){
        val dir=File(context.filesDir,"hoshino/updates").apply{mkdirs()}
        val flags=PackageManager.GET_SIGNING_CERTIFICATES;val incoming=context.packageManager.getPackageArchiveInfo(apk.path,flags)?:error("APK 无法解析")
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        check(incoming.packageName==context.packageName){"更新包 applicationId 不匹配"}
        val existing=installed.signingInfo!!.apkContentsSigners.map{it.toByteArray().toList()}.toSet();val signatures=incoming.signingInfo!!.apkContentsSigners.map{it.toByteArray().toList()}.toSet()
        check(existing==signatures){"更新包签名不匹配，已阻止安装"}
        File(context.applicationInfo.sourceDir).copyTo(File(dir,"previous-${installed.longVersionCode}.apk"),true)
        dir.listFiles{f->f.name.startsWith("previous-")&&f.extension=="apk"}?.sortedByDescending{it.lastModified()}?.drop(3)?.forEach{it.delete()}
        val silent=runCatching{silentInstall(apk)}.getOrNull();if(silent?.optBoolean("installed")==true)return@withContext silent
        val installer=context.packageManager.packageInstaller;val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply{setAppPackageName(context.packageName)}
        val sessionId=installer.createSession(params)
        installer.openSession(sessionId).use{session->session.openWrite("base.apk",0,apk.length()).use{out->apk.inputStream().use{it.copyTo(out,65536)};session.fsync(out)};val result=PendingIntent.getBroadcast(context,sessionId,Intent(context,M2InstallReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE);session.commit(result.intentSender)}
        JSONObject().put("verified",true).put("sessionId",sessionId).put("installation","system-confirmation").put("rollbackApks",3)
    }
}

class M2InstallReceiver:android.content.BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
    when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)){
        PackageInstaller.STATUS_PENDING_USER_ACTION->{@Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT);confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let{context.startActivity(it)}}
        PackageInstaller.STATUS_SUCCESS->M2Notifications.post(context,"Hoshino 更新完成","会话和配置保留")
        else->M2Notifications.post(context,"更新未完成","旧版本与数据仍保留，可重新安装")
    }
}}

