package app.hoshino.harness.phone.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * On-demand OCR models (PL1). Nothing is bundled and nothing is fetched in the background: the models are
 * downloaded only when the user taps "下载文字识别模型" in the phone setup wizard. Sources are pinned to one
 * immutable commit of the RapidOCR model repository (PaddleOCR PP-OCRv4 mobile exported to ONNX, Apache-2.0)
 * and every file is verified by SHA-256 before it is used; a mismatch deletes the file.
 *
 * Size cost (measured on the 1.22.0 AAR): ONNX Runtime adds ~6.6 MB to the arm64 APK (18.3 MB installed,
 * native libs are extracted), the two models are 15.6 MB downloaded once.
 */
object OcrModels {
    data class Model(val name: String, val url: String, val sha256: String, val bytes: Long)

    const val SOURCE_COMMIT = "1cfba2e90fc938db55889873735088de210cc173"
    private const val BASE = "https://huggingface.co/SWHL/RapidOCR/resolve/$SOURCE_COMMIT/PP-OCRv4/"
    val DET = Model("ch_PP-OCRv4_det_infer.onnx", BASE + "ch_PP-OCRv4_det_infer.onnx", "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9", 4_745_517)
    val REC = Model("ch_PP-OCRv4_rec_infer.onnx", BASE + "ch_PP-OCRv4_rec_infer.onnx", "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b", 10_857_958)
    val ALL = listOf(DET, REC)

    enum class State { Missing, Downloading, Ready, Failed }

    private val _state = MutableStateFlow(State.Missing)
    val progress = MutableStateFlow(0f)
    private val lock = Mutex()
    @Volatile private var engine: OnnxOcr? = null

    fun dir(context: Context) = File(context.filesDir, "ocr")
    fun file(context: Context, m: Model) = File(dir(context), m.name)

    fun state(context: Context): State {
        if (_state.value == State.Downloading) return State.Downloading
        val present = ALL.all { file(context, it).length() == it.bytes }
        if (present && _state.value != State.Ready) _state.value = State.Ready
        return if (present) State.Ready else _state.value.takeIf { it == State.Failed } ?: State.Missing
    }

    fun stateFlow(context: Context): StateFlow<State> { state(context); return _state.asStateFlow() }

    /** The engine when the models are present (lazy, one per process). Sizes are checked here, SHA-256 at download. */
    fun engine(context: Context): PhoneOcr? {
        engine?.let { return it }
        if (state(context) != State.Ready) return null
        return synchronized(this) { engine ?: OnnxOcr(file(context, DET).path, file(context, REC).path).also { engine = it } }
    }

    /** User-initiated download with SHA-256 verification. Returns null on success or a short error. */
    suspend fun download(context: Context): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            _state.value = State.Downloading
            progress.value = 0f
            val total = ALL.sumOf { it.bytes }.toFloat()
            var done = 0L
            try {
                dir(context).mkdirs()
                for (m in ALL) {
                    val target = file(context, m)
                    if (target.length() == m.bytes && sha256(target) == m.sha256) { done += m.bytes; continue }
                    val tmp = File(target.path + ".part")
                    val conn = (URL(m.url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true }
                    try {
                        check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                        conn.inputStream.use { input -> tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress.value = done / total }
                        } }
                    } finally { conn.disconnect() }
                    if (tmp.length() != m.bytes || sha256(tmp) != m.sha256) { tmp.delete(); error("checksum mismatch for ${m.name}") }
                    check(tmp.renameTo(target)) { "rename failed" }
                }
                _state.value = State.Ready
                null
            } catch (e: Exception) {
                _state.value = State.Failed
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun delete(context: Context) {
        synchronized(this) { engine?.close(); engine = null }
        dir(context).deleteRecursively()
        _state.value = State.Missing
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Android adapter: Bitmap → ARGB pixels → [OcrPipeline]. One recognition at a time. */
class OnnxOcr(det: String, rec: String) : PhoneOcr, AutoCloseable {
    private val pipeline by lazy { OcrPipeline(det, rec, threads = minOf(4, Runtime.getRuntime().availableProcessors())) }
    private val lock = Mutex()
    override val ready: Boolean = true

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = lock.withLock {
        withContext(Dispatchers.Default) {
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pipeline.run(px, bitmap.width, bitmap.height)
        }
    }

    override fun close() = pipeline.close()
}
