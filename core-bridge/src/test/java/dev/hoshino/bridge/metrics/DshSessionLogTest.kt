package dev.hoshino.bridge.metrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DshSessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun line(e: DshEvent, wrapped: Boolean): String {
        val o = JSONObject().put("seq", e.seq).put("type", e.type).put("time", e.time).put("data", e.data)
        return (if (wrapped) JSONObject().put("event", o) else o).toString()
    }

    private fun write(dir: File, name: String, id: String, events: List<DshEvent>, wrapped: Boolean = true): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText((listOf(JSONObject().put("id", id).put("cwd", "/root/workspace").toString()) + events.map { line(it, wrapped) } + "not json").joinToString("\n") + "\n") }
    }

    @Test fun findsByDirectoryOrHeaderAndPrefersTheNewestGeneration() {
        val root = tmp.newFolder("sessions")
        val turn = Dsh.turn()
        write(File(root, "p1/abc"), "session.jsonl", "abc", turn.take(3))
        val newest = write(File(root, "p1/abc"), "session.v2.jsonl", "abc", turn)
        val encoded = write(File(root, "p2/x%2Fy"), "session.jsonl", "x/y", turn, wrapped = false)
        assertEquals(newest, DshSessionLog.find(root, "abc"))
        assertEquals(encoded, DshSessionLog.find(root, "x/y"))
        assertNull(DshSessionLog.find(root, "missing"))
        assertNull(DshSessionLog.find(File(root, "nope"), "abc"))
    }

    /** 文件里读出来算的小结 = 内存里同一串事件算的小结（OB3「与 session log 重算一致」）。 */
    @Test fun readsEventsAndRecomputesTheSameMetrics() {
        val root = tmp.newFolder("sessions")
        val first = Dsh.turn(goal = "第一问")
        val second = Dsh(seq = first.last().seq + 1, now = 2_000_000L).turnStart(2).user("第二问", turn = 2)
            .after(10).stepStart(1, turn = 2).after(300).assistant(1, "好", firstTokenAt = 2_000_200L, turn = 2).stepEnd(1, turn = 2)
            .after(5).turnEnd(turn = 2).events
        val file = write(File(root, "p/s1"), "session.jsonl", "s1", first + second + first.take(2)) // 重复的 seq 去掉
        val events = DshSessionLog.read(file)
        assertEquals((first + second).map { it.seq }, events.map { it.seq })
        val runs = DshSessionLog.metrics(events, "s1")
        assertEquals(listOf("s1#1", "s1#2"), runs.map { it.runId })
        assertEquals(RunMetrics.of(first, "s1#1"), runs[0])
        assertEquals("第二问", runs[1].goal)
        assertEquals(2, DshSessionLog.lastTurn(events)?.turn)
        assertEquals("s1", DshSessionLog.headerId(file))
    }

    @Test fun unreadableFilesAreEmpty() {
        assertTrue(DshSessionLog.read(File(tmp.root, "absent.jsonl")).isEmpty())
        assertNull(DshSessionLog.headerId(File(tmp.root, "absent.jsonl")))
    }
}
