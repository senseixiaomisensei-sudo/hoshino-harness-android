package dev.hoshino.bridge.phone.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * system_shell through the built-in wireless-debugging helper (adb uid 2000), for users without Shizuku (m2.8.1).
 * Runs through [BuiltinShellManager.run] (the helper's runCommand: 4000-char command, 15 s hard limit, 64 KiB output,
 * stdout+stderr merged). On top of that it keeps the same contract as [dev.hoshino.bridge.ShizukuShell.execute]:
 * per-call timeout (toybox `timeout -s KILL`), cancellation by id (kills the command and its direct children),
 * output preview + full output file in the private workspace. Approval happens in the kernel before we get here.
 */
class BuiltinSystemShell(
    private val run: suspend (String) -> Pair<Int, String>,
    private val workspace: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val owners = ConcurrentHashMap<String, String>()
    private val cancelled = ConcurrentHashMap<String, String>()

    suspend fun execute(id: String, session: String, command: String, timeoutMs: Int, output: (String) -> Unit = {}): JSONObject {
        require(id.matches(ID)) { "Invalid system command id" }
        require(command.isNotBlank() && command.length <= MAX_COMMAND) { "命令过长：内置无线调试助手最多执行 $MAX_COMMAND 个字符的命令" }
        if (cancelled.remove(id) == session) throw CancellationException("System command cancelled")
        check(owners.putIfAbsent(id, session) == null) { "Duplicate command id" }
        try {
            val timeout = clampTimeout(timeoutMs)
            val wrapped = wrap(id, command, timeout)
            check(wrapped.length <= HELPER_COMMAND_MAX) { "命令过长：内置无线调试助手最多执行约 $MAX_COMMAND 个字符的命令" }
            val started = clock()
            val (code, text) = run(wrapped)
            val elapsed = clock() - started
            val wasCancelled = cancelled.remove(id) == session
            val result = result(code, text, timeout, timeoutMs, elapsed, wasCancelled)
            withContext(Dispatchers.IO) {
                val root = workspace.apply { mkdirs() }
                check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
                val out = File.createTempFile("system-shell-", ".stdout", root).apply { writeText(text) }
                result.put("stdoutPath", "/root/workspace/" + out.name)
            }
            output(result.optString("stdout"))
            return result
        } finally { owners.remove(id) }
    }

    suspend fun cancel(id: String, session: String) {
        if (cancelled.size > 128) cancelled.clear()
        cancelled[id] = session
        if (owners[id] == session) runCatching { run(cancelCommand(id)) }
    }

    companion object {
        val ID = Regex("[a-f0-9-]{36}")
        /** The helper's runCommand refuses longer commands and kills anything running past 15 s. */
        const val HELPER_COMMAND_MAX = 4_000
        const val HELPER_TIMEOUT_MS = 15_000
        const val MAX_COMMAND = 3_400
        const val MAX_TIMEOUT_MS = 14_000
        const val PREVIEW_BYTES = 32_768
        const val HELPER_OUTPUT_MAX = 64 * 1024
        private const val DIR = "/data/local/tmp"

        fun clampTimeout(requestedMs: Int): Int = requestedMs.coerceIn(1_000, MAX_TIMEOUT_MS)

        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        fun pidFile(id: String) = "$DIR/.hoshino-system-shell-$id.pid"

        /** The user command is one quoted argument; the pid file lets [cancel] find it from a second helper call. */
        fun wrap(id: String, command: String, timeoutMs: Int): String {
            val seconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
            val pid = pidFile(id)
            return "cd $DIR || exit 125; /system/bin/timeout -s KILL $seconds /system/bin/sh -c ${quote(command)} </dev/null 2>&1 & p=\$!; " +
                "echo \$p > $pid; wait \$p; c=\$?; rm -f $pid; exit \$c"
        }

        fun cancelCommand(id: String): String {
            val pid = pidFile(id)
            return "p=\$(cat $pid 2>/dev/null) || exit 0; [ -n \"\$p\" ] || exit 0; pkill -9 -P \$p; kill -9 \$p; rm -f $pid; exit 0"
        }

        /** Same shape as the Shizuku result so the model and the UI read both alike. */
        fun result(code: Int, text: String, timeoutMs: Int, requestedMs: Int, elapsedMs: Long, cancelled: Boolean): JSONObject {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val timedOut = !cancelled && (code == 124 || code == 137 || code == -1) && elapsedMs >= timeoutMs - 500
            return JSONObject()
                .put("uid", 2000).put("cwd", DIR).put("host", "builtin")
                .put("exitCode", code).put("timedOut", timedOut).put("cancelled", cancelled)
                .put("stdout", String(bytes.copyOf(minOf(bytes.size, PREVIEW_BYTES)), Charsets.UTF_8))
                .put("stderr", "").put("stderrMerged", true)
                .put("stdoutBytes", bytes.size).put("stderrBytes", 0)
                .put("truncated", bytes.size > PREVIEW_BYTES || bytes.size >= HELPER_OUTPUT_MAX)
                .apply { if (requestedMs > timeoutMs) put("timeoutLimitedMs", timeoutMs).put("note", "内置无线调试助手单条命令最长 ${MAX_TIMEOUT_MS / 1000} 秒；更长的任务请拆分或使用 Shizuku") }
        }
    }
}
