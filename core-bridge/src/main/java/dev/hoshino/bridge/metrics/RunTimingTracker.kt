package dev.hoshino.bridge.metrics

/**
 * 界面一侧实测的时间（OB3），会话日志里没有：用户第一次看到这轮进展的时刻（第一段流式文字/思考，或第一张工具卡），
 * 以及等用户的时间段（审批、回答问题）。轮次结束时由 [apply] 套到 [RunMetrics.Builder] 上，小结里的
 * 第一次反馈就标成「ui」，等用户的时间从工具时长里扣掉。
 *
 * 打开旧会话时内核会回放整段日志，界面上同样会出现开始、流式文字和结束；回放的轮次日志里的开始时间早就过去了，
 * 和界面看到「开始」的时刻对不上（相差超过 [toleranceMs]），这时不用界面实测，只按日志算。
 * 线程安全：调用方可以从不同协程调用。
 */
class RunTimingTracker(private val toleranceMs: Long = DEFAULT_TOLERANCE_MS) {
    private class Live(val startedAt: Long) {
        var feedbackAt: Long? = null
        val waits = mutableListOf<LongRange>()
        val open = HashMap<String, Long>()
    }

    private val live = HashMap<String, Live>()

    @Synchronized fun turnStarted(sessionId: String, atMs: Long) { live[sessionId] = Live(atMs) }

    /** 第一次可见的进展；只记第一次。 */
    @Synchronized fun feedback(sessionId: String, atMs: Long) {
        val l = live[sessionId] ?: return
        if (l.feedbackAt == null) l.feedbackAt = atMs
    }

    @Synchronized fun waitStarted(sessionId: String, id: String, atMs: Long) { live[sessionId]?.open?.put(id, atMs) }

    @Synchronized fun waitEnded(sessionId: String, id: String, atMs: Long) {
        val l = live[sessionId] ?: return
        val start = l.open.remove(id) ?: return
        if (atMs > start) l.waits += start until atMs
    }

    /** 这一轮界面上还在等用户（审批卡、问题卡没回答）。 */
    @Synchronized fun waiting(sessionId: String): Boolean = live[sessionId]?.open?.isNotEmpty() == true

    /**
     * 轮次结束：把实测套到 [builder] 上并丢掉这一轮的记录。[logStartMs] 是日志里这一轮 `turn/start` 的时间；
     * 和界面看到的开始相差超过容差时（回放、或界面没看到开始）不套。返回是否套上了。
     */
    @Synchronized fun apply(sessionId: String, logStartMs: Long, builder: RunMetrics.Builder): Boolean {
        val l = live.remove(sessionId) ?: return false
        if (kotlin.math.abs(l.startedAt - logStartMs) > toleranceMs) return false
        l.feedbackAt?.takeIf { it >= logStartMs }?.let(builder::uiFirstFeedback)
        // 结束时还没关上的等待（审批卡被取消）算到结束为止由调用方决定；这里只套已关上的
        l.waits.forEach { builder.userWait(it.first, it.last + 1) }
        return true
    }

    companion object {
        const val DEFAULT_TOLERANCE_MS = 5_000L
    }
}
