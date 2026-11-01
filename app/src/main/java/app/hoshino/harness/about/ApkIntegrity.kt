// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.about

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** 更新包没通过安装前核对的原因；关于页按它给出不同的说明。 */
class ApkRejected(val reason: Reason, detail: String = reason.text) : Exception(detail) {
    enum class Reason(val text: String) {
        /** 下载地址不是本仓库 Release 附件。 */
        Source("下载地址不是本仓库的 Release 附件，已阻止"),
        /** 大小、SHA-256 对不上，或者读不出安装包。 */
        Corrupt("安装包大小或校验值与 Release 附件不一致，可能下载不完整，已删除"),
        /** 包名不是 dev.hoshino.app。 */
        Package("安装包的包名不是 dev.hoshino.app，已阻止安装"),
        /** versionCode 不比已装的高。 */
        NotNewer("安装包版本不高于当前已装版本，已阻止安装"),
        /** 签名证书和当前已装的不同。 */
        Signer("安装包签名证书与当前已装版本不一致，已阻止安装"),
    }
}

/** 从安装包或已装包读出的、核对需要的几项（签名为各证书 DER 字节的 SHA-256 小写十六进制）。 */
data class PackageFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    /** 轮换过签名的包带着历史证书；多个签名者时为空。 */
    val signerHistory: Set<String> = emptySet(),
)

/**
 * 交给系统安装器之前的核对，全部是纯函数，便于单测：
 * 地址只收本仓库 HTTPS 发布附件；文件大小必须等于 Release 附件大小，给了 sha256 摘要就必须对上；
 * 包名必须是 dev.hoshino.app 且与当前进程一致；versionCode 必须更高；签名证书必须与当前已装包一致
 * （从已装包现读，不写死指纹）。系统安装器之后还会再验签名并要求用户确认。
 */
object ApkIntegrity {
    fun requireOfficialUrl(url: String, prefix: String = HoshinoRepo.DOWNLOAD_PREFIX) {
        val u = runCatching { URI(url) }.getOrNull() ?: throw ApkRejected(ApkRejected.Reason.Source)
        val p = URI(prefix)
        val ok = u.scheme == "https" && u.rawUserInfo == null && u.host == p.host && (u.port == -1 || u.port == 443) &&
            u.rawQuery == null && (u.rawPath ?: "").startsWith(p.rawPath) && !(u.rawPath ?: "").contains("/../") &&
            Releases.isPhoneApk(u.rawPath.substringAfterLast('/'))
        if (!ok) throw ApkRejected(ApkRejected.Reason.Source)
    }

    /** 大小必须与 Release 附件一致；摘要给了就必须是 `sha256:<64 hex>` 且相等，别的写法也算不一致。 */
    fun verifyFile(file: File, expectedSize: Long, digest: String?) {
        corruptUnless(file.isFile && file.length() > 0)
        corruptUnless(expectedSize > 0 && file.length() == expectedSize)
        if (digest != null) {
            corruptUnless(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            corruptUnless(sha256(file).equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }

    fun requireUpdate(archive: PackageFacts, installed: PackageFacts, expectedPackage: String = HoshinoRepo.PACKAGE_NAME) {
        if (archive.packageName != expectedPackage || installed.packageName != expectedPackage) throw ApkRejected(ApkRejected.Reason.Package)
        if (archive.versionCode <= installed.versionCode) throw ApkRejected(ApkRejected.Reason.NotNewer)
        requireSameSigner(installed.signers, archive.signers, archive.signerHistory)
    }

    /** 新包证书集合要和已装的相同；新包轮换过签名时，已装证书要在它的签名历史里。 */
    fun requireSameSigner(installed: Set<String>, archive: Set<String>, archiveHistory: Set<String> = emptySet()) {
        if (installed.isEmpty() || archive.isEmpty()) throw ApkRejected(ApkRejected.Reason.Signer)
        if (archive != installed && !(archiveHistory.isNotEmpty() && archiveHistory.containsAll(installed))) throw ApkRejected(ApkRejected.Reason.Signer)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun sha256(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return sha.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun corruptUnless(ok: Boolean) {
        if (!ok) throw ApkRejected(ApkRejected.Reason.Corrupt)
    }
}
