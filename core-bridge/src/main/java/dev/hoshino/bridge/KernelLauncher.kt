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
        val source=File(hoshino,"source").apply{mkdirs()}
        if(source.listFiles().isNullOrEmpty())runCatching{
            java.util.zip.ZipInputStream(context.assets.open("harness-source.zip")).use{zip->
                var entry=zip.nextEntry;var total=0L
                while(entry!=null){val dest=File(source,entry.name).canonicalFile;require(dest.path.startsWith(source.canonicalPath+File.separator));if(entry.isDirectory)dest.mkdirs()else{dest.parentFile?.mkdirs();dest.outputStream().use{out->val b=ByteArray(65536);while(true){val n=zip.read(b);if(n<0)break;total+=n;require(total<=50L*1024*1024);out.write(b,0,n)}}};zip.closeEntry();entry=zip.nextEntry}
            }
        }.onFailure{onDiagnostic("host: source bundle unavailable")}
        File(root,"root/workspace").mkdirs();File(root,"root/.hoshino").mkdirs()
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)
        val dns = network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty()
        val resolv = File(context.filesDir, "resolv.conf").apply { writeText(dns.joinToString("\n") { "nameserver ${it.hostAddress}" } + "\n") }
        val args = listOf("$lib/libproot.so", "--link2symlink", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${workspace.path}:/workspace", "-b", "${workspace.path}:/root/workspace", "-b", "${home.path}:/home/hoshino", "-b", "${hoshino.path}:/root/.hoshino",
            "-b", "${resolv.path}:/etc/resolv.conf",
            "-w", "/root/workspace", "/usr/bin/env", "HOME=/root", "PATH=/usr/bin:/bin", "DSH_TELEMETRY_DISABLED=1",
            "LD_PRELOAD=/opt/hoshino/libandroid-compat.so",
            "/usr/bin/node", "/opt/hoshino/node-host/dist/node-host/src/main.js")
        val builder = ProcessBuilder(args).directory(context.filesDir).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_TMP_DIR", tmp.path); put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("LD_LIBRARY_PATH", lib); put("PROOT_NO_SECCOMP", "1")
        }
        currentCoroutineContext().ensureActive()
        val child = builder.start(); process = child
        owner?.invokeOnCompletion { cause -> if(cause is CancellationException)child.destroy() }
        child.outputStream.bufferedWriter().use { it.write(bootstrap.put("workspace", "/root/workspace").put("home", "/home/hoshino").put("selfHome","/root/.hoshino").put("safeMode",context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).getBoolean("safe",false)).toString()); it.newLine() }
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
    override fun stop() { process?.destroy(); process = null }
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
