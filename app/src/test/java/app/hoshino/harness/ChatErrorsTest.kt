package app.hoshino.harness

import app.hoshino.harness.ui.chat.ChatErrors
import org.junit.Assert.*
import org.junit.Test

class ChatErrorsTest {
    @Test fun kernelErrorsAreShownInFull() {
        assertTrue(ChatErrors.isFullError("内核拒绝请求：session \"8d90e505-7220-48a6-9819-211e538f97ae\" is already owned by an active write handle"))
        assertTrue(ChatErrors.isFullError("请求未完成（TRANSPORT）：Connection error."))
        assertFalse(ChatErrors.isFullError("已复制"))
    }
}
