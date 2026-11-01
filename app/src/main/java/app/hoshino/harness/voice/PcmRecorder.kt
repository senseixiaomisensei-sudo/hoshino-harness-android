// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/PcmRecorder.kt；改为按块回调，由会话自己分段。)
package app.hoshino.harness.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.hoshino.bridge.voice.Recorder
import dev.hoshino.bridge.voice.Wav

/**
 * 录 16k 单声道 16 位 PCM，每约 50ms 回调一块。录音权限由调用方先检查，这里拿不到麦克风就返回 false。
 * [stop] 会等读线程退出（短暂阻塞），别在主线程调。
 */
class PcmRecorder : Recorder {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // 权限由调用方在 start 之前检查
    override fun start(onChunk: (ShortArray) -> Unit): Boolean {
        synchronized(lock) {
            if (record != null) return false
            val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val chunk = Wav.RATE / 20
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 2 * 4))
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
            try { rec.startRecording() } catch (_: IllegalStateException) { rec.release(); return false }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return false }
            record = rec
            thread = Thread({ readLoop(rec, chunk, onChunk) }, "hoshino-voice-rec").also { it.start() }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int, onChunk: (ShortArray) -> Unit) {
        val buf = ShortArray(chunk)
        while (true) {
            val n = try { rec.read(buf, 0, chunk) } catch (_: Exception) { -1 }
            if (n <= 0) break
            synchronized(lock) { if (record !== rec) return }
            onChunk(buf.copyOf(n))
        }
    }

    override fun stop() {
        val (rec, t) = synchronized(lock) { val r = record; record = null; r to thread.also { thread = null } }
        if (rec != null) release(rec)
        runCatching { t?.join(1_000) }
    }

    override fun cancel() {
        val rec = synchronized(lock) { val r = record; record = null; thread = null; r }
        if (rec != null) release(rec)
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }
}
