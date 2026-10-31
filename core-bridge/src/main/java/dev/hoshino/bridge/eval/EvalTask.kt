// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import org.json.JSONArray
import org.json.JSONObject

/**
 * 评测题的格式与 Mika `eval/tasks/` 完全兼容（同一份 JSON 两边都能读）：字段、取值、校验规则逐条照抄 Mika `core/eval/EvalTask.kt`，
 * 只把 kotlinx.serialization 换成 org.json。解析是严格的：不认识的字段、写错的类型直接报错（Mika 的解码器也是这样），
 * 这样一道题不会在一边能跑、另一边被悄悄忽略。
 */
enum class EvalCategory(val wire: String) {
    Read("read"), Write("write"), Irreversible("irreversible"), Impossible("impossible"), Barrier("barrier");

    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("未知 category「$wire」") }
}

enum class Expect(val wire: String) {
    Succeeded("succeeded"), Failed("failed"), Confirmation("confirmation"), Takeover("takeover");

    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("未知 expect「$wire」") }
}

/** 客观核对项；JSON 里以 "type" 区分。 */
sealed interface Check {
    val type: String

    data class AnswerContains(val all: List<String>) : Check { override val type get() = "answer_contains" }
    data class AnswerRegex(val pattern: String) : Check { override val type get() = "answer_regex" }
    /** 回答条目必须都出现在本任务读到的材料里（见 [Grounding]）。 */
    data class AnswerGrounded(val minItems: Int = 1) : Check { override val type get() = "answer_grounded" }
    /** [packageName] 可写成 `{app:<能力>}`（见 [APP_CAPABILITIES]），跑的时候换成这台手机上的 App。 */
    data class ScreenHas(val packageName: String, val text: String, val deepLink: String? = null) : Check { override val type get() = "screen_has" }
    /** probe 必须是 [PROBE_ALLOWLIST] 里的名字。 */
    data class ProbeEquals(val probe: String, val expected: String, val contains: Boolean = false) : Check { override val type get() = "probe_equals" }
    /** 这次任务开头带上了至少 [min] 条记忆。记忆题的第二题用。 */
    data class MemoryUsed(val min: Int = 1) : Check { override val type get() = "memory_used" }
    /** 这次任务里 Agent 有没有向用户追问（ask_user）：[expected] 为 true 时须问过，false 时须一次没问。 */
    data class AskedUser(val expected: Boolean = true) : Check { override val type get() = "asked_user" }
    /** 任务结束后打开 [url] 读整页，要能找到 [text]（忽略空白）。 */
    data class WebHas(val url: String, val text: String) : Check { override val type get() = "web_has" }
    data class ConfirmationShows(
        val action: String? = null,
        val target: String? = null,
        val content: String? = null,
        val amount: String? = null,
    ) : Check { override val type get() = "confirmation_shows" }
}

/** 原题的一个变体。[checks] 为空沿用原题的核对项。 */
data class Variant(val goal: String, val checks: List<Check>? = null)

/**
 * 一道评测题。goal、marker、核对项里的字符串可写 `{run}` 和 `{probe:<名字>}`，跑前由 [EvalTasks.substitute] 替换；
 * `probesBefore` 是「占位名 → 白名单探针名」。`apps` 和 screen_has 的包名可写 `{app:<能力>}`。
 * `requires`：`installed:<包名>`；`app:<能力>`；`signedIn:<包名或网站>`（旧写法 `login:`、`site:` 照样认）。
 */
data class EvalTask(
    val id: String,
    val goal: String,
    val apps: List<String>,
    val category: EvalCategory,
    val requires: List<String> = emptyList(),
    val expect: Expect,
    val checks: List<Check>,
    val maxSteps: Int? = null,
    val timeoutSec: Int? = null,
    val marker: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val probesBefore: Map<String, String> = emptyMap(),
    /** 给评审看的说明，评测器不用。 */
    val note: String? = null,
    /** 配对题：train 或 test。 */
    val split: String? = null,
    /** 同一 session 的题在同一个对话里按 id 顺序跑（记忆题：先告诉、再用上）。 */
    val session: String? = null,
    val variants: List<Variant> = emptyList(),
    /** 强制渠道："web" 或 "app"。 */
    val channel: String? = null,
    /** 题目分池：tune 或 holdout（holdout 只在决定发版时看）。 */
    val partition: String? = null,
    /** 撞上这些障碍后交给用户的，单列成「交给用户」；为空时按类别取默认（[Scoring.takeoverKinds]）。 */
    val acceptTakeoverOn: List<String>? = null,
)

data class AppIntent(val action: String, val category: String? = null)

private const val MAIN = "android.intent.action.MAIN"

val APP_CAPABILITIES: Map<String, List<AppIntent>> = mapOf(
    "alarm" to listOf(AppIntent("android.intent.action.SET_ALARM"), AppIntent("android.intent.action.SHOW_ALARMS")),
    "calendar" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALENDAR")),
    "calculator" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALCULATOR")),
    "contacts" to listOf(AppIntent(MAIN, "android.intent.category.APP_CONTACTS")),
    "files" to listOf(AppIntent(MAIN, "android.intent.category.APP_FILES"), AppIntent("android.intent.action.OPEN_DOCUMENT_TREE")),
    "weather" to listOf(AppIntent(MAIN, "android.intent.category.APP_WEATHER")),
    "notes" to emptyList(),
)

object AppCapabilities {
    private val COMPONENT = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/\\S+")
    private const val SYSTEM_PACKAGE = "android"

    /** `cmd package resolve-activity --brief` / `query-activities --brief` 输出里的包名，按出现顺序去重。 */
    fun packagesIn(output: String): List<String> =
        output.lines().mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }.filter { it != SYSTEM_PACKAGE }.distinct()

    /** 查某个 Intent 的 shell 命令（参数来自 [APP_CAPABILITIES]，任务文件写不进去）。 */
    fun commands(i: AppIntent): List<String> {
        val args = "-a ${i.action}" + (i.category?.let { " -c $it" } ?: "")
        return listOf("cmd package resolve-activity --brief $args", "cmd package query-activities --brief $args")
    }

    /** 跑完后 `{app:<能力>}` 取哪个包：Agent 打开过的候选优先，其次第一个候选；没有候选时只认唯一一个「打字时在前台的 App」。 */
    fun choose(candidates: List<String>, launched: List<String>, own: String? = null, written: List<String> = emptyList()): String? {
        val used = launched.filter { it != own && it != SYSTEM_PACKAGE }
        candidates.firstOrNull { it in used }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return written.filter { it != own && it != SYSTEM_PACKAGE }.distinct().singleOrNull()
    }
}

/** [EvalTask.channel] 的取值与加在目标前的说法。 */
val CHANNEL_PREFIX = mapOf("web" to "用网页", "app" to "用 App")

/** 只读设备探针：名字 → shell 命令。评测器只执行这里列出的命令，任务文件不能写任意命令。 */
val PROBE_ALLOWLIST: Map<String, String> = mapOf(
    "android_release" to "getprop ro.build.version.release",
    "android_sdk" to "getprop ro.build.version.sdk",
    "device_model" to "getprop ro.product.model",
    "device_manufacturer" to "getprop ro.product.manufacturer",
    "timezone" to "getprop persist.sys.timezone",
    "date_today" to "date +%Y-%m-%d",
    "time_12_24" to "settings get system time_12_24",
    "font_scale" to "settings get system font_scale",
    "airplane_mode_on" to "settings get global airplane_mode_on",
    "wifi_on" to "settings get global wifi_on",
    "bluetooth_on" to "settings get global bluetooth_on",
    "auto_time" to "settings get global auto_time",
    "default_browser" to "cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com",
    "default_home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    "storage_root" to "ls -1 /sdcard/",
)

object EvalTasks {
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SPLITS = setOf("train", "test")
    val PARTITIONS = setOf("tune", "holdout")
    private val PROBE_REF = Regex("""\{probe:([^}]*)\}""")
    private val APP_REF = Regex("""\{app:([^}]*)\}""")
    private val REQUIREMENT_KINDS = setOf("installed", "login", "site", "signedIn", "app")
    private val TASK_KEYS = setOf(
        "id", "goal", "apps", "category", "requires", "expect", "checks", "maxSteps", "timeoutSec", "marker", "answers",
        "probesBefore", "note", "split", "session", "variants", "channel", "partition", "acceptTakeoverOn",
    )
    private val CHECK_KEYS = mapOf(
        "answer_contains" to setOf("all"), "answer_regex" to setOf("pattern"), "answer_grounded" to setOf("minItems"),
        "screen_has" to setOf("packageName", "text", "deepLink"), "probe_equals" to setOf("probe", "expected", "contains"),
        "memory_used" to setOf("min"), "asked_user" to setOf("expected"), "web_has" to setOf("url", "text"),
        "confirmation_shows" to setOf("action", "target", "content", "amount"),
    )

    // ---------- 解析 ----------

    fun parse(json: String): EvalTask = parse(JSONObject(json))

    fun parse(o: JSONObject): EvalTask {
        unknown(o, TASK_KEYS, "题目")
        return EvalTask(
            id = o.str("id"),
            goal = o.str("goal"),
            apps = o.strings("apps"),
            category = EvalCategory.of(o.str("category")),
            requires = o.optStrings("requires"),
            expect = Expect.of(o.str("expect")),
            checks = o.getJSONArray("checks").objects().map(::check),
            maxSteps = o.optIntOrNull("maxSteps"),
            timeoutSec = o.optIntOrNull("timeoutSec"),
            marker = o.optStr("marker"),
            answers = o.optMap("answers"),
            probesBefore = o.optMap("probesBefore"),
            note = o.optStr("note"),
            split = o.optStr("split"),
            session = o.optStr("session"),
            variants = o.optJSONArray("variants")?.objects()?.map { v ->
                unknown(v, setOf("goal", "checks"), "variant")
                Variant(v.str("goal"), v.optJSONArray("checks")?.objects()?.map(::check))
            }.orEmpty(),
            channel = o.optStr("channel"),
            partition = o.optStr("partition"),
            acceptTakeoverOn = if (o.has("acceptTakeoverOn") && !o.isNull("acceptTakeoverOn")) o.strings("acceptTakeoverOn") else null,
        )
    }

    private fun check(o: JSONObject): Check {
        val type = o.str("type")
        val keys = CHECK_KEYS[type] ?: throw IllegalArgumentException("未知核对项类型「$type」")
        unknown(o, keys + "type", "核对项 $type")
        return when (type) {
            "answer_contains" -> Check.AnswerContains(o.strings("all"))
            "answer_regex" -> Check.AnswerRegex(o.str("pattern"))
            "answer_grounded" -> Check.AnswerGrounded(o.optIntOrNull("minItems") ?: 1)
            "screen_has" -> Check.ScreenHas(o.str("packageName"), o.str("text"), o.optStr("deepLink"))
            "probe_equals" -> Check.ProbeEquals(o.str("probe"), o.str("expected"), o.optBool("contains") ?: false)
            "memory_used" -> Check.MemoryUsed(o.optIntOrNull("min") ?: 1)
            "asked_user" -> Check.AskedUser(o.optBool("expected") ?: true)
            "web_has" -> Check.WebHas(o.str("url"), o.str("text"))
            else -> Check.ConfirmationShows(o.optStr("action"), o.optStr("target"), o.optStr("content"), o.optStr("amount"))
        }
    }

    private fun unknown(o: JSONObject, known: Set<String>, what: String) {
        val extra = o.keys().asSequence().filter { it !in known }.toList()
        require(extra.isEmpty()) { "$what 有不认识的字段：${extra.joinToString()}" }
    }

    // ---------- 序列化（写 scores.jsonl、对比报告用；与 Mika 的字段一致） ----------

    fun toJson(t: EvalTask): JSONObject = JSONObject().apply {
        put("id", t.id); put("goal", t.goal); put("apps", JSONArray(t.apps)); put("category", t.category.wire)
        if (t.requires.isNotEmpty()) put("requires", JSONArray(t.requires))
        put("expect", t.expect.wire); put("checks", JSONArray(t.checks.map(::checkJson)))
        t.maxSteps?.let { put("maxSteps", it) }; t.timeoutSec?.let { put("timeoutSec", it) }; t.marker?.let { put("marker", it) }
        if (t.answers.isNotEmpty()) put("answers", JSONObject(t.answers)); if (t.probesBefore.isNotEmpty()) put("probesBefore", JSONObject(t.probesBefore))
        t.note?.let { put("note", it) }; t.split?.let { put("split", it) }; t.session?.let { put("session", it) }
        if (t.variants.isNotEmpty()) put("variants", JSONArray(t.variants.map { v -> JSONObject().put("goal", v.goal).apply { v.checks?.let { put("checks", JSONArray(it.map(::checkJson))) } } }))
        t.channel?.let { put("channel", it) }; t.partition?.let { put("partition", it) }; t.acceptTakeoverOn?.let { put("acceptTakeoverOn", JSONArray(it)) }
    }

    fun checkJson(c: Check): JSONObject = JSONObject().put("type", c.type).apply {
        when (c) {
            is Check.AnswerContains -> put("all", JSONArray(c.all))
            is Check.AnswerRegex -> put("pattern", c.pattern)
            is Check.AnswerGrounded -> put("minItems", c.minItems)
            is Check.ScreenHas -> { put("packageName", c.packageName); put("text", c.text); c.deepLink?.let { put("deepLink", it) } }
            is Check.ProbeEquals -> { put("probe", c.probe); put("expected", c.expected); put("contains", c.contains) }
            is Check.MemoryUsed -> put("min", c.min)
            is Check.AskedUser -> put("expected", c.expected)
            is Check.WebHas -> { put("url", c.url); put("text", c.text) }
            is Check.ConfirmationShows -> { c.action?.let { put("action", it) }; c.target?.let { put("target", it) }; c.content?.let { put("content", it) }; c.amount?.let { put("amount", it) } }
        }
    }

    /** 题目里出现的全部字符串（Mika 是在整份 JSON 编码上找占位符，这里等价地逐字段收集）。 */
    private fun strings(t: EvalTask): List<String> = buildList {
        add(t.id); add(t.goal); addAll(t.apps); addAll(t.requires); t.marker?.let(::add); t.note?.let(::add); t.split?.let(::add); t.session?.let(::add)
        t.channel?.let(::add); t.partition?.let(::add); t.acceptTakeoverOn?.let(::addAll)
        addAll(t.answers.keys); addAll(t.answers.values); addAll(t.probesBefore.keys); addAll(t.probesBefore.values)
        (t.checks + t.variants.flatMap { it.checks.orEmpty() }).forEach { addAll(checkStrings(it)) }
        t.variants.forEach { add(it.goal) }
    }

    private fun checkStrings(c: Check): List<String> = when (c) {
        is Check.AnswerContains -> c.all
        is Check.AnswerRegex -> listOf(c.pattern)
        is Check.ScreenHas -> listOfNotNull(c.packageName, c.text, c.deepLink)
        is Check.ProbeEquals -> listOf(c.probe, c.expected)
        is Check.WebHas -> listOf(c.url, c.text)
        is Check.ConfirmationShows -> listOfNotNull(c.action, c.target, c.content, c.amount)
        is Check.AnswerGrounded, is Check.MemoryUsed, is Check.AskedUser -> emptyList()
    }

    /** 题目里用到的 `{app:<能力>}`。 */
    fun appCapabilities(t: EvalTask): Set<String> = strings(t).flatMap { s -> APP_REF.findAll(s).map { it.groupValues[1] } }.toSet()

    /** 把 `{app:<能力>}` 换成 [apps] 里的包名；没有的能力原样留着，`apps` 列表里没换上的去掉。 */
    fun resolveApps(t: EvalTask, apps: Map<String, String>): EvalTask {
        fun s(x: String) = APP_REF.replace(x) { m -> apps[m.groupValues[1]] ?: m.value }
        return t.copy(
            apps = t.apps.map(::s).filterNot { APP_REF.containsMatchIn(it) }.distinct(),
            checks = t.checks.map { c -> if (c is Check.ScreenHas) c.copy(packageName = s(c.packageName)) else c },
        )
    }

    /** 目标前加渠道说法；目标里已经这么说了（不计空白）就不再加。 */
    fun withChannel(goal: String, channel: String?): String {
        val prefix = channel?.let { CHANNEL_PREFIX[it] } ?: return goal
        fun squash(x: String) = x.filterNot(Char::isWhitespace)
        return if (squash(prefix) in squash(goal)) goal else prefix + goal
    }

    /** 原题的各个变体，编号为 `<原题>~v<n>`。 */
    fun variantsOf(t: EvalTask): List<EvalTask> = t.variants.mapIndexed { i, v ->
        t.copy(id = "${t.id}~v${i + 1}", goal = v.goal, checks = v.checks ?: t.checks, variants = emptyList())
    }

    /** 单题合法性（含各变体）；空列表 = 合法。 */
    fun validate(t: EvalTask): List<String> {
        val errs = mutableListOf<String>()
        variantsOf(t).forEachIndexed { i, v -> validate(v.copy(id = t.id)).forEach { errs += "${t.id}: variant[${i + 1}]: $it" } }
        if (!ID.matches(t.id)) errs += "id「${t.id}」须为小写字母数字加连字符"
        if (t.goal.isBlank()) errs += "${t.id}: goal 为空"
        t.channel?.let { if (it !in CHANNEL_PREFIX) errs += "${t.id}: channel 只能是 ${CHANNEL_PREFIX.keys.joinToString("/")}" }
        t.maxSteps?.let { if (it <= 0) errs += "${t.id}: maxSteps 须为正数" }
        t.split?.let { if (it !in SPLITS) errs += "${t.id}: split 须为 train 或 test" }
        t.partition?.let { if (it !in PARTITIONS) errs += "${t.id}: partition 须为 tune 或 holdout" }
        t.session?.let { if (!ID.matches(it)) errs += "${t.id}: session「$it」须为小写字母数字加连字符" }
        t.timeoutSec?.let { if (it <= 0) errs += "${t.id}: timeoutSec 须为正数" }
        t.requires.forEach { r ->
            val kind = r.substringBefore(':')
            if (kind !in REQUIREMENT_KINDS || r.substringAfter(':', "").isBlank()) errs += "${t.id}: 不认识的前置条件「$r」"
            if (kind == "app" && r.substringAfter(':') !in APP_CAPABILITIES) errs += "${t.id}: 前置条件「$r」不是已知的 App 能力"
        }
        appCapabilities(t).forEach { if (it !in APP_CAPABILITIES) errs += "${t.id}: {app:$it} 不是已知的 App 能力" }
        t.probesBefore.forEach { (name, probe) ->
            if (probe !in PROBE_ALLOWLIST) errs += "${t.id}: probesBefore.$name 用了白名单外的探针「$probe」"
        }
        if (t.checks.isEmpty() && (t.expect == Expect.Succeeded || t.expect == Expect.Confirmation)) {
            errs += "${t.id}: expect=${t.expect.wire} 至少要有一条核对项"
        }
        t.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ProbeEquals -> {
                    if (c.probe !in PROBE_ALLOWLIST) errs += "${t.id}: checks[$i] 用了白名单外的探针「${c.probe}」"
                    if (c.expected.isBlank()) errs += "${t.id}: checks[$i] expected 为空"
                }
                is Check.AnswerGrounded -> if (c.minItems < 1) errs += "${t.id}: checks[$i] minItems 须 ≥ 1"
                is Check.AnswerRegex ->
                    if (c.pattern.isEmpty()) errs += "${t.id}: checks[$i] pattern 为空"
                    else runCatching { Regex(c.pattern) }
                        .onFailure { errs += "${t.id}: checks[$i] 正则无效:${it.message}" }
                        .onSuccess { if (it.containsMatchIn("")) errs += "${t.id}: checks[$i] 正则能匹配空串,核对不了任何东西" }
                is Check.AnswerContains ->
                    if (c.all.isEmpty() || c.all.any { it.isBlank() }) errs += "${t.id}: checks[$i] all 为空或含空串"
                is Check.ConfirmationShows -> {
                    val fields = listOf(c.action, c.target, c.content, c.amount)
                    if (fields.all { it == null }) errs += "${t.id}: checks[$i] 没写任何要核对的字段"
                    if (fields.any { it != null && it.isBlank() }) errs += "${t.id}: checks[$i] 有字段为空白"
                }
                is Check.ScreenHas -> if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                is Check.MemoryUsed -> if (c.min < 1) errs += "${t.id}: checks[$i] min 须 ≥ 1"
                is Check.AskedUser -> Unit
                is Check.WebHas -> {
                    val u = c.url.trim().lowercase()
                    if (!u.startsWith("https://") && !u.startsWith("http://")) errs += "${t.id}: checks[$i] url 须是 http(s) 网址"
                    if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                }
            }
        }
        val referenced = strings(t).flatMap { s -> PROBE_REF.findAll(s).map { it.groupValues[1] } }.toSet()
        (referenced - t.probesBefore.keys).forEach { errs += "${t.id}: 占位符 {probe:$it} 没在 probesBefore 里声明" }
        return errs
    }

    /** 整个任务集：逐题校验，再查 id 重复。 */
    fun validateAll(ts: List<EvalTask>): List<String> =
        ts.flatMap { validate(it) } +
            ts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "id「$it」重复" }

    /** 替换 `{run}` 和 `{probe:<名字>}`；probes 是 probesBefore 的执行结果（占位名 → 输出）。 */
    fun substitute(t: EvalTask, run: String, probes: Map<String, String>): EvalTask {
        fun s(x: String): String = PROBE_REF.replace(x.replace("{run}", run)) { m ->
            probes[m.groupValues[1]]?.trim() ?: m.value
        }
        // 正则里插入的探针值按字面量处理（Kotlin 的 replace(transform) 按原样拼接，不解释 $ 与 \）
        fun re(x: String): String = PROBE_REF.replace(x.replace("{run}", Regex.escape(run))) { m ->
            probes[m.groupValues[1]]?.let { Regex.escape(it.trim()) } ?: m.value
        }
        val checks = t.checks.map { c ->
            when (c) {
                is Check.AnswerContains -> c.copy(all = c.all.map(::s))
                is Check.AnswerRegex -> c.copy(pattern = re(c.pattern))
                is Check.AnswerGrounded, is Check.MemoryUsed, is Check.AskedUser -> c
                is Check.ScreenHas -> c.copy(text = s(c.text), deepLink = c.deepLink?.let(::s))
                is Check.WebHas -> c.copy(url = s(c.url), text = s(c.text))
                is Check.ProbeEquals -> c.copy(expected = s(c.expected))
                is Check.ConfirmationShows -> Check.ConfirmationShows(c.action?.let(::s), c.target?.let(::s), c.content?.let(::s), c.amount?.let(::s))
            }
        }
        return t.copy(
            goal = withChannel(s(t.goal), t.channel),
            marker = t.marker?.let(::s),
            checks = checks,
            answers = t.answers.mapValues { s(it.value) },
        )
    }

    // ---------- org.json 小工具：类型不对就报错，不悄悄当成默认值 ----------

    private fun JSONObject.str(k: String): String = get(k).let { require(it is String) { "字段 $k 须是字符串" }; it }
    private fun JSONObject.optStr(k: String): String? = if (!has(k) || isNull(k)) null else str(k)
    private fun JSONObject.optIntOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else get(k).let { require(it is Int || (it is Number && it.toDouble() % 1.0 == 0.0)) { "字段 $k 须是整数" }; (it as Number).toInt() }
    private fun JSONObject.optBool(k: String): Boolean? = if (!has(k) || isNull(k)) null else get(k).let { require(it is Boolean) { "字段 $k 须是 true/false" }; it }
    private fun JSONObject.strings(k: String): List<String> = getJSONArray(k).let { a -> (0 until a.length()).map { a.get(it).let { v -> require(v is String) { "字段 $k 须是字符串数组" }; v } } }
    private fun JSONObject.optStrings(k: String): List<String> = if (!has(k) || isNull(k)) emptyList() else strings(k)
    private fun JSONObject.optMap(k: String): Map<String, String> = if (!has(k) || isNull(k)) emptyMap() else getJSONObject(k).let { m ->
        m.keys().asSequence().associateWith { key -> m.get(key).let { v -> require(v is String) { "字段 $k.$key 须是字符串" }; v } }
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
