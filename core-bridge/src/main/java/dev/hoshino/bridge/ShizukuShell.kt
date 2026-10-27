package dev.hoshino.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns only this app's non-daemon privileged service; it never stops the user's Shizuku server. */
class ShizukuShell(private val context: Context) {
    @Suppress("DEPRECATION")
    private val args=Shizuku.UserServiceArgs(ComponentName(context,PrivilegedShellService::class.java)).daemon(false).tag("hoshino-system-shell").processNameSuffix("system-shell").version(context.packageManager.getPackageInfo(context.packageName,0).longVersionCode.toInt())
    private val lock=Mutex()
    private var connected=CompletableDeferred<IShizukuShell>()
    private var bound=false
    private val workers=Executors.newCachedThreadPool()
    private val owners=ConcurrentHashMap<String,String>()
    private val cancelled=ConcurrentHashMap<String,String>()
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            connected.complete(IShizukuShell.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) { bound=false;connected=CompletableDeferred() }
    }
    private fun authorized()=Shizuku.pingBinder() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED
    private suspend fun service(): IShizukuShell {
        check(authorized()){ "请在 Shizuku 中启动服务并授权 Hoshino Harness" }
        lock.withLock { if(!bound)withContext(Dispatchers.Main){Shizuku.bindUserService(args,connection);bound=true} }
        return withTimeout(20000){connected.await()}
    }
    suspend fun execute(id:String,session:String,command:String,timeout:Int,output:(String)->Unit={}):JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")) && command.length in 1..16000)
        if(cancelled.remove(id)==session)throw CancellationException("System command cancelled")
        val remote=service();check(owners.putIfAbsent(id,session)==null)
        if(cancelled.remove(id)==session){owners.remove(id);throw CancellationException("System command cancelled")}
        val root=File(context.filesDir,"workspace").apply{mkdirs()}
        check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
        val out=File.createTempFile("system-shell-",".stdout",root)
        val err=File.createTempFile("system-shell-",".stderr",root)
        val stdout=ParcelFileDescriptor.open(out,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        val stderr=ParcelFileDescriptor.open(err,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        try {
            val result=suspendCancellableCoroutine<String>{ continuation ->
                workers.execute { try { val value=remote.execute(id,command,timeout,32768,stdout,stderr);if(continuation.isActive)continuation.resume(value) }
                    catch(failure: Exception){if(continuation.isActive)continuation.resumeWithException(IllegalStateException("Shizuku 系统执行连接中断",failure))} }
                continuation.invokeOnCancellation { workers.execute{runCatching{remote.cancel(id)}} }
            }
            val json=JSONObject(result)
            check(!json.has("error")){json.optString("error")}
            json.put("stdoutPath","/root/workspace/"+out.name).put("stderrPath","/root/workspace/"+err.name)
            output(json.optString("stdout")+json.optString("stderr"))
            return json
        } finally { stdout.close();stderr.close();owners.remove(id) }
    }
    suspend fun cancel(id:String,session:String) { if(cancelled.size>128)cancelled.clear();cancelled[id]=session;if(owners[id]==session)withTimeoutOrNull(1000){val remote=connected.await();withContext(Dispatchers.IO){runCatching{remote.cancel(id)}}} }
    suspend fun installApk(file:File):JSONObject {val remote=service();return withContext(Dispatchers.IO){ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->JSONObject(remote.installApk(fd,file.length()))}}}
    fun close() {
        if(bound)runCatching{Shizuku.unbindUserService(args,connection,true)}
        bound=false;connected.cancel();connected=CompletableDeferred();owners.clear()
    }
}
