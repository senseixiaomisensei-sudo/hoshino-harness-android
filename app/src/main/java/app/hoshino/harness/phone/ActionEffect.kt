// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/**
 * What a run of actions did to the screen (PH9): `done` (the screen changed), `place_changed` (another app
 * or overlay is in front), `no_effect` (nothing visible changed: the tap probably missed). `treeBlind` marks
 * screens where the accessibility tree shows (almost) nothing, so "no change" is not trustworthy.
 */
enum class Effect { done, place_changed, no_effect }

data class ActionEffect(val effect: Effect, val treeBlind: Boolean, val limitReached: String? = null)

object ActionEffects {
    /**
     * [frameChanged] (A-1): the wait after an action saw the picture change while the tree stayed the same (self-drawn
     * UI such as Flutter, games, mini programs). That is an effect, not `no_effect`.
     */
    fun of(before: ScreenSnapshot?, after: ScreenSnapshot?, frameChanged: Boolean = false): ActionEffect {
        after ?: return ActionEffect(Effect.no_effect, true)
        val blind = after.elements.count { it.name.isNotBlank() && !it.ocr } < 2
        val effect = when {
            before == null -> Effect.done
            before.packageName != after.packageName || before.overlayPackages != after.overlayPackages -> Effect.place_changed
            before.hash == after.hash && before.texts == after.texts && !frameChanged -> Effect.no_effect
            else -> Effect.done
        }
        return ActionEffect(effect, blind, LimitNotice.find(before, after))
    }
}

/** A short notice that appeared after an action saying a platform limit was reached ("操作过于频繁"…). */
object LimitNotice {
    val WORDS = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限",
        "明天再来", "明日再来", "明天再试", "明日再试", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "reachedthedailylimit", "reachedyourdailylimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")
    private const val MAX_CHARS = 120

    fun find(before: ScreenSnapshot?, after: ScreenSnapshot?): String? {
        after ?: return null
        val earlier = before?.let(::texts).orEmpty().map(::compact).toSet()
        return texts(after).firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun texts(s: ScreenSnapshot) = s.elements.filter { !it.editable && !it.hint && it.name.isNotBlank() }.map { it.name }
    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}
