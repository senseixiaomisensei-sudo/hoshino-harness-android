// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/SystemSpeech.kt 的 speechTag/languageRegion/isoCode；另加下划线写法、旧语言代码、只有文字子标签时补地区。)
package dev.hoshino.bridge.voice

import java.util.Locale

/**
 * 语言标签规整：识别服务普遍只认「语言-地区」（zh-CN），系统给的常带文字子标签（zh-Hans-CN）、
 * 下划线（zh_CN）、扩展（-u-nu-latn）或旧代码（iw/in/ji）。纯逻辑。
 */
object SpeechLang {
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro")

    /** 交给系统识别的 BCP-47 标签（只留语言-地区）；认不出来返回 null，由调用方用系统默认。 */
    fun recognizerTag(raw: String?): String? {
        val s = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val loc = Locale.forLanguageTag(s)
        val lang = canonical(loc.language) ?: return null
        var region = loc.country.uppercase()
        if (region.isEmpty() && lang == "zh") {
            // zh-Hans → zh-CN，zh-Hant → zh-TW：只给文字子标签时识别服务认不出，补一个最常见的地区
            region = when (loc.script.lowercase()) { "hant" -> "TW"; "hans" -> "CN"; else -> "" }
        }
        return if (region.isEmpty()) lang else "$lang-$region"
    }

    /** 转写接口要的 ISO-639-1 代码（zh、en、ja…）；认不出来返回 null（不传 language，交给模型判断）。 */
    fun isoCode(raw: String?): String? {
        val s = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        return canonical(Locale.forLanguageTag(s).language)
    }

    /** 系统语言对应的识别标签。 */
    fun fromLocale(locale: Locale): String =
        recognizerTag(locale.toLanguageTag()) ?: "en-US"

    private fun canonical(language: String?): String? {
        val l = language?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        if (!l.all { it in 'a'..'z' } || l.length !in 2..3) return null
        return LEGACY[l] ?: l
    }
}
