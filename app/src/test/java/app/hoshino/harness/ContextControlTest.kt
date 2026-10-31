package app.hoshino.harness
import app.hoshino.harness.core.compactNoticeText
import org.junit.Assert.*
import org.junit.Test
class ContextControlTest {
    @Test fun compactResultIsShownInChineseWithTheSameFacts(){
        assertEquals("已压缩上下文：折叠 12 条较早记录（约 3456 tokens），你说过的原话已逐字保留。",compactNoticeText(true,"Compacted 12 history items (~3456 tokens)."))
        assertEquals("暂时没有可以压缩的历史。",compactNoticeText(true,"No compactable history yet."))
        assertEquals("当前正在运行或正在压缩，等这一轮结束后再压缩。",compactNoticeText(false,"Compaction is unavailable because this process has an active compaction, or the agent is not idle."))
        assertTrue(compactNoticeText(false,"").startsWith("压缩没有完成"))
    }
}
