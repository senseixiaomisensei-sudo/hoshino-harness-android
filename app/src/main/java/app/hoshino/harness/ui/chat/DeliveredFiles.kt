package app.hoshino.harness.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一轮回答下面的交付文件卡（预览 / 保存 / 分享·打开）。 */
@Composable
fun DeliveredFiles(files: List<DeliveredFile>) {
    val client = Hoshino.client as? DshHarnessClient
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    files.forEach { row ->
        key(row.id) {
            var preview by remember(row.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).hairlineCard(HoshinoTheme.colors.surface, HoshinoTheme.colors.line).padding(12.dp)) {
                Text(row.name, style = HoshinoTheme.type.bodyMedium, color = HoshinoTheme.colors.text)
                Text("${row.bytes / 1024} KB · ${row.name.substringAfterLast('.', "文件").uppercase()}", style = HoshinoTheme.type.caption, color = HoshinoTheme.colors.text3)
                if (client != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HButton("预览", { preview = true }, compact = true)
                    HButton("保存", { scope.launch { runCatching { client.m2.save(client.m2.workspaceFile(row.path), row.name) }.onSuccess { toast(context, "已保存到下载/Hoshino") }.onFailure { toast(context, it.message ?: "保存失败") } } }, compact = true)
                    HButton("分享/打开", { runCatching { AppMedia(context).shareFile(client.m2.workspaceFile(row.path)) }.onFailure { toast(context, it.message ?: "文件失效") } }, compact = true)
                }
            }
            if (preview && client != null) HSheet(onDismiss = { preview = false }, title = row.name) { FilePreview(client, row) }
        }
    }
}

/** 预览内容。所有文件读取与解码都在 Dispatchers.IO，图片/PDF 按显示尺寸下采样。 */
@Composable
private fun FilePreview(client: DshHarnessClient, row: DeliveredFile) {
    val context = LocalContext.current
    val file = remember(row.path) { runCatching { client.m2.workspaceFile(row.path) }.getOrNull() }
    if (file == null) { Text("文件已移走"); return }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val targetWidth = with(density) { config.screenWidthDp.dp.roundToPx() }.coerceIn(320, 2048)
    val targetHeight = with(density) { 550.dp.roundToPx() }
    when (file.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif" -> {
            val bitmap by produceState<ImageBitmap?>(null, file.path, targetWidth) { value = withContext(Dispatchers.IO) { PreviewDecoder.decodeSampled(file, targetWidth, targetHeight * 2)?.asImageBitmap() } }
            bitmap?.let { androidx.compose.foundation.Image(it, row.name, Modifier.fillMaxWidth().heightIn(max = 500.dp)) } ?: ShimmerText("正在解码…", HoshinoTheme.type.caption)
        }
        "pdf" -> {
            val bitmap by produceState<ImageBitmap?>(null, file.path, targetWidth) { value = withContext(Dispatchers.IO) { PreviewDecoder.renderPdfFirstPage(file, targetWidth)?.asImageBitmap() } }
            bitmap?.let { androidx.compose.foundation.Image(it, row.name, Modifier.fillMaxWidth().heightIn(max = 550.dp)) } ?: ShimmerText("正在渲染…", HoshinoTheme.type.caption)
        }
        "mp4", "webm" -> AndroidView(factory = { android.widget.VideoView(it).apply { setVideoURI(AppMedia(context).uri(file)); setMediaController(android.widget.MediaController(it).also { c -> c.setAnchorView(this) }); start() } }, modifier = Modifier.fillMaxWidth().height(300.dp))
        "mp3", "wav", "m4a", "flac" -> {
            var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
            DisposableEffect(Unit) { onDispose { player?.release() } }
            HButton("播放 / 暂停", { if (player == null) player = android.media.MediaPlayer.create(context, AppMedia(context).uri(file)); player?.let { if (it.isPlaying) it.pause() else it.start() } })
        }
        else -> {
            val text by produceState<String?>(null, file.path) { value = withContext(Dispatchers.IO) { PreviewDecoder.text(file) } }
            val mono = file.extension.lowercase() != "docx"
            text?.let { SelectionContainer { Text(it, style = if (mono) HoshinoTheme.type.code else HoshinoTheme.type.body, color = HoshinoTheme.colors.text, modifier = Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState())) } }
                ?: ShimmerText("正在读取…", HoshinoTheme.type.caption)
        }
    }
}

internal object PreviewDecoder {
    /** 2 的幂下采样，使解码后宽高不超过请求尺寸（不会把 4000px 的图整张解进内存）。 */
    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= reqWidth || height / (sample * 2) >= reqHeight) sample *= 2
        return sample
    }

    fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight) }
        BitmapFactory.decodeFile(file.path, options)
    }.getOrNull()

    fun renderPdfFirstPage(file: File, targetWidth: Int): Bitmap? = runCatching {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val w = targetWidth
                    val h = (page.height * scale).toInt().coerceIn(1, targetWidth * 3)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                        page.render(it, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    fun text(file: File): String = when (file.extension.lowercase()) {
        "zip" -> runCatching { java.util.zip.ZipFile(file).use { it.entries().asSequence().take(2000).joinToString("\n") { e -> "${e.name} · ${e.size} B" } } }.getOrDefault("压缩包无法读取")
        "docx" -> runCatching { java.util.zip.ZipFile(file).use { zip -> val e = zip.getEntry("word/document.xml") ?: error("没有正文"); require(e.size in 0..5L * 1024 * 1024); zip.getInputStream(e).bufferedReader().use { it.readText().replace(Regex("</w:p>"), "\n").replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">") } } }.getOrDefault("无法读取 DOCX 正文，请用其他应用打开")
        else -> if (file.length() > 2 * 1024 * 1024) "文件较大，请使用其他应用打开" else runCatching { file.readText() }.getOrDefault("二进制文件，请使用其他应用打开")
    }
}
