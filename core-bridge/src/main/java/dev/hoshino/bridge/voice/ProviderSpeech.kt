// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/ProviderSpeech.kt；改为按停顿分段、边说边转：Mika 是松开后整段转写。)
package dev.hoshino.bridge.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 录音器的最小接口：App 里是 AudioRecord，测试换成假的。[start] 之后约每 50ms 回调一块 16k 单声道 PCM。 */
interface Recorder {
    /** 拿不到麦克风返回 false。回调在录音线程上。 */
    fun start(onChunk: (ShortArray) -> Unit): Boolean

    /** 停下并等读循环退出；返回后不会再有回调。会短暂阻塞，别在主线程调。 */
    fun stop()

    /** 放弃；可重复调用。 */
    fun cancel()
}

/**
 * 什么时候把按着时录到的声音切成一段送去转写。纯逻辑，按块喂。
 * - 这段已有 [MIN_SEGMENT_MS] 以上、且末尾连着 [PAUSE_MS] 安静：在停顿处切（自然分句）；
 * - 这段到了 [MAX_SEGMENT_MS]（30 秒单段上限）：硬切。
 * [voiced] 告诉调用方这段里有没有说话声，全是安静的段不送（省额度，也避免模型对静音编出「谢谢观看」）。
 */
class SegmentCutter(private val rate: Int = Wav.RATE) {
    enum class Cut { None, Pause, Cap }

    private var samples = 0
    private var silentTail = 0
    private var voicedSamples = 0

    val segmentMs get() = samples * 1000L / rate
    val voiced get() = voicedSamples * 1000L / rate >= MIN_VOICED_MS

    fun feed(chunk: ShortArray): Cut {
        samples += chunk.size
        if (Wav.rms(chunk) >= SILENCE_RMS) { voicedSamples += chunk.size; silentTail = 0 } else silentTail += chunk.size
        return when {
            samples * 1000L / rate >= MAX_SEGMENT_MS -> Cut.Cap
            samples * 1000L / rate >= MIN_SEGMENT_MS && silentTail * 1000L / rate >= PAUSE_MS && voiced -> Cut.Pause
            else -> Cut.None
        }
    }

    fun reset() { samples = 0; silentTail = 0; voicedSamples = 0 }

    companion object {
        const val SILENCE_RMS = 350.0
        const val MIN_SEGMENT_MS = 4_000L
        const val PAUSE_MS = 700L
        const val MAX_SEGMENT_MS = 30_000L
        const val MIN_VOICED_MS = 150L
    }
}

/**
 * 服务商那条路：按着时录音，按停顿切段、每段一到就送去转写（按顺序一个一个转），转好的字边说边出；
 * 松开后把最后一段也送去，发 [SpeechEvent.Converting]，全部转完给 Final。
 * 单段最长 30 秒（[SegmentCutter.MAX_SEGMENT_MS]），每段转写最多等 [timeoutMs]；松开后 [slowMs] 还没转完发 [SpeechHint.Slow]。
 * 开始 [micSilentMs] 内麦克风送来的全是 0 时发 [SpeechHint.MicSilent]。整段不到 [MIN_MS] 当没听清、不调服务商。
 * 某段失败但别的段转出了字：按转出的字收尾（不把整句丢掉）；一个字都没有才报失败。
 */
class ProviderSpeech(
    private val recorder: Recorder,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = Transcriber.SINGLE_SEGMENT_TIMEOUT_MS,
    private val slowMs: Long = SLOW_MS,
    private val micSilentMs: Long = MIC_SILENT_MS,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    override val convertsAfterRelease = true

    private val lock = Any()
    private val cutter = SegmentCutter()
    private var buffer = ShortArray(Wav.RATE * 4)
    private var size = 0
    private var total = 0L
    private var heardSignal = false
    private var micHinted = false
    private var released = false

    private val queue = Channel<ShortArray>(Channel.UNLIMITED)
    private val texts = Segments()
    @Volatile private var failure: SpeechFailure? = null
    private var worker: Job? = null
    private var finisher: Job? = null

    init {
        if (!recorder.start(::onChunk)) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        } else {
            worker = scope.launch { for (seg in queue) convert(seg) }
        }
    }

    /** 外部（App 层）补一个提示，例如记住的写法失灵后已自动换用新写法。 */
    fun hint(kind: SpeechHint) = out.send(SpeechEvent.Hint(kind))

    private suspend fun convert(seg: ShortArray) {
        val wav = Wav.encode(seg)
        val r = withTimeoutOrNull(timeoutMs) {
            try {
                Result.success(transcribe(wav))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(SttError.BadResponse(e.javaClass.simpleName))
            }
        }
        when {
            r == null -> failure = SpeechFailure.Network
            r.isSuccess -> {
                texts.segmentDone(r.getOrDefault(""))
                val full = texts.full()
                if (full.isNotEmpty()) out.send(SpeechEvent.Partial(full, ""))
            }
            else -> failure = (r.exceptionOrNull() as? SttError)?.let { e ->
                if (e is SttError.Network) SpeechFailure.Network else SpeechFailure.Provider(e)
            } ?: SpeechFailure.Network
        }
    }

    private fun onChunk(chunk: ShortArray) {
        synchronized(lock) {
            if (released || out.cancelled || out.finished) return
            total += chunk.size
            out.send(SpeechEvent.Level((Wav.rms(chunk) / 6000.0).coerceIn(0.0, 1.0).toFloat()))
            if (!heardSignal && Wav.peak(chunk) > 0) heardSignal = true
            if (!heardSignal && !micHinted && total * 1000 / Wav.RATE >= micSilentMs) {
                micHinted = true
                out.send(SpeechEvent.Hint(SpeechHint.MicSilent))
            }
            append(chunk)
            when (cutter.feed(chunk)) {
                SegmentCutter.Cut.None -> Unit
                SegmentCutter.Cut.Pause -> flush()
                SegmentCutter.Cut.Cap -> { flush(); out.send(SpeechEvent.Hint(SpeechHint.SegmentCapped)) }
            }
        }
    }

    private fun append(chunk: ShortArray) {
        if (size + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size += chunk.size
    }

    /** 当前段交出去（有说话声才送）并清空。调用方持锁。 */
    private fun flush() {
        if (size > 0 && cutter.voiced) queue.trySend(buffer.copyOf(size))
        size = 0
        cutter.reset()
    }

    override fun release() {
        synchronized(lock) {
            if (released || out.finished || out.cancelled) return
            released = true
        }
        finisher = scope.launch {
            recorder.stop()
            val tooShort: Boolean
            synchronized(lock) {
                tooShort = total * 1000 / Wav.RATE < MIN_MS
                if (!tooShort) flush() else { size = 0; cutter.reset() }
            }
            queue.close()
            out.send(SpeechEvent.Converting)
            val slow = launch { delay(slowMs); out.send(SpeechEvent.Hint(SpeechHint.Slow)) }
            worker?.join()
            slow.cancel()
            val text = texts.full()
            val f = failure
            out.finish(if (text.isEmpty() && f != null) SpeechEvent.Failed(f) else SpeechEvent.Final(text))
        }
    }

    override fun cancel() {
        if (!out.cancel()) return
        queue.close()
        worker?.cancel()
        finisher?.cancel()
        recorder.cancel()
    }

    companion object {
        /** 比这短的录音多半是误触，直接当没听清。 */
        const val MIN_MS = 500L
        const val SLOW_MS = 3_000L
        const val MIC_SILENT_MS = 3_000L
    }
}
