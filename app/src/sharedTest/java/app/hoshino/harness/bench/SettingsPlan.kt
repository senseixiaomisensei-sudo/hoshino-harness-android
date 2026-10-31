package app.hoshino.harness.bench

import org.json.JSONObject

/** The fixed Settings plan (20 steps). Only tap targets vary by ROM/language, and they come from a list of read-only pages. */
object SettingsPlan {
    /** Pages that only open a sub-screen when tapped (no toggle on the row itself), HyperOS/MIUI names first, then AOSP. */
    val SAFE_TARGETS = listOf(
        "我的设备", "显示与亮度", "声音与振动", "通知与控制中心", "桌面", "壁纸与个性化", "锁屏", "电池", "存储", "关于手机",
        "显示", "声音", "通知", "壁纸", "Display", "Sound & vibration", "Sound", "Notifications", "Battery", "Storage", "Wallpaper & style", "About phone",
    )

    fun pickTargets(screen: String): List<String> {
        val lines = screen.lines().map { it.trim() }
        // Exact element names first (the rendered screen quotes each name), so "显示" never stands in for "显示与亮度".
        val exact = SAFE_TARGETS.filter { t -> lines.any { l -> l.contains("\"$t\"") } }
        if (exact.isNotEmpty()) return exact.take(4)
        // Some ROMs merge the summary into the row name ("显示与亮度 自动亮度…"): accept a name that starts with the entry, one entry per row.
        val used = mutableSetOf<String>()
        return SAFE_TARGETS.filter { t -> lines.firstOrNull { l -> l !in used && l.contains("\"$t") }?.also { used += it } != null }.take(4)
    }

    fun build(pkg: String, targets: List<String>): List<JSONObject> {
        require(targets.isNotEmpty())
        fun t(i: Int) = targets[i % targets.size]
        fun s(action: String, fill: JSONObject.() -> Unit = {}) = JSONObject().put("action", action).apply(fill)
        fun tap(target: String) = s("tap") { put("target", target) }
        fun scroll(d: String) = s("scroll") { put("direction", d) }
        val back = { s("back") }
        return listOf(
            s("open_app") { put("app", pkg).put("expect", JSONObject().put("app", pkg).put("timeout_ms", 8_000)) },
            scroll("down"), scroll("up"), scroll("down"), scroll("up"),
            tap(t(0)), scroll("down"), back(),
            tap(t(1)), back(),
            tap(t(2)), scroll("down"), scroll("up"), back(),
            tap(t(3)), back(),
            scroll("down"), scroll("up"),
            tap(t(0)), back(),
        ).also { check(it.size == 20) }
    }
}
