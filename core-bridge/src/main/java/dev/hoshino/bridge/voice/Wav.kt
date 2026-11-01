// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/Wav.kt；另加 [Wav.probe] 内置试转样本、[Wav.rms]/[Wav.peak]。)
package dev.hoshino.bridge.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** 16 位单声道 PCM ↔ 标准 WAV（44 字节头 + 小端数据），给服务商的转写接口上传用。 */
object Wav {
    const val RATE = 16_000
    const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // fmt 块长度
        buf.putShort(1)             // PCM
        buf.putShort(1)             // 单声道
        buf.putInt(rate)
        buf.putInt(rate * 2)        // 每秒字节数 = 采样率 × 声道 × 2 字节
        buf.putShort(2)             // 块对齐
        buf.putShort(16)            // 位深
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /** 一段全零的静音 WAV。 */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)

    /**
     * 选模型时试转用的内置短音频（约 1.2 秒，随代码生成，不带任何录音素材）：
     * 前后各 0.2 秒静音，中间 0.8 秒像元音的谐波音（基频 140 Hz，按 4 Hz 起伏）。
     * 不用纯静音：有的服务对全零音频直接回 400「音频为空」，会把能用的模型误判成不能用。
     */
    fun probe(rate: Int = RATE): ByteArray {
        val total = rate * 1200 / 1000
        val lead = rate * 200 / 1000
        val body = rate * 800 / 1000
        val pcm = ShortArray(total)
        for (i in 0 until body) {
            val t = i.toDouble() / rate
            val env = sin(PI * i / body) * (0.6 + 0.4 * sin(2 * PI * 4 * t))
            var s = 0.0
            for ((h, a) in HARMONICS) s += a * sin(2 * PI * 140.0 * h * t)
            pcm[lead + i] = (s * env * 6000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return encode(pcm, rate)
    }

    private val HARMONICS = listOf(1 to 1.0, 2 to 0.6, 3 to 0.45, 5 to 0.25, 7 to 0.12)

    /** 一块 PCM 的均方根（0..32768）。 */
    fun rms(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
        return sqrt(sum / (to - from))
    }

    fun peak(pcm: ShortArray): Int = pcm.maxOfOrNull { abs(it.toInt()) } ?: 0

    /** WAV 里数据段的采样数（只认本类写出的 44 字节头）。 */
    fun samples(wav: ByteArray): Int = (wav.size - HEADER).coerceAtLeast(0) / 2
}
