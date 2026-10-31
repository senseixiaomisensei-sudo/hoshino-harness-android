package app.hoshino.harness.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReadingTest {
    private fun raw(name: String, b: List<Int>, clickable: Boolean = false, editable: Boolean = false, role: String = "TextView", res: String? = null, checked: Boolean? = null, scrollable: Boolean = false) =
        UiElement(role = role, name = name, bounds = b, clickable = clickable, editable = editable, resourceId = res, checked = checked, scrollable = scrollable)

    private fun chatList(offset: Int = 0, extra: List<UiElement> = emptyList()) = listOf(
        raw("微信", listOf(0, 20, 999, 60)),
        raw("搜索", listOf(850, 20, 990, 60), clickable = true, role = "ImageButton"),
        raw("", listOf(0, 80, 999, 999), role = "androidx.recyclerview.widget.RecyclerView", scrollable = true),
        raw("张三 你好 昨天", listOf(0, 100 - offset, 999, 180 - offset), clickable = true, role = "LinearLayout"),
        raw("张三", listOf(150, 110 - offset, 400, 140 - offset)),
        raw("你好", listOf(150, 145 - offset, 400, 175 - offset)),
        raw("李四 收到 星期一", listOf(0, 190 - offset, 999, 270 - offset), clickable = true, role = "LinearLayout"),
        raw("", listOf(20, 200 - offset, 120, 260 - offset), role = "ImageView"),
    ) + extra

    @Test fun compressionDropsRedundantTextAndDecoration() {
        val st = RefStabilizer()
        val s = ScreenSnapshot("1", "com.tencent.mm", 1080, 2400, st.assign(chatList()))
        val text = ScreenCompressor.render(s)
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith("screen com.tencent.mm 1080x2400"))
        // "张三"/"你好" are already inside the clickable row's name; the bare ImageView has no name and no action.
        assertFalse(text, lines.any { it.contains("\"张三\"") || it.contains("\"你好\"") })
        assertFalse(text, lines.any { it.contains(" img ") })
        assertTrue(text, lines.any { it.contains("btn \"张三 你好 昨天\"") && it.endsWith("click") })
        assertTrue(text, lines.any { it.contains(" list ") && it.endsWith("scroll") })
        // Much smaller than a JSON dump of the same tree.
        val json = JSONArray(s.elements.map { e -> JSONObject().put("ref", e.ref).put("role", e.role).put("name", e.name).put("bounds", JSONArray(e.bounds)).put("clickable", e.clickable).put("editable", e.editable).put("scrollable", e.scrollable) }).toString()
        assertTrue("${text.length} vs ${json.length}", text.length * 2 < json.length)
    }

    @Test fun refsStayStableAcrossScrollAndDiffOnlyShowsChanges() {
        val st = RefStabilizer()
        val first = ScreenSnapshot("1", "com.tencent.mm", 1080, 2400, st.assign(chatList()))
        val rowRef = first.elements.first { it.name == "张三 你好 昨天" }.ref
        // Scrolled up by 50 and a new conversation appeared at the bottom.
        val second = ScreenSnapshot("2", "com.tencent.mm", 1080, 2400, st.assign(chatList(50, listOf(raw("王五 在吗 刚刚", listOf(0, 300, 999, 380), clickable = true, role = "LinearLayout")))))
        assertEquals(rowRef, second.elements.first { it.name == "张三 你好 昨天" }.ref)
        val delta = ScreenDiff.compute(first, second)
        assertFalse(delta.full)
        assertEquals(listOf("王五 在吗 刚刚"), delta.added.map { it.name })
        assertTrue(delta.changed.all { (o, n) -> o.ref == n.ref })
        val text = ScreenDiff.render(first, second, delta)
        assertTrue(text, text.startsWith("screen com.tencent.mm update: +1"))
        assertTrue(text, text.lines().any { it.startsWith("+ ") && it.contains("王五") })
        assertTrue("diff is shorter than the full screen", text.length < ScreenCompressor.render(second).length)
    }

    @Test fun typedTextKeepsTheInputRef() {
        val st = RefStabilizer()
        val a = ScreenSnapshot("1", "p", 1080, 2400, st.assign(listOf(raw("", listOf(0, 900, 800, 990), editable = true, role = "EditText", res = "input"), raw("发送", listOf(800, 900, 999, 990), clickable = true, role = "Button"))))
        val b = ScreenSnapshot("2", "p", 1080, 2400, st.assign(listOf(raw("你好", listOf(0, 900, 800, 990), editable = true, role = "EditText", res = "input"), raw("发送", listOf(800, 900, 999, 990), clickable = true, role = "Button"))))
        assertEquals(a.elements[0].ref, b.elements[0].ref)
        val d = ScreenDiff.compute(a, b)
        assertEquals(1, d.changed.size)
        assertTrue(ScreenDiff.render(a, b, d).contains("was \"\""))
    }

    @Test fun unchangedAndAppSwitch() {
        val st = RefStabilizer()
        val a = ScreenSnapshot("1", "p", 1080, 2400, st.assign(chatList()))
        val b = ScreenSnapshot("2", "p", 1080, 2400, st.assign(chatList()))
        assertTrue(ScreenDiff.render(a, b).contains("unchanged"))
        val c = ScreenSnapshot("3", "other", 1080, 2400, st.assign(chatList()))
        assertTrue(ScreenDiff.compute(b, c).full)
        assertTrue(ScreenDiff.render(b, c).startsWith("screen other"))
    }

    @Test fun refsAreNeverReusedForDifferentElements() {
        val st = RefStabilizer()
        val a = st.assign(listOf(raw("A", listOf(0, 0, 100, 100), clickable = true)))
        val b = st.assign(listOf(raw("B", listOf(500, 500, 600, 600), clickable = true)))
        val c = st.assign(listOf(raw("A", listOf(0, 0, 100, 100), clickable = true)))
        assertTrue(a[0].ref != b[0].ref)
        assertTrue(b[0].ref != c[0].ref)
    }

    @Test fun chooserAndNeedsUser() {
        val chooser = ScreenSnapshot("1", "com.tencent.mm", 1080, 2400, listOf(
            el("e1", "选择要使用的应用", listOf(0, 600, 999, 650), clickable = false), el("e2", "微信", listOf(100, 700, 400, 800)), el("e3", "微信(双开)", listOf(500, 700, 900, 800))),
            overlayPackages = listOf("com.miui.securitycore"))
        val c = SystemScreens.chooser(chooser, "微信")!!
        assertEquals(listOf("微信" to false, "微信(双开)" to true), c.options.map { it.label to it.clone })
        assertEquals(null, SystemScreens.chooser(snap(el("e1", "微信", listOf(0, 0, 999, 100))), "微信"))
        assertEquals("captcha", SystemScreens.needsUser(snap(el("e1", "请完成安全验证", listOf(0, 0, 999, 100), clickable = false)))?.reason)
        assertEquals("login", SystemScreens.needsUser(snap(el("e1", "登录", listOf(0, 0, 999, 100)), el("e2", "", listOf(0, 200, 999, 300), editable = true, password = true)))?.reason)
        assertEquals(null, SystemScreens.needsUser(snap(el("e1", "首页", listOf(0, 0, 999, 100)))))
    }
}
