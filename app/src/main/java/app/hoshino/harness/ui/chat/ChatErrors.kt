package app.hoshino.harness.ui.chat

/** Pure rule for which one-shot messages are errors that must be shown untruncated (dialog + 重试). */
object ChatErrors {
    fun isFullError(message: String): Boolean =
        message.startsWith("内核") || message.contains("请求未完成") || message.contains("session \"") || message.length > 60
}
