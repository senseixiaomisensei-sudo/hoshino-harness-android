package dev.hoshino.bridge.eval

import org.json.JSONObject

/**
 * Hoshino 在这台设备上有哪些能力、一道题需要哪些能力。Mika 的题默认 Agent 什么都有（手机、网页、沙箱、记忆）；
 * Hoshino 的能力随插件装载而定（手机操作是 m2.8，记忆是 m3.0），缺能力的题按 Mika 的规矩「跳过并写明原因」，不算失败，
 * 这样双跑报告里能清楚看到「做错」和「还不会做」的区别。
 *
 * 能力从内核实际注册的工具名推出来（`selfHome/self/manifest.json` 的 `tools`，由 node-host 按真实注册表生成），不靠手写名单。
 */
object HoshinoCapabilities {
    const val PHONE = "phone"
    const val WEB = "web"
    const val SANDBOX = "sandbox"
    const val MEMORY = "memory"
    const val ASK_USER = "ask_user"

    /** 已注册工具名 → 能力。 */
    fun detect(tools: Collection<String>): Set<String> = buildSet {
        if (tools.any { it.startsWith("phone_") }) add(PHONE)
        if (tools.any { it in WEB_TOOLS }) add(WEB)
        if ("bash" in tools) add(SANDBOX)
        if (tools.any { it.startsWith("memory_") || it == "remember" }) add(MEMORY)
        if (DshRunFacts.ASK_USER in tools) add(ASK_USER)
    }

    /** `manifest.json`（`{"tools":[{"name":…},…]}`）里的工具名。 */
    fun toolsInManifest(json: String): List<String> = runCatching {
        val a = JSONObject(json).optJSONArray("tools") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    /** 一道题要的能力：碰 App 的要手机；网页题要网页；沙箱题再要沙箱；核对记忆的要记忆；核对追问的要 ask_user。 */
    fun required(t: EvalTask): Set<String> = buildSet {
        if (t.apps.isNotEmpty()) add(PHONE)
        if (t.channel == "web" || t.id.startsWith("web-")) add(WEB)
        if ("sandbox" in t.id) add(SANDBOX)
        if (t.checks.any { it is Check.MemoryUsed }) add(MEMORY)
        if (t.checks.any { it is Check.AskedUser && it.expected }) add(ASK_USER)
    }

    /** 缺的能力；不缺返回空。 */
    fun missing(t: EvalTask, have: Set<String>): Set<String> = required(t) - have

    /** 跳过原因的写法（报告里「跳过」一节）。 */
    fun skipReason(missing: Set<String>): String = Scoring.MISSING_CAPABILITY_PREFIX + missing.sorted().joinToString(",") { "$it(${LABELS[it] ?: it})" }

    private val WEB_TOOLS = setOf("web_fetch", "web_render", "web_search", "web_navigate", "web_read_page")
    private val LABELS = mapOf(PHONE to "手机操作,m2.8", WEB to "网页", SANDBOX to "沙箱命令", MEMORY to "记忆,m3.0", ASK_USER to "向用户追问")
}
