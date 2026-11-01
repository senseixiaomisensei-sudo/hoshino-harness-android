// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentsTest {
    @Test
    fun chineseSegmentsJoinWithoutSpace() {
        val s = Segments()
        assertEquals("帮我", s.partial("帮我"))
        s.segmentDone("帮我订票")
        assertEquals("帮我订票明天", s.partial("明天"))
    }

    @Test
    fun englishSegmentsJoinWithOneSpace() {
        val s = Segments()
        s.segmentDone("book a")
        assertEquals("book a train", s.partial("train"))
        s.segmentDone("train")
        assertEquals("book a train", s.full())
    }

    @Test
    fun fullTrimsAndIncludesPendingPartial() {
        val s = Segments()
        s.segmentDone("  hello ")
        s.partial(" world  ")
        assertEquals("hello world", s.full())
    }

    @Test
    fun blankSegmentsAreIgnored() {
        val s = Segments()
        s.segmentDone("你好")
        s.segmentDone("")
        assertEquals("你好", s.full())
    }

    @Test
    fun partialSplitsLastFourCodePointsAsTail() {
        assertEquals(SpeechEvent.Partial("帮我订", "明天下午"), Segments.split("帮我订明天下午"))
        assertEquals(SpeechEvent.Partial("", "hi"), Segments.split("hi"))
        // 表情占两个 UTF-16 单位,按码点数不拆开
        assertEquals(SpeechEvent.Partial("a", "b😀cd"), Segments.split("ab😀cd"))
    }
}
