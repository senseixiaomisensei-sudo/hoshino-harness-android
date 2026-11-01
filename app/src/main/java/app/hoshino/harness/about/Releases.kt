// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.about

import org.json.JSONArray
import org.json.JSONObject

/** 本仓库的发布位置。检查更新只问这一个仓库的 GitHub Releases，不经过任何自建服务器。 */
object HoshinoRepo {
    const val OWNER_REPO = "senseixiaomisensei-sudo/hoshino-harness-android"
    const val PAGE = "https://github.com/$OWNER_REPO"
    const val RELEASES_API = "https://api.github.com/repos/$OWNER_REPO/releases?per_page=30"
    /** 发布附件的直链前缀；之后跳转到 GitHub 附件存储由 HttpURLConnection 跟随（只跟 HTTPS→HTTPS）。 */
    const val DOWNLOAD_PREFIX = "$PAGE/releases/download/"
    const val PACKAGE_NAME = "dev.hoshino.app"
}

/**
 * 一个可用于升级的发布：版本号取自 Release 说明里的 `versionCode N`（本仓库每个 Release 都写），
 * APK 只认手机用的 `*-arm64-v8a.apk`，不选 `x86_64-debug`（模拟器验收包）或其它附件。
 */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val versionCode: Long,
    val prerelease: Boolean,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    /** 附件字节数；GitHub 接口总会给，缺了就不收这个发布（下载后要按它核对大小）。 */
    val apkSize: Long,
    /** 形如 `sha256:<hex>`；较早的附件没有，这时靠大小、包名、版本号和签名把关。 */
    val apkDigest: String?,
    val publishedAt: String?,
)

object Releases {
    private val VERSION_CODE = Regex("""versionCode\s*[:：=]?\s*`?(\d{1,10})""")

    /** Release 说明（或标题）里第一次出现的 `versionCode N`；没写就是 null（这个发布无法按 versionCode 比较，跳过）。 */
    fun versionCodeOf(vararg texts: String?): Long? = texts.asSequence().filterNotNull()
        .mapNotNull { VERSION_CODE.find(it)?.groupValues?.get(1)?.toLongOrNull() }.firstOrNull()

    /** 手机用安装包：名字以 `-arm64-v8a.apk` 结尾，且不是 debug / x86 包。 */
    fun isPhoneApk(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith("-arm64-v8a.apk") && "debug" !in n && "x86" !in n
    }

    /** 读 GitHub `GET /repos/{repo}/releases` 的数组；含预发布，跳过草稿、没写 versionCode 或没有手机 APK 的。 */
    fun parseList(text: String): List<ReleaseInfo> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parse) }
    }

    fun parse(o: JSONObject): ReleaseInfo? {
        if (o.optBoolean("draft", false)) return null
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: tag
        val code = versionCodeOf(o.optString("body"), name) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { isPhoneApk(it.optString("name")) }
            .singleOrNull() ?: return null
        val size = apk.optLong("size", -1L).takeIf { it > 0 } ?: return null
        val url = apk.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return null
        return ReleaseInfo(
            tag = tag, name = name, versionCode = code, prerelease = o.optBoolean("prerelease", false),
            pageUrl = o.optString("html_url").ifBlank { "${HoshinoRepo.PAGE}/releases/tag/$tag" },
            apkName = apk.optString("name"), apkUrl = url, apkSize = size,
            apkDigest = apk.optString("digest").takeIf { it.isNotBlank() && it != "null" },
            publishedAt = o.optString("published_at").take(10).takeIf { it.length == 10 },
        )
    }

    /** versionCode 比当前高的里面最高的一个；没有就是 null（已是最新）。 */
    fun newest(releases: List<ReleaseInfo>, currentVersionCode: Long): ReleaseInfo? =
        releases.filter { it.versionCode > currentVersionCode }.maxByOrNull { it.versionCode }
}
