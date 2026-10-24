package app.hoshino.harness.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 细线图标集（1.7px 描边，24 视口）。不依赖 material-icons，保持克制统一的视觉。
 * 用法：Icon(HIcons.Menu, contentDescription = null, tint = HoshinoTheme.colors.text)
 */
object HIcons {
    private fun line(name: String, d: String, width: Float = 1.7f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    private fun solid(name: String, d: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(d),
            fill = SolidColor(Color.Black),
        ).build()

    val Menu by lazy { line("menu", "M4 7h16M4 12h16M4 17h10") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val More by lazy { line("more", "M6 12h.01M12 12h.01M18 12h.01", 2.8f) }
    val Close by lazy { line("close", "M6 6l12 12M18 6L6 18") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7") }
    val Search by lazy { line("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4") }
    val Settings by lazy {
        line(
            "settings",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1",
        )
    }
    val ArrowUp by lazy { line("arrow_up", "M12 19V5M6 11l6-6 6 6", 2f) }
    val ArrowDown by lazy { line("arrow_down", "M12 5v14M6 13l6 6 6-6") }
    val Stop by lazy { solid("stop", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z") }
    val Play by lazy { solid("play", "M8 5.5v13a.8.8 0 0 0 1.2.7l10-6.5a.8.8 0 0 0 0-1.4l-10-6.5A.8.8 0 0 0 8 5.5z") }
    val Pause by lazy { line("pause", "M8 5v14M16 5v14", 2f) }
    val ChevronDown by lazy { line("chevron_down", "M6 9l6 6 6-6") }
    val ChevronUp by lazy { line("chevron_up", "M6 15l6-6 6 6") }
    val ChevronRight by lazy { line("chevron_right", "M9 6l6 6-6 6") }
    val Back by lazy { line("back", "M15 6l-6 6 6 6") }
    val Copy by lazy { line("copy", "M9 9h10v10H9zM5 15V5h10") }
    val Paperclip by lazy {
        line("paperclip", "M20 11.5l-7.8 7.8a5 5 0 0 1-7.1-7.1l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.5a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8")
    }
    val Image by lazy { line("image", "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 8.5h.01") }
    val Camera by lazy { line("camera", "M4 8h3l2-3h6l2 3h3v11H4zM12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z") }
    val At by lazy { line("at", "M16 12a4 4 0 1 1-8 0a4 4 0 0 1 8 0zM16 12v1.5a2.5 2.5 0 0 0 5 0V12a9 9 0 1 0-3.5 7.1") }
    val File by lazy { line("file", "M6 3h8l5 5v13H6zM14 3v5h5") }
    val Folder by lazy { line("folder", "M3 6h6l2 2h10v11H3z") }
    val Terminal by lazy { line("terminal", "M4 5h16v14H4zM8 10l3 2-3 2M13 15h3") }
    val Plug by lazy { line("plug", "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4") }
    val Sparkle by lazy {
        line("sparkle", "M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z")
    }
    val Server by lazy { line("server", "M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01") }
    val Link by lazy {
        line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    }
    val Clock by lazy { line("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2") }
    val Target by lazy {
        line("target", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7a5 5 0 1 0 0 10a5 5 0 1 0 0-10zM12 11.5h.01", 1.7f)
    }
    val Sun by lazy {
        line("sun", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    }
    val Moon by lazy { line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val Trash by lazy { line("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Edit by lazy { line("edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4") }
    val Archive by lazy { line("archive", "M3 4h18v4H3zM5 8v12h14V8M10 12h4") }
    val Fork by lazy {
        line("fork", "M7 4a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM17 4a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM12 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4zM7 8v1a3 3 0 0 0 3 3h4a3 3 0 0 0 3-3V8M12 12v4")
    }
    val Download by lazy { line("download", "M12 4v11M7 10l5 5 5-5M5 20h14") }
    val Layers by lazy { line("layers", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5") }
    val Gauge by lazy { line("gauge", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 12l4-4M7 12h.01M12 7h.01") }
    val Chart by lazy { line("chart", "M4 20h16M7 16v-5M12 16V7M17 16v-8") }
    val Shield by lazy { line("shield", "M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z") }
    val Lock by lazy { line("lock", "M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3") }
    val Warning by lazy { line("warning", "M12 4L2.5 20h19zM12 10v4M12 17h.01") }
    val Cpu by lazy { line("cpu", "M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4") }
    val Refresh by lazy { line("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v4h-4") }
    val Eye by lazy { line("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z") }
    val EyeOff by lazy {
        line("eye_off", "M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3 3.8M6.6 6.6C3.9 8.4 2 12 2 12s3.5 7 10 7a9.8 9.8 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2")
    }
    val Globe by lazy {
        line("globe", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z")
    }
    val Code by lazy { line("code", "M8 7l-5 5 5 5M16 7l5 5-5 5") }
    val Braces by lazy {
        line("braces", "M8 4c-2 0-3 1-3 3v2c0 1.5-1 3-2 3 1 0 2 1.5 2 3v2c0 2 1 3 3 3M16 4c2 0 3 1 3 3v2c0 1.5 1 3 2 3-1 0-2 1.5-2 3v2c0 2-1 3-3 3")
    }
    val Tree by lazy { line("tree", "M4 4h6v5H4zM14 15h6v5h-6zM14 4h6v5h-6zM7 9v8.5h7M10 6.5h4") }
    val Info by lazy { line("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v6M12 7.5h.01") }
    val Bulb by lazy {
        line("bulb", "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z")
    }
    val Wrench by lazy { line("wrench", "M15 4a5 5 0 0 0-4.6 7L4 17.4V20h2.6l6.4-6.4A5 5 0 0 0 20 9l-3 3-3-1-1-3z") }
    val ListIcon by lazy { line("list", "M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01", 1.9f) }
    val Question by lazy {
        line("question", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9a2.5 2.5 0 0 1 4.9.7c0 1.7-2.4 2.3-2.4 3.8M12 17h.01")
    }
    val Agents by lazy {
        line("agents", "M9 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7zM2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 6.5M18 14a6.5 6.5 0 0 1 3.5 6")
    }
    val Phone by lazy { line("phone", "M7 2h10v20H7zM11 18h2") }
    val Key by lazy { line("key", "M8 15a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM12 11h9M18 11v3M21 11v2") }
    val Message by lazy { line("message", "M4 5h16v11H9l-5 4z") }
    val Bolt by lazy { line("bolt", "M13 2L4 14h7l-1 8 9-12h-7z") }
    val Database by lazy {
        line("database", "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3")
    }
    val Palette by lazy {
        line("palette", "M12 3a9 9 0 0 0 0 18c1 0 1.5-.7 1.5-1.5 0-1.2-1-1.5-1-2.5s.7-1.5 1.5-1.5H17a4 4 0 0 0 4-4c0-4.7-4-8.5-9-8.5zM7.5 11h.01M10 7h.01M15 7.5h.01")
    }
    val Filter by lazy { line("filter", "M4 6h16M7 12h10M10 18h4") }
    val Sort by lazy { line("sort", "M7 4v16M3 16l4 4 4-4M17 20V4M13 8l4-4 4 4") }
    val Flag by lazy { line("flag", "M5 21V4M5 4h11l-2 4 2 4H5") }
    val Edit2 by lazy { line("pen_square", "M4 4h9M4 4v16h16v-9M17 3l4 4-9 9H8v-4z") }
}
