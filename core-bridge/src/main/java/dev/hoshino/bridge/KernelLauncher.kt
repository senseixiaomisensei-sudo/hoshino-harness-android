package dev.hoshino.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

interface KernelLauncher {
    suspend fun launch(context: Context, bootstrap: JSONObject, onExit: (Int) -> Unit, onDiagnostic: (String) -> Unit): Process
    fun stop()
}

/** GPL proot is an independent executable in nativeLibraryDir; it is never linked. */
class ProotKernelLauncher : KernelLauncher {
    private var process: Process? = null
    private var unwatchDns: (() -> Unit)? = null
    override suspend fun launch(context: Context, bootstrap: JSONObject, onExit: (Int) -> Unit, onDiagnostic: (String) -> Unit): Process {
        val owner=currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
        val abi = android.os.Build.SUPPORTED_ABIS.first { it == "arm64-v8a" || it == "x86_64" }
        val root = File(context.filesDir, "alpine-$abi")
        val fingerprint = context.assets.open("kernel/manifest.json").bufferedReader().use { JSONObject(it.readText()).getString(abi) }
        val marker = File(root, ".hoshino-runtime")
        if (!marker.isFile || marker.readText() != fingerprint) {
            onDiagnostic("host: extracting rootfs")
            val staging = File(context.filesDir, "alpine-$abi.installing")
            val stagedMarker=File(staging, marker.name)
            if(!stagedMarker.isFile || stagedMarker.readText()!=fingerprint) {
            RuntimeArchive.removeGenerated(staging,context.filesDir)
            context.assets.open("kernel/$abi.txz").use { RuntimeArchive.extract(it,"xz",staging) }
            File(staging, marker.name).writeText(fingerprint)
            }
            RuntimeArchive.removeGenerated(root,context.filesDir)
            java.nio.file.Files.move(staging.toPath(), root.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            onDiagnostic("host: rootfs extracted")
        }
        val lib = context.applicationInfo.nativeLibraryDir
        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
        val home = File(context.filesDir, "dsh-home").apply { mkdirs() }
        val hoshino = File(context.filesDir,"hoshino").apply { mkdirs() }
        runCatching{val record=File(hoshino,"runtime-process.json");if(record.isFile){val saved=JSONObject(record.readText());val pid=saved.getInt("pid");require(pid>1);val proc=File("/proc/$pid");if(proc.isDirectory){val command=File(proc,"cmdline").readText();val stat=File(proc,"stat").readText();val ticks=stat.substring(stat.lastIndexOf(')')+2).split(' ')[19];if(ticks==saved.getString("startTicks")&&command.contains("/opt/hoshino/node-host/dist/node-host/src/main.js")){android.os.Process.killProcess(pid);Thread.sleep(300)}}}}
        val source=File(hoshino,"source").apply{mkdirs()}
        if(source.listFiles().isNullOrEmpty())runCatching{
            java.util.zip.ZipInputStream(context.assets.open("harness-source.zip")).use{zip->
                var entry=zip.nextEntry;var total=0L
                while(entry!=null){val dest=File(source,entry.name).canonicalFile;require(dest.path.startsWith(source.canonicalPath+File.separator));if(entry.isDirectory)dest.mkdirs()else{dest.parentFile?.mkdirs();dest.outputStream().use{out->val b=ByteArray(65536);while(true){val n=zip.read(b);if(n<0)break;total+=n;require(total<=50L*1024*1024);out.write(b,0,n)}}};zip.closeEntry();entry=zip.nextEntry}
            }
        }.onFailure{onDiagnostic("host: source bundle unavailable")}
        File(root,"root/workspace").mkdirs();File(root,"root/.hoshino").mkdirs()
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        // musl inside proot does not use Android's resolver: write system DNS + domestic fallbacks and keep it current.
        val resolv = File(context.filesDir, "resolv.conf")
        KernelNetwork.writeResolvConf(resolv, KernelNetwork.systemDns(context))
        unwatchDns?.invoke(); unwatchDns = KernelNetwork.watchDns(context.applicationContext, resolv)
        val proxyEnv = KernelNetwork.kernelProxyEnvironment(context)
        val args = listOf("$lib/libproot.so", "-0", "--link2symlink", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${workspace.path}:/workspace", "-b", "${workspace.path}:/root/workspace", "-b", "${home.path}:/home/hoshino", "-b", "${hoshino.path}:/root/.hoshino",
            "-b", "${resolv.path}:/etc/resolv.conf",
            "-w", "/root/workspace", "/usr/bin/env", "HOME=/root", "PATH=/root/.hoshino/tools/python/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "DSH_TELEMETRY_DISABLED=1",
            "LD_PRELOAD=/opt/hoshino/libandroid-compat.so") + proxyEnv.map { (k, v) -> "$k=$v" } +
            listOf("/usr/bin/node") + KernelNetwork.NODE_NETWORK_FLAGS + listOf("/opt/hoshino/node-host/dist/node-host/src/main.js")
        val builder = ProcessBuilder(args).directory(context.filesDir).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_TMP_DIR", tmp.path); put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("LD_LIBRARY_PATH", lib); put("PROOT_NO_SECCOMP", "1")
        }
        currentCoroutineContext().ensureActive()
        val child = builder.start(); process = child
        owner?.invokeOnCompletion { cause -> if(cause is CancellationException)child.destroy() }
        child.outputStream.bufferedWriter().use { it.write(bootstrap.put("workspace", "/root/workspace").put("home", "/home/hoshino").put("selfHome","/root/.hoshino").put("safeMode",context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).getBoolean("safe",false)).put("timeZone",java.util.TimeZone.getDefault().id).toString()); it.newLine() }
        Thread {
            // Arbitrary child output may contain tool data or credentials. Keep only classified diagnostics.
            try {
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach { onDiagnostic(classifyDiagnostic(it)) } }
            } catch (_: java.io.IOException) {
                // Process.destroy closes this pipe during an ordinary stop/restart.
                onDiagnostic("host: diagnostic stream closed")
            } finally {
                try { onExit(child.waitFor()) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }.apply { isDaemon = true; start() }
        child
        }
    }
    override fun stop() { process?.destroy(); process = null; unwatchDns?.invoke(); unwatchDns = null }
}

fun classifyDiagnostic(line: String): String {
    Regex("^hoshino: boot ([A-Za-z]+) (ready|pending)$").matchEntire(line)?.let { match ->
        if(match.groupValues[1] in setOf("agents","fs","attachments","sessionController","connection","webServer","subprocess","shell","skills","terminals","terminalController"))return "dsh: ${match.groupValues[1]} ${match.groupValues[2]}"
    }
    val knownPlugins = setOf("workspace", "session-controller", "android-api", "android-policy", "android-credentials", "android-device-info", "sessions", "session-checkpoint-policy", "storage-domain", "storage-json", "webserver", "connection", "typert-gateway", "agent-loop", "llm")
    Regex("^([a-z][a-z0-9-]{0,63}) \\(").find(line)?.groupValues?.get(1)?.takeIf { it in knownPlugins }?.let { id ->
        val code = Regex("Android callback failed \\(([0-9]{3})\\)").find(line)?.groupValues?.get(1)
        return "dsh plugin $id: " + if(code != null) "callback HTTP $code" else "activation failed"
    }
    return when {
    line.contains("ptrace",ignoreCase=true) && line.contains("Operation not permitted",ignoreCase=true) -> "proot: ptrace EPERM"
    line.contains("ptrace",ignoreCase=true) && line.contains("Permission denied",ignoreCase=true) -> "proot: ptrace EACCES"
    line.contains("ptrace",ignoreCase=true) && (line.contains("failed",ignoreCase=true) || line.contains("error",ignoreCase=true)) -> "proot: ptrace failed"
    line.contains("proot warning: signal 11 received") -> "proot: tracer SIGSEGV"
    line.contains("proot warning: signal 31 received") -> "proot: tracer SIGSYS"
    line.contains("did not activate") -> "dsh: plugin activation failed"
    line.contains("Kernel required service") -> "dsh: required service unavailable"
    line.contains("proot error:") -> "proot: runtime error"
    line.contains("Error") || line.contains("error") -> "kernel: error (payload suppressed)"
    else -> "kernel: diagnostic (payload suppressed)"
    }
}
