package app.hoshino.harness

import app.hoshino.harness.core.ChatItem
import app.hoshino.harness.ui.components.*
import org.junit.Assert.*
import org.junit.Test

class MessageTextTest {
    @Test fun plainCopyRemovesFormattingButPreservesCode() {
        val raw="## 标题\n\n**粗体**与`code`\n\n```kotlin\nval x = \"**literal**\"\n```"
        val item=ChatItem.Assistant("test",raw,false)
        assertEquals(raw,item.markdownText())
        assertEquals("标题\n\n粗体与code\n\nval x = \"**literal**\"",item.plainText())
    }
    @Test fun codeCopySourceHasNoFenceOrLanguageMarker() {
        val block=parseMarkdown("```sh\nprintf hello\n```").single() as MdBlock.Code
        assertEquals("printf hello",block.code)
    }
}
