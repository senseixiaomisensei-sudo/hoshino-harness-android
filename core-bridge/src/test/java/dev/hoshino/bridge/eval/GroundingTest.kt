// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mika GroundingFormatsTest / GroundingClaimsTest 里只测字面规则的部分（核查模型相关的不移植，Hoshino 还没有核查器）。 */
class GroundingTest {
    private val screen = listOf("当前 App:com.example.notes\nJetpack Compose 入门\n阅读 5.3万\n价格 ¥128")
    private val hot = "当前 App:com.example.qa\n热榜\n为什么秋天的天空特别蓝\n1561 万热度\n城市里还能看到银河吗\n982 万热度\n" +
        "长期熬夜有哪些代价\n655 万热度\n猫为什么喜欢纸箱\n431 万热度\n第一份工作该怎么选\n307 万热度"

    @Test fun citationStylesAreNotPartOfTheAnswer() {
        val styles = listOf(
            "最新一篇是「Jetpack Compose 入门」（@e16）\n依据: 屏上标题「Jetpack Compose 入门」(@e16)",
            "最新一篇是「Jetpack Compose 入门」[@e16, @e17]\n依据: 「Jetpack Compose 入门」",
            "最新一篇是“Jetpack Compose 入门”[1]\n依据：“Jetpack Compose 入门”[^1]",
            "最新一篇是 Jetpack Compose 入门 @e16。\n依据: Jetpack Compose 入门【2】",
            "最新一篇是「Ｊｅｔｐａｃｋ Ｃｏｍｐｏｓｅ 入门」\n依据：「Ｊｅｔｐａｃｋ Ｃｏｍｐｏｓｅ 入门」",
        )
        for (a in styles) assertEquals(a, true, Grounding.found("最新一篇是什么", a, screen))
    }

    @Test fun numbersWithUnitsCompareByValue() {
        assertEquals(true, Grounding.found("阅读量多少", "阅读 53000\n依据: 阅读 5.3万", screen))
        assertEquals(true, Grounding.found("阅读量多少", "5.3万次阅读\n依据: 「阅读 5.3万」", screen))
        assertEquals(true, Grounding.found("多少钱", "价格 128 元\n依据：价格 ¥128", screen))
        assertEquals(false, Grounding.found("阅读量多少", "阅读 6.3万\n依据: 阅读 6.3万", screen))
        assertEquals(false, Grounding.found("多少钱", "128.5 元(@e3)\n依据: 价格 128.5", screen))
    }

    @Test fun refsInQuotesStillMatch() {
        assertTrue(Grounding.quoteInReads("Jetpack Compose 入门 (@e16)", screen))
        assertFalse(Grounding.quoteInReads("Kotlin 协程 (@e16)", screen))
    }

    @Test fun truncatedTextIsPartialEvidence() {
        val shown = listOf("当前 App:com.example.jobs\nAndroid 工程师 · Kotlin + Jetpack…\n薪资 25-40K")
        assertTrue(Grounding.builtOnTruncated("岗位要求「…Kotlin + Jetpack ...」\n依据: 列表第一条", shown))
        assertTrue(Grounding.builtOnTruncated("要求 Kotlin + Jetpack...\n依据: 列表第一条", shown))
        assertTrue(Grounding.builtOnTruncated("要求「Kotlin + Jetpack」\n依据: 列表第一条", shown))
        assertFalse(Grounding.builtOnTruncated("薪资「25-40K」\n依据: 列表第一条", shown))
        assertFalse(Grounding.builtOnTruncated("要求「Kotlin + Jetpack」\n依据: 详情页", shown + "岗位要求 Kotlin + Jetpack Compose"))
    }

    @Test fun narrativeCountsAndRanksInBasisAreNotRequired() {
        val answer = "热榜前五:\n1. 为什么秋天的天空特别蓝(1561万热度)\n2. 城市里还能看到银河吗\n3. 长期熬夜有哪些代价\n4. 猫为什么喜欢纸箱\n5. 第一份工作该怎么选\n" +
            "依据: 热榜页可见排名从1到5的问题列表,共5条,第1条热度1561万"
        assertEquals(true, Grounding.found("热榜前五是什么", answer, listOf(hot)))
        assertEquals(true, Grounding.found("热榜第一是什么", "第3步看到热榜,共 5 条,第一是「为什么秋天的天空特别蓝」\n依据: 热榜页", listOf(hot)))
    }

    @Test fun citationRefsGluedToChineseAreStripped() {
        val styles = listOf(
            "热榜第一是「为什么秋天的天空特别蓝」,1561万热度\n依据: @e46显示「为什么秋天的天空特别蓝」,@e47显示1561 万热度",
            "热榜第一是「为什么秋天的天空特别蓝」@e46,热度1561万@e47。\n依据: 热榜页(@e46)显示该标题",
            "热榜第一是「为什么秋天的天空特别蓝」[@e46、@e47]\n依据:@v2里写着「1561 万热度」",
        )
        for (a in styles) assertEquals(a, true, Grounding.found("热榜第一是什么", a, listOf(hot)))
        assertEquals(" 显示", Grounding.stripRefs("@e46显示"))
    }

    @Test fun datesAndTimesAreClaims() {
        val s = listOf("订单详情\n下单时间 2026-10-23 14:05\n已发货")
        assertEquals(true, Grounding.found("什么时候下的单", "10月23日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月22日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月23日 15:05 下的单\n依据: 订单详情「已发货」", s))
    }

    @Test fun madeUpNumberWithUnitIsCaught() {
        assertEquals(false, Grounding.found("热榜第一是什么", "热榜第一是「为什么秋天的天空特别蓝」,1671万热度\n依据: 热榜页", listOf(hot)))
        assertEquals(false, Grounding.found("热榜第一是什么", "热榜第一是「秋天为什么天这么蓝」\n依据: 热榜页", listOf(hot)))
    }
}
