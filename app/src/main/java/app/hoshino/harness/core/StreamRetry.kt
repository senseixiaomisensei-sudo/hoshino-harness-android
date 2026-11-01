// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (0.1.24 · 2d5ca72: "retry clears the half reply already shown"; re-implemented over dsh's per-attempt assistant stream.)
package app.hoshino.harness.core

/**
 * 断流重试（m2.10 T4）。dsh 每次模型请求是一次流式尝试（assistant-stream 的 start/chunk/end，id `a:<attemptId>`）；
 * 失败的尝试落成 `assistant/attempt`、不进上下文，RT16 重试时开始新的尝试。界面上该尝试已经画出的条目要去掉，
 * 否则重试的回答会接在半句后面重复显示。纯逻辑，可单测。
 */
object StreamRetry {
    /**
     * 一次失败尝试在界面上留下的条目 id：正文 [liveId]、思考 `r:<liveId>`、托管搜索步骤 `ws:<liveId>:<n>`
     * （与 DshHarnessClient 画它们时用的 id 一致）。没画出的不列。
     */
    fun discardedItems(liveId: String, hasText: Boolean, hasThinking: Boolean, searchSteps: Int = 0): List<String> = buildList {
        if (hasText) add(liveId)
        if (hasThinking) add("r:$liveId")
        for (i in 0 until searchSteps) add("ws:$liveId:$i")
    }
}
