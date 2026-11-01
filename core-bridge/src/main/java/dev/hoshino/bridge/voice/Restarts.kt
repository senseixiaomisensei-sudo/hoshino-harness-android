// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.voice

/**
 * 按住期间识别服务结束了一段却没听到字(没匹配上、没开口、空结果)时,要不要重开、隔多久。
 * 正常的空段(用户停顿几秒)立刻重开;有的服务刚重开就又回空,连着很快回空就隔一会儿再开,
 * 太多次就不再重开、按已听到的收尾,免得主线程一直在开、错、开里打转。纯逻辑,时间由调用方给。
 */
class Restarts {
    sealed interface Decision {
        data class Restart(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private var quick = 0
    private var last = Long.MIN_VALUE

    /** 又一段空的,[now] 是此刻(毫秒,单调时钟)。 */
    fun onEmpty(now: Long): Decision {
        quick = if (last != Long.MIN_VALUE && now - last <= WINDOW_MS) quick + 1 else 1
        last = now
        return when {
            quick > MAX_QUICK -> Decision.GiveUp
            quick > QUICK -> Decision.Restart(BACKOFF_MS)
            else -> Decision.Restart(0)
        }
    }

    /** 听到了字:重新计数。 */
    fun onSpeech() {
        quick = 0
        last = Long.MIN_VALUE
    }

    companion object {
        /** 和上一次空段隔这么久以内算「很快又回空」。 */
        const val WINDOW_MS = 2_000L
        /** 连着这么多次很快回空之后开始隔一会儿再开。 */
        const val QUICK = 3
        /** 连着很快回空超过这么多次就不再重开。 */
        const val MAX_QUICK = 8
        const val BACKOFF_MS = 250L
    }
}
