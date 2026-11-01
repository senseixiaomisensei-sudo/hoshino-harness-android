// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.voice

/**
 * 按住期间识别服务自己结束一段后重开,把各段拼起来。
 * 中日文段之间直接相连,其他文字(英文等)段之间加一个空格。纯逻辑,不碰系统。
 */
class Segments {
    private var done = ""
    private var pending = ""

    /** 当前这一段的中间结果;返回到此刻的全文。 */
    fun partial(text: String): String {
        pending = text.trim()
        return full()
    }

    /** 当前这一段识别完了,[text] 是这一段的最终文字。 */
    fun segmentDone(text: String) {
        done = join(done, text.trim())
        pending = ""
    }

    /** 已完成的各段加上还没完成那段的中间结果;松开后等不到最终结果时就用它。 */
    fun full(): String = join(done, pending).trim()

    companion object {
        /** 末尾这么多个字(按码点)还可能被识别服务改掉,界面上画得淡一些。 */
        private const val TAIL = 4

        fun split(full: String): SpeechEvent.Partial {
            val n = full.codePointCount(0, full.length)
            if (n <= TAIL) return SpeechEvent.Partial("", full)
            val cut = full.offsetByCodePoints(0, n - TAIL)
            return SpeechEvent.Partial(full.substring(0, cut), full.substring(cut))
        }

        private fun join(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            noSpace(a.codePointBefore(a.length)) || noSpace(b.codePointAt(0)) || b.codePointAt(0).toChar() in ATTACHED -> a + b
            else -> "$a $b"
        }

        /** 紧跟前文、前面不加空格的标点。 */
        private const val ATTACHED = ",.!?;:)"

        /** 本身是空白,或属于不用空格分词的文字(汉字、假名及其全角标点)。 */
        private fun noSpace(cp: Int): Boolean {
            if (Character.isWhitespace(cp)) return true
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                else -> {}
            }
            val block = Character.UnicodeBlock.of(cp)
            return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
    }
}
