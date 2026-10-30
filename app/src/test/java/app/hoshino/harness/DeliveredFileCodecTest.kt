package app.hoshino.harness

import app.hoshino.harness.core.DeliveredFile
import app.hoshino.harness.core.DeliveredFileCodec
import org.junit.Assert.*
import org.junit.Test

class DeliveredFileCodecTest {
    /** M2.6 及以前写下的 hoshino-deliveries.json 只有 5 个字段：仍能读出，交付时间退回到文件修改时间。 */
    @Test fun legacyRecordsDecodeWithFallbackTime() {
        val legacy = """[{"id":"x","sessionId":"s","path":"/root/workspace/a.md","name":"a.md","bytes":12}]"""
        val rows = DeliveredFileCodec.decode(legacy) { path -> if (path.endsWith("a.md")) 42L else 0L }
        assertEquals(listOf(DeliveredFile("x", "s", "/root/workspace/a.md", "a.md", 12, turnId = null, deliveredAt = 42, callId = null)), rows)
    }

    @Test fun newRecordsRoundTrip() {
        val rows = listOf(
            DeliveredFile("1", "s", "/root/workspace/r.pdf", "r.pdf", 3, turnId = "e:17", deliveredAt = 1_700_000_000_000, callId = "call_9"),
            DeliveredFile("2", "s", "/root/workspace/o.txt", "o.txt", 4),
        )
        assertEquals(rows, DeliveredFileCodec.decode(DeliveredFileCodec.encode(rows)))
    }

    @Test fun malformedRowsAreSkipped() {
        val rows = DeliveredFileCodec.decode("""[{"id":"ok","path":"/w/a"},{"broken":true},7]""")
        assertEquals(listOf("ok"), rows.map { it.id })
    }
}
