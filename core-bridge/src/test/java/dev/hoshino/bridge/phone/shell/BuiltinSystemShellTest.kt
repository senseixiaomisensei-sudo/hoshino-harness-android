package dev.hoshino.bridge.phone.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BuiltinSystemShellTest {
    private val id = "0123abcd-0123-4567-89ab-0123456789ab"
    private fun dir() = Files.createTempDirectory("hh-sys").toFile()

    @Test fun commandIsOneQuotedArgumentWithTimeoutAndPidFile() {
        val w = BuiltinSystemShell.wrap(id, "echo 'hi'; monkey -p com.ss.android.ugc.aweme 1", 5_000)
        assertTrue(w, w.contains("/system/bin/timeout -s KILL 5 /system/bin/sh -c 'echo '\\''hi'\\''; monkey -p com.ss.android.ugc.aweme 1'"))
        assertTrue(w.contains(BuiltinSystemShell.pidFile(id)))
        assertTrue(w.startsWith("cd /data/local/tmp"))
        assertEquals("'a'\\''b'", BuiltinSystemShell.quote("a'b"))
        assertTrue(BuiltinSystemShell.cancelCommand(id).contains("pkill -9 -P"))
    }

    @Test fun timeoutIsClampedToTheHelperLimit() {
        assertEquals(BuiltinSystemShell.MAX_TIMEOUT_MS, BuiltinSystemShell.clampTimeout(120_000))
        assertEquals(1_000, BuiltinSystemShell.clampTimeout(5))
        assertTrue(BuiltinSystemShell.MAX_TIMEOUT_MS < BuiltinSystemShell.HELPER_TIMEOUT_MS)
        val r = BuiltinSystemShell.result(0, "ok", 14_000, 120_000, 20, false)
        assertEquals(14_000, r.getInt("timeoutLimitedMs"))
    }

    @Test fun resultMatchesShizukuShapeAndFlagsTimeoutAndTruncation() {
        val ok = BuiltinSystemShell.result(0, "Events injected: 1\n", 5_000, 5_000, 300, false)
        assertEquals(2000, ok.getInt("uid")); assertEquals("/data/local/tmp", ok.getString("cwd")); assertEquals("builtin", ok.getString("host"))
        assertEquals(0, ok.getInt("exitCode")); assertFalse(ok.getBoolean("timedOut")); assertFalse(ok.getBoolean("truncated"))
        assertEquals("Events injected: 1\n", ok.getString("stdout"))
        val slow = BuiltinSystemShell.result(137, "", 5_000, 5_000, 5_100, false)
        assertTrue(slow.getBoolean("timedOut"))
        // A fast 137 is the command's own exit, not our timeout.
        assertFalse(BuiltinSystemShell.result(137, "", 5_000, 5_000, 50, false).getBoolean("timedOut"))
        val big = BuiltinSystemShell.result(0, "x".repeat(40_000), 5_000, 5_000, 10, false)
        assertTrue(big.getBoolean("truncated")); assertEquals(BuiltinSystemShell.PREVIEW_BYTES, big.getString("stdout").length)
        assertEquals(40_000, big.getInt("stdoutBytes"))
    }

    @Test fun executesThroughRunEchoesOutputAndWritesFullOutputFile() = runBlocking {
        val ran = mutableListOf<String>()
        val root = dir()
        val shell = BuiltinSystemShell({ cmd -> ran += cmd; 0 to "Events injected: 1\n" }, root)
        var echoed = ""
        val r = shell.execute(id, "s1", "monkey -p com.ss.android.ugc.aweme 1", 120_000) { echoed = it }
        assertEquals(1, ran.size); assertTrue(ran[0].contains("monkey -p com.ss.android.ugc.aweme 1"))
        assertEquals("Events injected: 1\n", echoed)
        assertEquals(0, r.getInt("exitCode"))
        val file = root.resolve(r.getString("stdoutPath").removePrefix("/root/workspace/"))
        assertEquals("Events injected: 1\n", file.readText())
    }

    @Test fun rejectsBadIdsAndOverlongCommands() = runBlocking {
        val shell = BuiltinSystemShell({ 0 to "" }, dir())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { shell.execute("nope", "s", "ls", 1000) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { shell.execute(id, "s", "x".repeat(BuiltinSystemShell.MAX_COMMAND + 1), 1000) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { shell.execute(id, "s", "  ", 1000) } }
        Unit
    }

    @Test fun cancelBeforeStartAndDuringRun() = runBlocking {
        val shell0 = BuiltinSystemShell({ fail("must not run"); 0 to "" }, dir())
        shell0.cancel(id, "s1")
        assertThrows(CancellationException::class.java) { runBlocking { shell0.execute(id, "s1", "sleep 5", 5000) } }

        val gate = CompletableDeferred<Pair<Int, String>>()
        val ran = mutableListOf<String>()
        val shell = BuiltinSystemShell({ cmd -> ran += cmd; if (cmd.contains("pkill")) { gate.complete(137 to ""); 0 to "" } else gate.await() }, dir())
        val job = async { shell.execute(id, "s1", "sleep 5", 5000) }
        while (ran.isEmpty()) yield()
        shell.cancel(id, "other-session") // another session cannot cancel it
        assertFalse(gate.isCompleted)
        shell.cancel(id, "s1")
        val r = job.await()
        assertTrue(r.getBoolean("cancelled")); assertFalse(r.getBoolean("timedOut"))
        assertTrue(ran.last().contains(BuiltinSystemShell.pidFile(id)))
    }
}
