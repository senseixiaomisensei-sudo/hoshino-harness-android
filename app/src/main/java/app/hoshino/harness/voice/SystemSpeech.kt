// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/SystemSpeech.kt；错误码规则挪到 dev.hoshino.bridge.voice.SystemSpeechRules。)
package app.hoshino.harness.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.hoshino.bridge.voice.Restarts
import dev.hoshino.bridge.voice.Segments
import dev.hoshino.bridge.voice.SessionEvents
import dev.hoshino.bridge.voice.SpeechEngineSpec
import dev.hoshino.bridge.voice.SpeechEvent
import dev.hoshino.bridge.voice.SpeechFailure
import dev.hoshino.bridge.voice.SpeechSession
import dev.hoshino.bridge.voice.SystemCapability
import dev.hoshino.bridge.voice.SystemSpeechRules
import kotlinx.coroutines.flow.Flow

object SystemSpeech {
    /** 安卓 11 起要在清单里声明查询 RecognitionService，否则这里永远是不可用。 */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice, responsive = !silent)
    }
}

/**
 * 手机自带识别的一次会话。[SpeechRecognizer] 只能在主线程建和调，所以这里所有对它的操作都投到主线程。
 * 按着期间识别服务自己结束一段就重开，各段由 [Segments] 拼起来；松开后 stopListening，最多等
 * [SystemSpeechRules.FINAL_WAIT_MS] 拿最终结果，等不到就用最后一次中间结果。每次 startListening 后
 * [SystemSpeechRules.NO_RESPONSE_MS]（3 秒）内服务一个回调都没有，按 [SystemSpeechRules.afterSilence] 收尾。
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: () -> Unit = {},
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    private var answered = false
    private var reported = false
    private val timeout = Runnable { if (answered) finish(SpeechEvent.Final(segments.full())) }
    private val silence = Runnable {
        if (done()) return@Runnable
        when (val o = SystemSpeechRules.afterSilence(answered)) {
            is SystemSpeechRules.Outcome.Fail -> { onNoResponse(); finish(SpeechEvent.Failed(o.why)) }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    private val restart = Runnable {
        restartPending = false
        if (!done() && holding) listen()
    }

    init { onMain { open(onDevice) } }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (restartPending) { finish(SpeechEvent.Final(segments.full())); return@onMain }
        recognizer?.stopListening()
        main.postDelayed(timeout, SystemSpeechRules.FINAL_WAIT_MS)
    }

    override fun cancel() {
        if (!out.cancel()) return
        onMain { recognizer?.cancel(); teardown() }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else null
        usingOnDevice = r != null
        val rec = r ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (rec == null) { finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone))); return }
        recognizer = rec
        answered = false
        rec.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        main.removeCallbacks(silence)
        main.postDelayed(silence, SystemSpeechRules.NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (e: RuntimeException) {
            finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        }
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    private fun emptySegment() {
        when (val d = restarts.onEmpty(SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> { restartPending = true; main.postDelayed(restart, d.delayMs) }
        }
    }

    private fun heard() {
        answered = true
        main.removeCallbacks(silence)
        if (!reported) { reported = true; onAnswered() }
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        restartPending = false
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = heard()
        override fun onBeginningOfSpeech() = heard()
        override fun onBufferReceived(buffer: ByteArray?) = heard()
        override fun onEndOfSpeech() = heard()
        override fun onEvent(eventType: Int, params: Bundle?) = heard()

        override fun onRmsChanged(rmsdB: Float) {
            heard()
            if (!done()) out.send(SpeechEvent.Level(SystemSpeechRules.level(rmsdB)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                if (text.isBlank()) emptySegment() else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else finish(SpeechEvent.Final(segments.full()))
        }

        override fun onError(error: Int) {
            heard()
            if (done()) return
            if (usingOnDevice && SystemSpeechRules.languageMissing(error) && segments.full().isEmpty()) {
                open(preferOnDevice = false)
                if (!holding && !done()) {
                    recognizer?.stopListening()
                    main.postDelayed(timeout, SystemSpeechRules.FINAL_WAIT_MS)
                }
                return
            }
            when (val o = SystemSpeechRules.afterError(error, holding, heard = segments.full().isNotEmpty())) {
                SystemSpeechRules.Outcome.Retry -> emptySegment()
                SystemSpeechRules.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
                is SystemSpeechRules.Outcome.Fail -> finish(SpeechEvent.Failed(o.why))
            }
        }
    }
}
