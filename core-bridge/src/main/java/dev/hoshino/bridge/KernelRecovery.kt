package dev.hoshino.bridge

import java.io.File

/**
 * Classification of kernel failures and the single-retry recovery policy for a user turn.
 *
 * Root cause (m2.8.x, HyperOS): the proot'd node kernel dies or is restarted mid-task. The App then
 * (A) loses its loopback mux/HTTP link before the turn reaches the model ("0 步 · 0s"), and
 * (B) on the next turn dsh-session-persistence-jsonl refuses the session because the session.lock
 * flock is still held by an orphaned node from the previous launch:
 * `session "<id>" is already owned by an active write handle` (SessionAlreadyOwnedError), shown as
 * "内核拒绝请求：session … is …" truncated by the Toast. Other dsh texts on the same path:
 * `is owned by subagent routing` (session/agent-busy), cold ids absent from persistence (not found).
 */
enum class KernelFailure { SessionOwned, SessionBusy, SessionNotFound, KernelLink, ProviderTransport, Other }

object KernelRecovery {
    fun classify(message: String?): KernelFailure {
        val m = message.orEmpty()
        return when {
            Regex("already owned by an active write handle|ownership lost|SessionOwnershipLost|handle (is )?closed", RegexOption.IGNORE_CASE).containsMatchIn(m) -> KernelFailure.SessionOwned
            Regex("agent-busy|is busy|already running|turn (is )?in progress|owned by subagent routing", RegexOption.IGNORE_CASE).containsMatchIn(m) -> KernelFailure.SessionBusy
            Regex("session/not-found|session[^\\n]{0,80}(not found|is not open|is unavailable|is not attached)|SessionPersistenceNotFound", RegexOption.IGNORE_CASE).containsMatchIn(m) -> KernelFailure.SessionNotFound
            Regex("内核请求失败|内核未连接|终端连接已断开|内核鉴权失败|Failed to connect to /127\\.0\\.0\\.1|127\\.0\\.0\\.1.*(refused|reset)|unexpected end of stream|Socket closed|reconnecting", RegexOption.IGNORE_CASE).containsMatchIn(m) -> KernelFailure.KernelLink
            isTransportFailure(null, m) -> KernelFailure.ProviderTransport
            else -> KernelFailure.Other
        }
    }

    /** What to do before the one retry. */
    enum class Action { RestartKernel, CancelStaleTurn, ReloadSession, None }

    fun actionFor(failure: KernelFailure): Action = when (failure) {
        KernelFailure.SessionOwned, KernelFailure.KernelLink -> Action.RestartKernel
        KernelFailure.SessionBusy -> Action.CancelStaleTurn
        KernelFailure.SessionNotFound -> Action.ReloadSession
        else -> Action.None
    }

    /**
     * Runs [attempt]; on a recoverable failure performs [recover] once and retries exactly once.
     * A second failure (or a non-recoverable first one) is rethrown with the full message.
     */
    suspend fun <T> runWithRecovery(attempt: suspend () -> T, recover: suspend (Action, KernelFailure, Throwable) -> Unit): T {
        try { return attempt() } catch (first: kotlinx.coroutines.CancellationException) { throw first } catch (first: Exception) {
            val failure = classify(first.message)
            val action = actionFor(failure)
            if (action == Action.None) throw first
            recover(action, failure, first)
            return attempt()
        }
    }

    /** Busy state machine: cancel the stale turn, then wait for idle; give up after [maxPolls]. */
    enum class BusyState { Cancel, Wait, Ready, GiveUp }
    fun nextBusyState(running: Boolean, polls: Int, maxPolls: Int = 20): BusyState = when {
        !running -> BusyState.Ready
        polls == 0 -> BusyState.Cancel
        polls >= maxPolls -> BusyState.GiveUp
        else -> BusyState.Wait
    }

    /** Heap cap for node inside proot: about a quarter of device RAM, clamped to 512..2048 MiB. */
    fun nodeHeapMiB(totalRamBytes: Long): Int = (totalRamBytes / 4 / (1024 * 1024)).toInt().coerceIn(512, 2048)

    /** Last lines of kernel output that explain a crash (OOM, fatal, signal), without tool data. */
    fun crashReason(lines: List<String>): String? = lines.lastOrNull {
        Regex("heap out of memory|FATAL ERROR|Allocation failed|Killed|SIGKILL|SIGSEGV|Segmentation fault|uncaught|exit(ed)? with", RegexOption.IGNORE_CASE).containsMatchIn(it)
    }?.take(300)

    /**
     * Kills node-host kernels left over from a previous launch (any pid whose cmdline runs main.js), so
     * their flock on session.lock is released before the new kernel opens sessions. Same-uid only.
     */
    fun reapOrphanKernels(except: Set<Int> = emptySet(), proc: File = File("/proc")): Int {
        var killed = 0
        proc.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid <= 1 || pid in except || pid == android.os.Process.myPid()) return@forEach
            val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (isKernelCmdline(cmd)) { runCatching { android.os.Process.killProcess(pid); killed++ } }
        }
        return killed
    }
    fun isKernelCmdline(cmdline: String) = cmdline.contains("/opt/hoshino/node-host/dist/node-host/src/main.js")
}
