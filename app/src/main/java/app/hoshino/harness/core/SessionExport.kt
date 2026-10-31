package app.hoshino.harness.core

/** 会话导出格式。Markdown 适合阅读和分享；JSON 是完整的 dsh 事件日志（可再分析）。 */
enum class SessionExportFormat(val key: String, val label: String, val mime: String) {
    Markdown("markdown", "Markdown", "text/markdown"),
    Json("json", "JSON", "application/json"),
}

/** 一次导出的结果：保存到「下载/Hoshino」的显示路径，以及一份可直接分享的本地副本。 */
data class ExportedSession(val name: String, val displayPath: String, val shareFile: java.io.File?, val format: SessionExportFormat)

/** 校验插件返回的导出文件位置与文件名（只允许 `exports/` 下的单层文件名，不允许 `.`、`..`、斜杠）。 */
object SessionExportPaths {
    private val RELATIVE = Regex("exports/[^/\\\\]{1,300}")
    fun safeRelative(rel: String): Boolean = RELATIVE.matches(rel) && rel.substringAfter('/') !in setOf(".", "..")
    fun safeName(name: String, fallback: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim().take(180)
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
    }
}
