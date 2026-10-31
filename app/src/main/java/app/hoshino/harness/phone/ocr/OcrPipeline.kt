package app.hoshino.harness.phone.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOCR PP-OCRv4 mobile (Apache-2.0, ONNX export by RapidOCR) on ONNX Runtime (MIT). Pure JVM code on
 * ARGB pixels so the same pipeline runs in unit tests on a desktop JVM and on Android (PL1: no ML Kit/GMS).
 *
 * Detection: DB text detector on the image resized so the longer side is <= [DET_MAX_SIDE] (multiple of 32),
 * BGR channels normalised with ImageNet mean/std (PaddleOCR's DetResizeForTest + NormalizeImage), then
 * [DbPostProcess]. Recognition: each box cropped, resized to height 48, normalised to [-1, 1], CRNN/SVTR
 * output decoded greedily by [CtcDecoder] with the character table stored in the model's `character` metadata.
 */
class OcrPipeline(detModel: String, recModel: String, threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det = env.createSession(detModel, options)
    private val rec = env.createSession(recModel, options)
    private val decoder = CtcDecoder(CtcDecoder.table(rec.metadata.customMetadata["character"] ?: error("recognition model has no character table")))

    fun run(argb: IntArray, width: Int, height: Int): List<OcrLine> {
        val boxes = detect(argb, width, height)
        return boxes.mapNotNull { b ->
            val (text, conf) = recognize(argb, width, height, b)
            if (text.isBlank()) null else OcrLine(text.trim(), conf, b.left, b.top, b.right, b.bottom)
        }
    }

    fun detect(argb: IntArray, width: Int, height: Int): List<Box> {
        val scale = min(1.0, DET_MAX_SIDE.toDouble() / max(width, height))
        val dw = max(32, ((width * scale) / 32.0).roundToInt() * 32)
        val dh = max(32, ((height * scale) / 32.0).roundToInt() * 32)
        val img = Pixels.resize(argb, width, height, dw, dh)
        val input = FloatArray(3 * dw * dh)
        val plane = dw * dh
        for (i in 0 until plane) {
            val p = img[i]
            input[i] = (((p and 0xff) / 255f) - MEAN[0]) / STD[0]               // B
            input[plane + i] = ((((p shr 8) and 0xff) / 255f) - MEAN[1]) / STD[1]  // G
            input[2 * plane + i] = ((((p shr 16) and 0xff) / 255f) - MEAN[2]) / STD[2] // R
        }
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { r ->
                val out = r.get(0) as OnnxTensor
                val fb = out.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        return DbPostProcess.boxes(prob, dw, dh).map { b ->
            Box(
                (b.left * width / dw).coerceIn(0, width - 1), (b.top * height / dh).coerceIn(0, height - 1),
                (b.right * width / dw).coerceIn(1, width), (b.bottom * height / dh).coerceIn(1, height), b.score,
            )
        }.filter { it.right - it.left >= 3 && it.bottom - it.top >= 3 }
    }

    fun recognize(argb: IntArray, width: Int, height: Int, b: Box): Pair<String, Float> {
        val bw = b.right - b.left
        val bh = b.bottom - b.top
        val crop = IntArray(bw * bh)
        for (y in 0 until bh) System.arraycopy(argb, (b.top + y) * width + b.left, crop, y * bw, bw)
        val rw = min(REC_MAX_WIDTH, max(8, ceil(REC_HEIGHT * bw.toDouble() / bh).toInt()))
        val w = max(REC_MIN_WIDTH, rw)
        val img = Pixels.resize(crop, bw, bh, rw, REC_HEIGHT)
        val plane = w * REC_HEIGHT
        val input = FloatArray(3 * plane) // zero padding on the right (= mid grey after normalisation)
        for (y in 0 until REC_HEIGHT) for (x in 0 until rw) {
            val p = img[y * rw + x]
            val i = y * w + x
            input[i] = ((p and 0xff) / 255f - 0.5f) / 0.5f
            input[plane + i] = (((p shr 8) and 0xff) / 255f - 0.5f) / 0.5f
            input[2 * plane + i] = (((p shr 16) and 0xff) / 255f - 0.5f) / 0.5f
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), w.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { r ->
                val out = r.get(0) as OnnxTensor
                val shape = out.info.shape
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining()).also { fb.get(it) }
                decoder.decode(data, shape[1].toInt(), shape[2].toInt())
            }
        }
    }

    override fun close() { runCatching { det.close() }; runCatching { rec.close() }; runCatching { options.close() } }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float = 1f)

    companion object {
        const val DET_MAX_SIDE = 1280
        const val REC_HEIGHT = 48
        const val REC_MIN_WIDTH = 320
        const val REC_MAX_WIDTH = 1600
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** DB post-processing: threshold → 8-connected components → score filter → unclip (expand) → boxes. */
object DbPostProcess {
    const val THRESH = 0.3f
    const val BOX_THRESH = 0.6f
    const val UNCLIP = 1.6
    const val MIN_SIZE = 3
    const val MAX_BOXES = 1000

    fun boxes(prob: FloatArray, w: Int, h: Int, thresh: Float = THRESH, boxThresh: Float = BOX_THRESH, unclip: Double = UNCLIP): List<OcrPipeline.Box> {
        val seen = BooleanArray(w * h)
        val out = mutableListOf<OcrPipeline.Box>()
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || prob[start] <= thresh) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            var sum = 0.0; var n = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                sum += prob[p]; n++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (!seen[q] && prob[q] > thresh) { seen[q] = true; stack[sp++] = q }
                }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (min(bw, bh) < MIN_SIZE) continue
            val score = (sum / n).toFloat()
            if (score < boxThresh) continue
            val d = bw.toDouble() * bh * unclip / (2.0 * (bw + bh))
            out += OcrPipeline.Box(
                max(0, (x0 - d).toInt()), max(0, (y0 - d).toInt()),
                min(w, (x1 + 1 + d).toInt()), min(h, (y1 + 1 + d).toInt()), score,
            )
            if (out.size >= MAX_BOXES) break
        }
        return out.sortedWith(compareBy({ it.top / 10 }, { it.left }))
    }
}

/** Greedy CTC: argmax per step, drop blanks (index 0) and repeats; confidence = mean max-probability of kept steps. */
class CtcDecoder(private val table: List<String>) {
    fun decode(data: FloatArray, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var confSum = 0.0
        var kept = 0
        for (t in 0 until steps) {
            var best = 0
            var bestP = data[t * classes]
            for (c in 1 until classes) { val v = data[t * classes + c]; if (v > bestP) { bestP = v; best = c } }
            if (best != 0 && best != prev && best < table.size) { sb.append(table[best]); confSum += bestP; kept++ }
            prev = best
        }
        return sb.toString() to if (kept == 0) 0f else (confSum / kept).toFloat()
    }

    companion object {
        /** RapidOCR/PaddleOCR CTCLabelDecode table: blank, the characters (one per line), then a space. */
        fun table(characters: String): List<String> = listOf("blank") + characters.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() } + " "
    }
}

/** Bilinear resize of ARGB pixels (no Android dependency). */
object Pixels {
    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        val out = IntArray(dw * dh)
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = max(0f, (y + 0.5f) * sy - 0.5f)
            val y0 = min(sh - 1, fy.toInt()); val y1 = min(sh - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until dw) {
                val fx = max(0f, (x + 0.5f) * sx - 0.5f)
                val x0 = min(sw - 1, fx.toInt()); val x1 = min(sw - 1, x0 + 1); val wx = fx - x0
                val a = src[y0 * sw + x0]; val b = src[y0 * sw + x1]; val c = src[y1 * sw + x0]; val d = src[y1 * sw + x1]
                var px = 0xff shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val top = ((a shr shift) and 0xff) * (1 - wx) + ((b shr shift) and 0xff) * wx
                    val bot = ((c shr shift) and 0xff) * (1 - wx) + ((d shr shift) and 0xff) * wx
                    px = px or ((top * (1 - wy) + bot * wy).roundToInt().coerceIn(0, 255) shl shift)
                }
                out[y * dw + x] = px
            }
        }
        return out
    }
}
