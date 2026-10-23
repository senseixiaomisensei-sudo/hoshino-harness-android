package dev.hoshino.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.File

interface KernelLauncher {
    suspend fun launch(context: Context, bootstrap: JSONObject, onExit: (Int) -> Unit, onDiagnostic: (String) -> Unit): Process
    fun stop()
}

/** GPL proot is an independent executable in nativeLibraryDir; it is never linked. */
class ProotKernelLauncher : KernelLauncher {
    private var process: Process? = null
    override suspend fun launch(context: Context, bootstrap: JSONObject, onExit: (Int) -> Unit, onDiagnostic: (String) -> Unit): Process = withContext(Dispatchers.IO) {
        val abi = android.os.Build.SUPPORTED_ABIS.first { it == "arm64-v8a" || it == "x86_64" }
        val root = File(context.filesDir, "alpine-$abi")
        val marker = File(root, ".hoshino-m0-v1")
        if (!marker.exists()) {
            onDiagnostic("host: extracting rootfs")
            root.mkdirs()
            context.assets.open("kernel/$abi.tgz").use { input ->
                TarArchiveInputStream(GzipCompressorInputStream(input)).use { tar ->
                    val links = mutableListOf<Pair<File, String>>()
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val dest = File(root, entry.name).canonicalFile
                        require(dest.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe rootfs path" }
                        when {
                            entry.isDirectory -> dest.mkdirs()
                            entry.isSymbolicLink -> links += dest to entry.linkName
                            entry.isFile -> { dest.parentFile?.mkdirs(); dest.outputStream().use { tar.copyTo(it) }; android.system.Os.chmod(dest.path, entry.mode and 511) }
                            entry.isLink -> throw IllegalStateException("Rootfs must contain no hard links")
                        }
                    }
                    // Create links only after all regular files; the archive cannot redirect extraction.
                    links.forEach { (dest, target) -> dest.parentFile?.mkdirs(); android.system.Os.symlink(target, dest.path) }
                }
            }
            marker.writeText("0.2.1-rc.2")
            onDiagnostic("host: rootfs extracted")
        }
        val lib = context.applicationInfo.nativeLibraryDir
        val workspace = File(context.filesDir, "workspace").apply { mkdirs() }
        val home = File(context.filesDir, "dsh-home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)
        val dns = network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty()
        val resolv = File(context.filesDir, "resolv.conf").apply { writeText(dns.joinToString("\n") { "nameserver ${it.hostAddress}" } + "\n") }
        val args = listOf("$lib/libproot.so", "--link2symlink", "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${workspace.path}:/workspace", "-b", "${home.path}:/home/hoshino",
            "-b", "${resolv.path}:/etc/resolv.conf",
            "-w", "/workspace", "/usr/bin/env", "HOME=/home/hoshino", "PATH=/usr/bin:/bin", "DSH_TELEMETRY_DISABLED=1",
            "/usr/bin/node", "/opt/hoshino/node-host/dist/node-host/src/main.js")
        val builder = ProcessBuilder(args).directory(context.filesDir).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_TMP_DIR", tmp.path); put("PROOT_LOADER", "$lib/libproot-loader.so")
            put("LD_LIBRARY_PATH", lib); put("PROOT_NO_SECCOMP", "1")
        }
        val child = builder.start(); process = child
        child.outputStream.bufferedWriter().use { it.write(bootstrap.put("workspace", "/workspace").put("home", "/home/hoshino").toString()); it.newLine() }
        Thread {
            // Arbitrary child output may contain tool data or credentials. Keep only classified diagnostics.
            child.inputStream.bufferedReader().forEachLine { onDiagnostic(classifyDiagnostic(it)) }
            onExit(child.waitFor())
        }.apply { isDaemon = true; start() }
        child
    }
    override fun stop() { process?.destroy(); process = null }
}

fun classifyDiagnostic(line: String): String = when {
    line.contains("did not activate") -> "dsh: plugin activation failed"
    line.contains("Kernel required service") -> "dsh: required service unavailable"
    line.contains("proot error:") -> "proot: runtime error"
    line.contains("Error") || line.contains("error") -> "kernel: error (payload suppressed)"
    else -> "kernel: diagnostic (payload suppressed)"
}
