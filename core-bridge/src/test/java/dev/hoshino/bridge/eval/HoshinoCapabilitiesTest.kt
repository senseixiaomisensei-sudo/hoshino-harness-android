package dev.hoshino.bridge.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HoshinoCapabilitiesTest {
    private val tasks = listOf(File("../eval/tasks"), File("eval/tasks")).first { it.isDirectory }
        .walkTopDown().filter { it.extension == "json" }.associate { it.nameWithoutExtension to EvalTasks.parse(it.readText()) }

    @Test fun detectsFromRealToolNames() {
        val today = listOf("read", "write", "bash", "web_search", "web_fetch", "web_render", "ask_user", "get_device_info")
        assertEquals(setOf("web", "sandbox", "ask_user"), HoshinoCapabilities.detect(today))
        assertTrue("phone" in HoshinoCapabilities.detect(today + "phone_tap"))
        assertEquals(listOf("read", "web_fetch"), HoshinoCapabilities.toolsInManifest("""{"plugins":[],"tools":[{"name":"read"},{"name":"web_fetch"},{"x":1}]}"""))
        assertEquals(emptyList<String>(), HoshinoCapabilities.toolsInManifest("not json"))
    }

    @Test fun requirementsPerTask() {
        assertEquals(setOf("web"), HoshinoCapabilities.required(tasks.getValue("web-python-latest")))
        assertEquals(setOf("web", "sandbox"), HoshinoCapabilities.required(tasks.getValue("web-sandbox-csv-mean")))
        assertEquals(setOf("web", "phone"), HoshinoCapabilities.required(tasks.getValue("web-mix-android-version-check")))
        assertEquals(setOf("phone"), HoshinoCapabilities.required(tasks.getValue("calculator-multiply")))
        assertEquals(setOf("phone", "memory"), HoshinoCapabilities.required(tasks.getValue("mem-rate-2-use")))
        assertEquals(setOf("phone", "ask_user"), HoshinoCapabilities.required(tasks.getValue("alarm-missing-time")))
        assertEquals(emptySet<String>(), HoshinoCapabilities.required(tasks.getValue("weather-moon-temperature")))
    }

    /** 今天（m2.8 合并前）的 Hoshino 能跑哪些题：没有手机能力，网页与沙箱题能跑。报告里如实列出其余的跳过原因。 */
    @Test fun todaysHoshinoRunsTheWebAndSandboxTasks() {
        val have = setOf("web", "sandbox", "ask_user")
        val runnable = tasks.values.filter { HoshinoCapabilities.missing(it, have).isEmpty() }.map { it.id }.toSet()
        assertTrue(runnable.containsAll(listOf("web-python-latest", "web-sandbox-csv-mean", "weather-moon-temperature")))
        assertTrue(runnable.none { tasks.getValue(it).apps.isNotEmpty() })
        assertEquals("缺能力:phone(手机操作,m2.8)", HoshinoCapabilities.skipReason(setOf("phone")))
    }
}
