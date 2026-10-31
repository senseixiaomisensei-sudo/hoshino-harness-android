// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/** A system "open with" / dual-app chooser in front of the task. */
data class Chooser(val packageName: String, val title: String?, val options: List<ChooserOption>)
data class ChooserOption(val ref: String, val label: String, val clone: Boolean?)

/** The screen needs the user's own hands (login, verification code, payment, password). */
data class NeedsUser(val reason: String, val cause: String)

/**
 * Recognises system screens that the agent must not handle silently.
 * Choosers: resolver/chooser windows of the framework or intent resolver, and the dual-app ("应用双开" /
 * XSpace on MIUI/HyperOS) chooser. Recognised by package and wording, not by fixed layouts.
 */
object SystemScreens {
    val CHOOSER_PACKAGES = setOf("android", "com.android.intentresolver", "com.miui.securitycore", "com.android.internal.app", "com.google.android.intentresolver")
    private val CHOOSER_WORDS = listOf(
        "打开方式", "使用以下方式打开", "选择打开方式", "选择应用", "选择要使用的应用", "用哪个应用", "始终", "仅此一次", "双开", "分身",
        "open with", "complete action using", "always", "just once", "choose an app",
    )
    /** Words meaning "the second copy" of a dual app. */
    private val CLONE_WORDS = listOf("双开", "分身", "第二空间", "工作", "clone", "dual", "work", "(2)", "（2）")

    private val CAPTCHA = listOf("captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "请按顺序点击", "verify you are human", "not a robot")
    private val PAYMENT = IrreversibleDetector.PAYMENT_TEXT
    private val SMS_CODE = listOf("获取验证码", "发送验证码", "短信验证码", "输入验证码", "get code", "send code", "verification code")
    private val LOGIN = listOf("登录", "登 录", "sign in", "log in")

    /** [appLabel] (the app the task opened), when known, makes dual-app options more precise. */
    fun chooser(s: ScreenSnapshot, appLabel: String? = null): Chooser? {
        val pkg = (s.overlayPackages + listOfNotNull(s.packageName)).firstOrNull { it in CHOOSER_PACKAGES } ?: return null
        val text = s.texts.joinToString("\n").lowercase()
        val worded = CHOOSER_WORDS.any { it in text }
        val label = appLabel?.filterNot(Char::isWhitespace)?.lowercase()
        val options = s.elements.filter { it.clickable && it.name.isNotBlank() }.filter { e ->
            val n = e.name.filterNot(Char::isWhitespace).lowercase()
            (label != null && label.isNotEmpty() && n.contains(label)) || (label == null && n !in setOf("始终", "仅此一次", "always", "justonce", "取消", "cancel"))
        }
        if (!worded && (label == null || options.isEmpty())) return null
        val title = s.elements.firstOrNull { !it.clickable && it.name.isNotBlank() }?.name
        val counts = options.groupingBy { it.name.filterNot(Char::isWhitespace).lowercase() }.eachCount()
        return Chooser(pkg, title, options.take(12).map { e ->
            val n = e.name.filterNot(Char::isWhitespace).lowercase()
            val clone = when {
                CLONE_WORDS.any { it in n } -> true
                counts.getValue(n) > 1 -> null
                label != null && n == label -> false
                else -> null
            }
            ChooserOption(e.ref, e.name.replace('\n', ' ').trim(), clone)
        })
    }

    /** Screens the agent must hand to the user: captcha, payment confirmation, password/login, SMS code. */
    fun needsUser(s: ScreenSnapshot): NeedsUser? {
        val text = s.texts.joinToString("\n").lowercase()
        fun hit(words: List<String>) = words.firstOrNull { it.lowercase() in text }
        hit(CAPTCHA)?.let { return NeedsUser("captcha", "captcha text: $it") }
        hit(PAYMENT)?.let { return NeedsUser("payment", "payment text: $it") }
        if (s.elements.any { it.password }) return NeedsUser(if (hit(LOGIN) != null) "login" else "password", "password field")
        hit(SMS_CODE)?.let { return NeedsUser("login", "verification code: $it") }
        return null
    }
}
