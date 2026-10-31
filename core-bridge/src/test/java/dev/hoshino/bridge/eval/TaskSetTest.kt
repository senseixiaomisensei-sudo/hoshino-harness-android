// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 仓库里的评测任务集（eval/tasks/<类别>/…/<id>.json）。规则照抄 Mika TaskSetTest：128 题都能解析、合法、按目录分层分池。 */
class TaskSetTest {
    private val evalDir = listOf("eval", "../eval").map(::File).first { File(it, "tasks").isDirectory }
    private val tasksDir = File(evalDir, "tasks")
    private val files = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
    private val tasks = files.map { it to EvalTasks.parse(it.readText()) }

    private fun group(f: File) = f.parentFile.relativeTo(tasksDir).invariantSeparatorsPath
    private fun suite(f: File) = group(f).substringBefore('/').takeIf { it in SUITES }

    @Test fun allTasksParseAndValidate() {
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), EvalTasks.validateAll(tasks.map { it.second }))
        tasks.forEach { (f, t) ->
            if (group(f) == WEB) {
                assertTrue("${t.id}: 网页题的 id 以 web- 开头", t.id.startsWith("web-"))
                t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
                return@forEach
            }
            if (suite(f) == null) assertEquals(f.path, t.category.wire, group(f).substringBefore('/'))
            assertTrue("${t.id}: apps 为空", t.apps.isNotEmpty() || t.category == EvalCategory.Impossible)
            t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
            if (t.category == EvalCategory.Impossible) assertEquals(t.id, Expect.Failed, t.expect)
            if (t.category == EvalCategory.Barrier) assertEquals(t.id, Expect.Takeover, t.expect)
            if (t.category == EvalCategory.Read) assertEquals(t.id, Expect.Succeeded, t.expect)
        }
    }

    /** EV1 验收：移植的 Mika 题集共 128 题。 */
    @Test fun mikaSetIsComplete() {
        assertEquals(128, tasks.count { !it.second.id.startsWith(HOSHINO_PREFIX) })
    }

    /** 解析后再写回 JSON、再解析，得到同一道题（Hoshino 的读写与 Mika 的格式双向兼容）。 */
    @Test fun roundTripsThroughJson() {
        tasks.forEach { (_, t) -> assertEquals(t.id, t, EvalTasks.parse(EvalTasks.toJson(t).toString())) }
    }

    @Test fun strictParsingRejectsUnknownFields() {
        val ok = files.first().readText()
        val bad = org.json.JSONObject(ok).put("sauce", "x").toString()
        assertTrue(runCatching { EvalTasks.parse(bad) }.exceptionOrNull()?.message.orEmpty().contains("sauce"))
        val badCheck = org.json.JSONObject(ok).put("checks", org.json.JSONArray().put(org.json.JSONObject().put("type", "answer_regex").put("pattern", "x").put("flags", "i"))).toString()
        assertTrue(runCatching { EvalTasks.parse(badCheck) }.isFailure)
    }

    private fun pinnedOk(t: EvalTask, app: String) = APP_TOKEN.matches(app) || "installed:$app" in t.requires

    @Test fun idsUniqueAndMatchFileNames() {
        tasks.forEach { (f, t) -> assertEquals(f.path, f.nameWithoutExtension, t.id) }
        assertEquals(tasks.size, tasks.map { it.second.id }.toSet().size)
    }

    @Test fun compositionMeetsMinimums() {
        assertTrue("总数 ${tasks.size}", tasks.size >= 60)
        val count = tasks.filter { suite(it.first) == null }.groupingBy { group(it.first) }.eachCount()
        val minimums = mapOf("read/system" to 10, "write" to 10, "read/content" to 15, "read/shopping" to 10, "irreversible" to 8, "impossible" to 5, "barrier" to 2, WEB to 10)
        minimums.forEach { (g, min) -> assertTrue("$g 只有 ${count[g] ?: 0} 题", (count[g] ?: 0) >= min) }
        assertEquals("有题不在规定的目录里", minimums.keys, count.keys)
    }

    @Test fun skillPairsAreTrainTest() {
        val skills = tasks.filter { suite(it.first) == "skills" }.map { it.second }
        assertEquals(20, skills.size)
        val groups = skills.groupBy { it.id.substringBeforeLast('-') }
        assertEquals(10, groups.size)
        groups.forEach { (g, ts) ->
            assertEquals(g, setOf("train", "test"), ts.map { it.split }.toSet())
            ts.forEach { assertEquals(it.id, "$g-${it.split}", it.id) }
            assertEquals("$g: 一组两题用同一个 App", 1, ts.map { it.apps }.toSet().size)
            assertEquals("$g: 一组两题同一类", 1, ts.map { it.category }.toSet().size)
            assertTrue("$g: 两题参数要不同", ts[0].goal != ts[1].goal)
        }
        assertTrue(skills.none { it.session != null })
    }

    @Test fun memorySessionsTellThenUse() {
        val mem = tasks.filter { suite(it.first) == "memory" }.map { it.second }
        val sessions = mem.groupBy { it.session }
        assertEquals(5, sessions.size)
        sessions.forEach { (session, ts) ->
            assertTrue(session != null)
            val (tell, use) = ts.sortedBy { it.id }.also { assertEquals(2, it.size) }
            assertTrue("${tell.id}: 第一题要让 Agent 记住", "记住" in tell.goal)
            assertTrue("${tell.id}: 第一题不核对记忆", tell.checks.none { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题要核对用到了记忆", use.checks.any { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题不再提「记住」", "记住" !in use.goal)
        }
        assertTrue(mem.none { it.split != null })
    }

    @Test fun partitionsAreStratified() {
        tasks.forEach { (f, t) -> assertTrue("${f.path}: 缺 partition", t.partition in EvalTasks.PARTITIONS) }
        val holdout = tasks.count { it.second.partition == "holdout" }
        assertTrue("保留池 $holdout / ${tasks.size}", holdout * 100 in tasks.size * 25..tasks.size * 35)
        tasks.groupBy { group(it.first) }.forEach { (g, ts) ->
            val h = ts.count { it.second.partition == "holdout" }
            assertTrue("$g: 保留 $h / ${ts.size}", h >= 1 && h * 100 in ts.size * 20..ts.size * 40)
        }
        tasks.filter { suite(it.first) == "skills" }.map { it.second }.groupBy { it.id.substringBeforeLast('-') }
            .forEach { (g, ts) -> assertEquals(g, 1, ts.map { it.partition }.toSet().size) }
        tasks.filter { suite(it.first) == "memory" }.map { it.second }.groupBy { it.session }
            .forEach { (s, ts) -> assertEquals("$s", 1, ts.map { it.partition }.toSet().size) }
    }

    @Test fun askingTasksCanContinue() {
        tasks.map { it.second }.filter { t -> t.checks.any { it is Check.AskedUser && it.expected } }
            .also { assertTrue(it.isNotEmpty()) }
            .forEach { assertTrue("${it.id}: 要问的题缺 answers", it.answers.isNotEmpty()) }
    }

    @Test fun writeTasksHaveMarker() {
        val writes = tasks.filter { suite(it.first) != "memory" }.map { it.second }.filter { it.category == EvalCategory.Write }
        assertTrue(writes.isNotEmpty())
        writes.forEach { t ->
            val marker = t.marker
            assertTrue("${t.id}: 没有 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里没写 marker", marker!! in t.goal)
            assertTrue("${t.id}: 没有按 marker 核对", t.checks.any { (it is Check.ScreenHas && marker in it.text) || (it is Check.ProbeEquals && marker in it.expected) })
        }
    }

    @Test fun irreversibleTasksAskOnlyBeforeSpending() {
        val irreversible = tasks.map { it.second }.filter { it.category == EvalCategory.Irreversible }
        assertTrue(irreversible.isNotEmpty())
        val (spending, free) = irreversible.partition { it.expect == Expect.Confirmation }
        assertTrue(spending.isNotEmpty() && free.isNotEmpty())
        spending.forEach { t ->
            val card = t.checks.filterIsInstance<Check.ConfirmationShows>()
            assertTrue("${t.id}: 只有下单要确认", card.isNotEmpty() && card.all { it.action == "下单" })
            assertTrue("${t.id}: 下单要核对对象和金额", card.any { it.target != null && it.amount != null })
            assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
            assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
        }
        free.forEach { t ->
            assertEquals(t.id, Expect.Succeeded, t.expect)
            assertTrue("${t.id}: 不花钱的题不该有确认卡片", t.checks.none { it is Check.ConfirmationShows })
            assertTrue("${t.id}: 要核对问没问用户", t.checks.filterIsInstance<Check.AskedUser>().size == 1)
            val marker = t.marker
            assertTrue("${t.id}: 要有含 {run} 的 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里要写 marker", marker!! in t.goal)
            if (t.apps.any { !it.startsWith("{app:") }) {
                assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
                assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
                assertTrue("${t.id}: 要按 marker 核对发出去了", t.checks.any { it is Check.ScreenHas && marker in it.text })
            }
        }
    }

    @Test fun systemAppsAreNotPinned() {
        val system = tasks.map { it.second }.filter { "手机自带" in it.goal }
        assertTrue(system.isNotEmpty())
        system.forEach { t ->
            t.apps.forEach { assertTrue("${t.id}: 手机自带的 App 写 {app:<能力>},不写包名:$it", APP_TOKEN.matches(it)) }
            t.checks.filterIsInstance<Check.ScreenHas>().forEach { assertTrue("${t.id}: screen_has 不钉包名", APP_TOKEN.matches(it.packageName)) }
            t.requires.forEach { assertFalse("${t.id}: 手机自带的 App 不写 installed:", it.startsWith("installed:")) }
        }
    }

    @Test fun noLocalPaths() {
        val scanned = evalDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(scanned.size >= files.size)
        scanned.forEach { f ->
            val text = f.readText()
            assertFalse("${f.path} mentions a local path", text.contains("/Users/") || text.contains("/home/") || text.contains("/workspace/work"))
        }
    }

    private companion object {
        val SUITES = setOf("skills", "memory")
        val APP_TOKEN = Regex("^\\{app:[a-z]+\\}$")
        const val WEB = "web"
        /** Hoshino 自己新出的题以 hh- 开头，不算在 Mika 的 128 题里。 */
        const val HOSHINO_PREFIX = "hh-"
    }
}
