// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/**
 * How text gets into a field (WP-2, D-5), as pure logic so it can be unit-tested without a device.
 *
 * Write paths, in order:
 * - native field: SET_TEXT → clipboard paste → key-by-key injection;
 * - field inside a WebView: clipboard paste → key-by-key → SET_TEXT last and reported as unverified, because a
 *   contenteditable shows SET_TEXT text without the page receiving an input event;
 * - no field in the tree (self-drawn input, [unseen]): clipboard paste → key-by-key.
 *
 * The clipboard is only used when the helper could read the user's clip first ([Paste.Unavailable] otherwise,
 * with the clipboard untouched); key-by-key refuses text it has no keys for ([Keys.Unmappable]). Each write is
 * read back; when nothing reads back the whole chain is retried once ([ATTEMPTS]). A screen that reacted without
 * showing readable text ([Check.Unverified]) stops the chain, since writing again could double the text.
 */
object TypeFlow {
    const val ATTEMPTS = 2

    enum class Paste { Done, Failed, Unavailable }
    enum class Keys { Done, Failed, Unmappable }
    enum class Method { set_text, paste, keys }

    /** Read back after a write. [Unverified]: the screen reacted but the text could not be read (self-drawn field). */
    enum class Check { Shown, Unverified, NotShown }

    interface Io {
        suspend fun setText(text: String): Boolean
        suspend fun paste(text: String): Paste
        suspend fun keys(text: String): Keys
        suspend fun check(text: String): Check
    }

    data class Result(val ok: Boolean, val code: String?, val detail: String, val method: Method? = null, val attempts: Int = 0)

    fun order(web: Boolean, unseen: Boolean): List<Method> = when {
        unseen -> listOf(Method.paste, Method.keys)
        web -> listOf(Method.paste, Method.keys, Method.set_text)
        else -> listOf(Method.set_text, Method.paste, Method.keys)
    }

    suspend fun run(io: Io, text: String, web: Boolean = false, unseen: Boolean = false, atPoint: Boolean = false): Result {
        val methods = order(web, unseen)
        val unavailable = HashSet<Method>()
        var wrote = false
        var clipUnavailable = false
        var unmappable = false
        var rounds = 0
        for (attempt in 1..ATTEMPTS) {
            rounds = attempt
            for (m in methods) {
                if (m in unavailable) continue
                val done = when (m) {
                    Method.set_text -> io.setText(text)
                    Method.paste -> when (io.paste(text)) {
                        Paste.Done -> true
                        Paste.Failed -> false
                        Paste.Unavailable -> { unavailable += m; clipUnavailable = true; false }
                    }
                    Method.keys -> when (io.keys(text)) {
                        Keys.Done -> true
                        Keys.Failed -> false
                        Keys.Unmappable -> { unavailable += m; unmappable = true; false }
                    }
                }
                if (!done) continue
                wrote = true
                when (io.check(text)) {
                    Check.Shown -> {
                        // SET_TEXT inside a WebView may only change what is drawn: never report it as verified.
                        if (web && m == Method.set_text) return Result(true, null, "set_text_unverified: the text shows, but a web editor may not have received it (clipboard and key input were not possible)", m, attempt)
                        return Result(true, null, describe(m, attempt), m, attempt)
                    }
                    // The screen reacted (a self-drawn field): writing again could double the text, so stop here.
                    Check.Unverified -> return Result(true, null, "${m.name}_unverified: the screen changed but the typed text could not be read back; check it", m, attempt)
                    Check.NotShown -> Unit
                }
            }
            if (methods.all { it in unavailable }) break
        }
        return when {
            !wrote && clipUnavailable && unmappable ->
                Result(false, NEEDS_IME, "$NEEDS_IME: the clipboard was not used because the user's clip could not be read (left untouched), and the text has characters without keys; type it in parts the field accepts or ask the user", attempts = rounds)
            atPoint || unseen -> Result(false, POINT_NOT_TYPED, "$POINT_NOT_TYPED: nothing there took the text; tap the input field (or read the screen) and try again", attempts = rounds)
            !wrote -> Result(false, NOT_TYPED, "$NOT_TYPED: the field did not accept the text", attempts = rounds)
            else -> Result(false, READBACK_MISMATCH, "$READBACK_MISMATCH: the field does not show the text after $ATTEMPTS tries", attempts = rounds)
        }
    }

    private fun describe(m: Method, attempt: Int) = m.name + if (attempt > 1) " (retried)" else ""

    /** Whether new screen text contains what was typed (whitespace-insensitive); used for fields not in the tree. */
    fun appeared(text: String, before: Collection<String>, after: Collection<String>): Boolean {
        val typed = text.filterNot(Char::isWhitespace).take(64)
        if (typed.isEmpty()) return false
        val old = before.map { it.filterNot(Char::isWhitespace) }.toSet()
        return after.map { it.filterNot(Char::isWhitespace) }.any { it !in old && typed in it }
    }

    const val NO_FOCUS = "no_focus"
    const val POINT_NOT_TYPED = "point_not_typed"
    const val NEEDS_IME = "needs_ime"
    const val READBACK_MISMATCH = "readback_mismatch"
    const val NOT_TYPED = "not_typed"
}
