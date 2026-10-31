package app.hoshino.harness.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WP-4 (B-1): pure query / extract / rows / merge functions and the scrolling loops on a simulated list.
 * The list fixture is a real model of a scrolling RecyclerView: a viewport over N rows, rows cut at the edges
 * (their clipped copy only shows the visible part of the text), page-sized accessibility scrolls.
 */
class ScreenQueriesTest {

    private fun e(ref: String, name: String, b: List<Int>, role: String = "TextView", clickable: Boolean = false, scrollable: Boolean = false,
                  editable: Boolean = false, hint: Boolean = false, placeholder: String? = null, password: Boolean = false, checked: Boolean? = null, id: String? = null) =
        UiElement(ref = ref, role = role, name = name, bounds = b, clickable = clickable, scrollable = scrollable, editable = editable, hint = hint,
            placeholder = placeholder, password = password, checked = checked, resourceId = id)

    private fun screen(vararg els: UiElement, pkg: String = "com.shop", hash: Long = 0) = ScreenSnapshot("s", pkg, 1080, 2400, els.toList(), hash)

    // A shopping list: wide clickable item containers (name aggregated from children) with a price and a button each.
    private val shop = screen(
        e("e1", "购物车", listOf(0, 0, 999, 80), role = "TextView"),
        e("e2", "", listOf(0, 100, 999, 900), role = "androidx.recyclerview.widget.RecyclerView", scrollable = true),
        e("e3", "商品A ¥99 加入购物车", listOf(0, 100, 999, 300), role = "android.view.ViewGroup", clickable = true),
        e("e4", "商品A", listOf(20, 120, 600, 180)),
        e("e5", "¥99", listOf(20, 200, 300, 260)),
        e("e6", "加入购物车", listOf(700, 200, 980, 280), role = "android.widget.Button", clickable = true),
        e("e7", "商品B ¥15 加入购物车", listOf(0, 300, 999, 500), role = "android.view.ViewGroup", clickable = true),
        e("e8", "商品B", listOf(20, 320, 600, 380)),
        e("e9", "加入购物车", listOf(700, 400, 980, 480), role = "android.widget.Button", clickable = true),
        e("e10", "结算", listOf(700, 920, 980, 990), role = "android.widget.Button", clickable = true, id = "btn_checkout"),
    )

    @Test fun queryFiltersByTextRoleIdAndInsideAndShowsContainers() {
        assertEquals(5, ScreenQueries.query(shop, ElementQuery(role = "btn")).second)
        val (ms, total) = ScreenQueries.query(shop, ElementQuery(text = "加入", role = "Button"))
        assertEquals(2, total)
        assertEquals(listOf("e6", "e9"), ms.map { it.element.ref })
        assertEquals("商品A ¥99 加入购物车", ms[0].path.first())
        val inB = ScreenQueries.query(shop, ElementQuery(text = "加入购物车", inside = "商品B")).first
        assertEquals(listOf("e9"), inB.map { it.element.ref })
        assertEquals(listOf("e10"), ScreenQueries.query(shop, ElementQuery(id = "checkout")).first.map { it.element.ref })
        // The container text itself is not "inside" itself; plain-text children of the row are.
        assertTrue(ScreenQueries.query(shop, ElementQuery(inside = "商品A")).first.none { it.element.ref == "e3" })
        val line = ScreenQueries.renderMatches(inB)
        assertTrue(line, line.startsWith("e9 btn \"加入购物车\" @840,440 click · in \"商品B ¥15 加入购物车\""))
        assertEquals(1, ScreenQueries.query(shop, ElementQuery(role = "btn", limit = 1)).first.size)
    }

    @Test fun queryNeverMatchesPasswordText() {
        val s = screen(e("p", "secret123", listOf(0, 0, 999, 100), role = "EditText", editable = true, password = true))
        assertEquals(0, ScreenQueries.query(s, ElementQuery(text = "secret")).second)
    }

    @Test fun rowsUseListItemContainersInOrder() {
        val rows = ScreenQueries.rows(shop)
        assertEquals(listOf("商品A ¥99 加入购物车", "商品B ¥15 加入购物车"), rows.map { it.text })
        assertEquals(listOf("e3", "e7"), rows.map { it.ref })
        assertEquals(ScreenQueries.key("商品A ¥99 加入购物车"), rows[0].key)
        assertEquals(ScreenQueries.key("商品a¥99  加入购物车"), rows[0].key)
        assertTrue(rows[0].key.matches(Regex("[0-9a-f]{8}")))
        assertTrue("first row touches the list top", rows[0].clipTop)
        assertFalse(rows[1].clipped)
    }

    @Test fun rowsGroupLooseTextIntoBands() {
        val chat = screen(
            e("l", "", listOf(0, 100, 999, 900), role = "RecyclerView", scrollable = true),
            e("a", "张三", listOf(150, 150, 400, 190)),
            e("b", "明天几点？", listOf(150, 200, 700, 260)),
            e("c", "10点", listOf(600, 300, 980, 360)),
            e("t", "下午 3:20", listOf(400, 380, 600, 400)),
            pkg = "com.tencent.mm",
        )
        assertEquals(listOf("张三", "明天几点？", "10点", "下午 3:20"), ScreenQueries.rows(chat).map { it.text })
        // Side-by-side cells share one band.
        val side = screen(e("l", "", listOf(0, 0, 999, 999), scrollable = true), e("x", "名称", listOf(0, 100, 300, 160)), e("y", "数值", listOf(500, 110, 900, 150)))
        assertEquals(listOf("名称 · 数值"), ScreenQueries.rows(side).map { it.text })
    }

    @Test fun mergeKeepsRepeatedIdenticalMessagesAndDropsTheOverlap() {
        fun r(t: String) = ListRow(ScreenQueries.key(t), t, 0, 0, null)
        val acc = listOf(r("早"), r("好的"), r("在吗"), r("好的"))
        val page = listOf(r("在吗"), r("好的"), r("几点"), r("好的"))
        val m = ScreenQueries.mergeDown(acc, page)
        assertEquals(listOf("早", "好的", "在吗", "好的", "几点", "好的"), m.rows.map { it.text })
        assertEquals(2, m.added)
        assertFalse(m.gap)
        assertEquals(listOf(ScreenQueries.key("早"), ScreenQueries.key("好的"), ScreenQueries.key("在吗"), ScreenQueries.key("好的") + "-2", ScreenQueries.key("几点"), ScreenQueries.key("好的") + "-3"), ScreenQueries.displayKeys(m.rows))
        val up = ScreenQueries.mergeUp(listOf(r("第三条消息内容c"), r("第四条消息内容d")), listOf(r("第一条消息内容a"), r("第二条消息内容b"), r("第三条消息内容c")))
        assertEquals(listOf("第一条消息内容a", "第二条消息内容b", "第三条消息内容c", "第四条消息内容d"), up.rows.map { it.text })
        // One short unclipped identical row at the seam is a second message, not overlap (page scrolls share no full row).
        assertEquals(listOf("在吗", "好的", "好的", "几点"), ScreenQueries.mergeDown(listOf(r("在吗"), r("好的")), listOf(r("好的"), r("几点"))).rows.map { it.text })
        val none = ScreenQueries.mergeDown(listOf(r("x1"), r("x2")), listOf(r("y1")))
        assertTrue(none.gap)
    }

    @Test fun mergeMatchesAClippedRowAgainstItsFullCopy() {
        val full = ListRow(ScreenQueries.key("订单 1234 已发货 预计明天送达"), "订单 1234 已发货 预计明天送达", 800, 900, "e1")
        val cut = ListRow(ScreenQueries.key("订单 1234 已发货"), "订单 1234 已发货", 0, 40, "e9", clipTop = true)
        val next = ListRow(ScreenQueries.key("订单 5678 待付款"), "订单 5678 待付款", 50, 150, "e10")
        val m = ScreenQueries.mergeDown(listOf(full), listOf(cut, next))
        assertEquals(listOf(full.text, next.text), m.rows.map { it.text })
        // Two different short rows are never merged just because one contains the other.
        val a = ListRow(ScreenQueries.key("好"), "好", 0, 0, null, clipTop = true)
        val b = ListRow(ScreenQueries.key("好的"), "好的", 0, 0, null)
        assertFalse(ScreenQueries.sameRow(a, b))
    }

    @Test fun extractTextRowsAndFormWithoutPasswordValues() {
        assertEquals("购物车\n商品A ¥99 加入购物车\n商品B ¥15 加入购物车\n结算", ScreenQueries.extract(shop, ExtractParams(ExtractMode.text, 0, 999)))
        assertEquals("e3 商品A ¥99 加入购物车\ne7 商品B ¥15 加入购物车", ScreenQueries.extract(shop, ExtractParams(ExtractMode.rows, 0, 999)))
        val login = screen(
            e("t1", "手机号", listOf(0, 100, 200, 160)),
            e("f1", "138 0000 0000", listOf(220, 100, 999, 160), role = "EditText", editable = true),
            e("f2", "请输入密码", listOf(220, 200, 999, 260), role = "EditText", editable = true, password = true, hint = true),
            e("f3", "备注", listOf(220, 300, 999, 360), role = "EditText", editable = true, hint = true, placeholder = "备注"),
            e("s1", "记住我", listOf(0, 400, 999, 460), role = "android.widget.Switch", clickable = true, checked = true),
        )
        val form = ScreenQueries.form(login)
        assertEquals("f1 input \"手机号\" = \"138 0000 0000\"", form[0])
        assertEquals("f2 input \"\" = \"(hidden)\"", form[1])
        assertEquals("f3 input \"备注\" = \"\"", form[2])
        assertEquals("s1 switch \"记住我\" on", form[3])
    }

    @Test fun parametersAreValidatedStrictly() {
        fun bad(block: () -> Unit) = try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
        bad { ElementQuery.parse(JSONObject()) }
        bad { ElementQuery.parse(JSONObject("""{"text":"a","limit":0}""")) }
        bad { ElementQuery.parse(JSONObject("""{"text":"a","limit":1.5}""")) }
        bad { ExtractParams.parse(JSONObject("""{"mode":"html"}""")) }
        bad { ExtractParams.parse(JSONObject("""{"from_y":500,"to_y":520}""")) }
        bad { FindParams.parse(JSONObject("""{"text":"a","direction":"sideways"}""")) }
        bad { FindParams.parse(JSONObject("""{"text":"a","max_scrolls":31}""")) }
        bad { CollectParams.parse(JSONObject("""{"direction":"left"}""")) }
        bad { CollectParams.parse(JSONObject("""{"max_items":301}""")) }
        bad { CollectParams.parse(JSONObject("""{"cursor":"nope"}""")) }
        bad { CollectParams.parse(JSONObject("""{"direction":"down","cursor":"c1.up.0123abcd"}""")) }
        val c = CollectParams.parse(JSONObject("""{"direction":"up","cursor":"c1.up.0123abcd.89abcdef","from_y":100,"to_y":900}"""))
        assertEquals(listOf("0123abcd", "89abcdef"), c.cursor!!.keys)
        assertEquals(CollectParams.DEFAULT_ITEMS, c.maxItems)
        assertEquals("c1.up.0123abcd.89abcdef", c.cursor!!.encode())
        assertNull(ReadCursor.parse("c1.down.0123abcd.1.2"))
        assertEquals("down", FindParams.parse(JSONObject("""{"role":"btn"}""")).direction)
    }

    // ---------- loops on a simulated scrolling list ----------

    /**
     * A RecyclerView of [items] (each 100 units tall) shown in a viewport from y=100 to y=900. An accessibility scroll moves
     * by [step] units. Rows cut by the viewport edge expose only the first part of their text (what is visible), like a clipped
     * container whose aggregated label skips invisible children.
     */
    private class ListPort(val items: List<String>, var offset: Int, val step: Int = 800, val rowH: Int = 100, val wide: Boolean = false, val pkg: String = "com.tencent.mm") : PhonePort {
        val top = 100; val bottom = 900
        var scrolls = 0
        val performed = mutableListOf<String>()
        var failScroll: String? = null
        val maxOffset get() = maxOf(0, items.size * rowH - (bottom - top))
        override suspend fun read(): ScreenSnapshot {
            val els = mutableListOf(UiElement(ref = "list", role = "androidx.recyclerview.widget.RecyclerView", name = "", bounds = listOf(0, top, 999, bottom), scrollable = true))
            for ((i, t) in items.withIndex()) {
                val y0 = top + i * rowH - offset; val y1 = y0 + rowH
                if (y1 <= top || y0 >= bottom) continue
                val vis0 = maxOf(y0, top); val vis1 = minOf(y1, bottom)
                val fraction = (vis1 - vis0).toDouble() / rowH
                val text = if (fraction >= 1.0) t else t.take(maxOf(1, (t.length * fraction).toInt()))
                els += UiElement(ref = "r$i", role = if (wide) "android.view.ViewGroup" else "TextView", name = text, bounds = listOf(if (wide) 0 else 100, vis0, if (wide) 999 else 900, vis1), clickable = wide)
            }
            return ScreenSnapshot("s$offset", pkg, 1080, 2400, els, hash = offset.toLong() + 1)
        }
        override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome {
            performed += step.describe()
            failScroll?.let { return ActionOutcome(false, it) }
            val before = offset
            offset = if (step.direction == "up") maxOf(0, offset - this.step) else minOf(maxOffset, offset + this.step)
            if (offset != before) scrolls++
            return ActionOutcome(true)
        }
        override suspend fun awaitChange(timeoutMs: Long) = false
        override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long) = true
        override fun now() = 0L
    }

    /** 200 chat messages with many repeated texts ("好的", "嗯", stickers) — the WP-4 acceptance fixture. */
    private val chat200 = List(200) { i ->
        when {
            i % 7 == 0 -> "好的"
            i % 11 == 0 -> "[表情]"
            i % 13 == 0 -> "嗯"
            else -> "消息 $i：" + "内容".repeat(i % 5 + 1) + " 第${i}条记录"
        }
    }

    private fun checkComplete(expected: List<String>, got: List<String>, context: String = "") {
        val at = expected.indices.firstOrNull { it >= got.size || expected[it] != got[it] } ?: if (got.size > expected.size) expected.size else null
        if (at != null) fail("$context: rows differ at $at: expected ${expected.subList(maxOf(0, at - 2), minOf(expected.size, at + 3))} got ${got.subList(maxOf(0, at - 2), minOf(got.size, at + 3))} (sizes ${expected.size}/${got.size})")
    }

    @Test fun collectReads200ChatMessagesDownWithoutDuplicatesOrMisses() = runBlocking {
        for (step in listOf(800, 600, 750, 530)) {
            val port = ListPort(chat200, 0, step = step)
            val r = ScreenReader(port).collect(CollectParams("down", 0, 999, 300, 40, null))
            checkComplete(chat200, r.rows.map { it.text }, "step $step")
            assertEquals("step $step", ReadStop.END, r.stop)
            if (step < 800) assertEquals("overlapping scrolls always align", 0, r.unaligned)
            assertNull("finished lists return no cursor", r.cursor)
            assertEquals(r.rows.size, r.keys.toSet().size)
        }
    }

    @Test fun collectReadsChatHistoryUpFromTheBottom() = runBlocking {
        for (step in listOf(800, 640, 750, 530)) {
            val p = ListPort(chat200, 0, step = step).also { it.offset = it.maxOffset }
            val up = ScreenReader(p).collect(CollectParams("up", 0, 999, 300, 40, null))
            checkComplete(chat200, up.rows.map { it.text }, "up step $step")
            assertEquals(ReadStop.END, up.stop)
        }
        val port = ListPort(chat200, 0, step = 640)
        port.offset = port.maxOffset
        val r = ScreenReader(port).collect(CollectParams("up", 0, 999, 300, 40, null))
        checkComplete(chat200, r.rows.map { it.text })
        assertEquals(ReadStop.END, r.stop)
        assertTrue(port.performed.toString(), port.performed.all { it == "scroll list up" })
    }

    @Test fun collectWithWideClickableRowsToo() = runBlocking {
        val items = List(120) { "商品 $it ¥${it * 3 + 1} 已售${it * 7}" }
        for (step in listOf(800, 750, 600)) {
            val port = ListPort(items, 0, step = step, wide = true, pkg = "com.shop")
            val r = ScreenReader(port).collect(CollectParams("down", 0, 999, 300, 40, null))
            checkComplete(items, r.rows.map { it.text }, "wide step $step")
            assertTrue(r.rows.all { it.ref != null })
        }
    }

    @Test fun collectStopsAtMaxItemsAndCursorContinuesWithoutOverlapOrGap() = runBlocking {
        val port = ListPort(chat200, 0, step = 700)
        val reader = ScreenReader(port)
        val first = reader.collect(CollectParams("down", 0, 999, 50, 40, null))
        assertEquals(ReadStop.MAX_ITEMS, first.stop)
        assertEquals(50, first.rows.size)
        val cursor = assertNotNullAndGet(first.cursor)
        val all = first.rows.map { it.text }.toMutableList()
        var c: ReadCursor? = cursor
        var rounds = 0
        while (c != null && rounds++ < 10) {
            val next = reader.collect(CollectParams("down", 0, 999, 50, 40, c))
            assertFalse("cursor rows are still on screen", next.cursorLost)
            all += next.rows.map { it.text }
            c = next.cursor
        }
        checkComplete(chat200, all)
    }

    @Test fun collectUpWithCursorContinuesOlderHistory() = runBlocking {
        val port = ListPort(chat200, 0, step = 800)
        port.offset = port.maxOffset
        val reader = ScreenReader(port)
        val first = reader.collect(CollectParams("up", 0, 999, 60, 40, null))
        assertEquals(60, first.rows.size)
        assertEquals(chat200.takeLast(60), first.rows.map { it.text })
        val second = reader.collect(CollectParams("up", 0, 999, 300, 40, first.cursor))
        assertFalse(second.cursorLost)
        checkComplete(chat200, second.rows.map { it.text } + first.rows.map { it.text })
    }

    @Test fun collectKeepsTheOutputWithinTheCharacterBudgetAndContinues() = runBlocking {
        val long = List(150) { "第${it}条 " + "很长的聊天内容".repeat(20) }
        val port = ListPort(long, 0, step = 800)
        val reader = ScreenReader(port)
        val first = reader.collect(CollectParams("down", 0, 999, 300, 40, null))
        assertEquals(ReadStop.MAX_CHARS, first.stop)
        assertTrue(first.rows.sumOf { it.text.length + ScreenReader.ROW_OVERHEAD } <= ScreenQueries.MAX_CHARS)
        val second = reader.collect(CollectParams("down", 0, 999, 300, 40, first.cursor))
        assertFalse(second.cursorLost)
        assertEquals(long.subList(first.rows.size, first.rows.size + second.rows.size), second.rows.map { it.text })
    }

    @Test fun collectStopsOnScrollFailureNeedsUserAndMaxScrolls() = runBlocking {
        val failing = ListPort(chat200, 0).apply { failScroll = "own_app: Hoshino does not operate its own screens" }
        val r = ScreenReader(failing).collect(CollectParams("down", 0, 999, 300, 40, null))
        assertEquals(ReadStop.SCROLL_FAILED, r.stop)
        assertTrue(r.detail!!.startsWith("own_app"))
        assertNotNull("more to read: cursor returned", r.cursor)
        val limited = ScreenReader(ListPort(chat200, 0)).collect(CollectParams("down", 0, 999, 300, 2, null))
        assertEquals(ReadStop.MAX_SCROLLS, limited.stop)
        assertEquals(2, limited.scrolls)
        val stopped = ScreenReader(ListPort(chat200, 0)) { "stopped by request" }.collect(CollectParams("down", 0, 999, 300, 40, null))
        assertEquals(ReadStop.STOPPED, stopped.stop)
        assertEquals(0, stopped.scrolls)
    }

    @Test fun findScrollsUntilTheTargetAppearsAndStopsAtTheEnd() = runBlocking {
        val port = ListPort(chat200, 0, step = 800)
        val hit = ScreenReader(port).find(FindParams(ElementQuery(text = "第150条"), "down", 30))
        assertEquals(ReadStop.FOUND, hit.stop)
        assertEquals("r150", hit.matches.single().element.ref)
        assertTrue(hit.scrolls in 1..30)
        val miss = ScreenReader(ListPort(chat200, 0, step = 800)).find(FindParams(ElementQuery(text = "不存在的内容"), "down", 30))
        assertEquals(ReadStop.END, miss.stop)
        assertTrue(miss.matches.isEmpty())
        val capped = ScreenReader(ListPort(chat200, 0, step = 800)).find(FindParams(ElementQuery(text = "不存在的内容"), "down", 3))
        assertEquals(ReadStop.MAX_SCROLLS, capped.stop)
        assertEquals(3, capped.scrolls)
        val here = ScreenReader(ListPort(chat200, 0)).find(FindParams(ElementQuery(text = "消息 1："), "down", 5))
        assertEquals(0, here.scrolls)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
