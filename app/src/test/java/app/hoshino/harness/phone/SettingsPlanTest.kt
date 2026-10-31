package app.hoshino.harness.phone

import app.hoshino.harness.bench.SettingsPlan
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The benchmark's fixed Settings plan must stay 20 valid, non-irreversible steps that the real parser accepts. */
class SettingsPlanTest {
    private fun screen(vararg names: String) = (listOf("screen com.android.settings 1080x2400 · ${names.size} items") +
        names.mapIndexed { i, n -> "e${i + 1} Button \"$n\" @500,${100 + i * 80}" }).joinToString("\n")

    @Test fun planIsTwentyStepsTheParserAccepts() {
        val plan = SettingsPlan.build("com.android.settings", listOf("显示与亮度", "电池"))
        assertEquals(20, plan.size)
        val steps = PhoneStep.parseAll(JSONArray(plan))
        assertEquals(StepKind.open_app, steps.first().kind)
        assertEquals("com.android.settings", steps.first().expect?.app)
        assertEquals(StepKind.back, steps.last().kind)
        // Targets cycle when fewer than four were found.
        assertEquals(listOf("显示与亮度", "电池", "显示与亮度", "电池", "显示与亮度"), steps.filter { it.kind == StepKind.tap }.map { it.target })
        // Every tap opens a page and is followed by back eventually: as many backs as taps.
        assertEquals(steps.count { it.kind == StepKind.tap }, steps.count { it.kind == StepKind.back })
    }

    @Test fun noPlannedTapIsIrreversible() {
        for (t in SettingsPlan.SAFE_TARGETS) assertEquals(t, null, IrreversibleDetector.matchName(t))
    }

    @Test fun picksExactNamesFromTheRenderedScreen() {
        val hyperOs = screen("WLAN", "蓝牙", "我的设备", "显示与亮度", "声音与振动", "电池", "关于手机")
        assertEquals(listOf("我的设备", "显示与亮度", "声音与振动", "电池"), SettingsPlan.pickTargets(hyperOs))
        // "显示" is not taken from "显示与亮度" while exact names exist; toggles (WLAN/蓝牙) are never picked.
        assertTrue("显示" !in SettingsPlan.pickTargets(hyperOs))
        assertEquals(listOf("Display", "Battery"), SettingsPlan.pickTargets(screen("Network & internet", "Display", "Battery")))
    }

    @Test fun fallsBackToRowsWhoseNameStartsWithAnEntry() {
        val merged = screen("显示与亮度 自动亮度、深色模式", "电池 剩余 80%")
        assertEquals(listOf("显示与亮度", "电池"), SettingsPlan.pickTargets(merged))
        assertEquals(emptyList<String>(), SettingsPlan.pickTargets(screen("WLAN", "蓝牙", "飞行模式")))
    }
}
