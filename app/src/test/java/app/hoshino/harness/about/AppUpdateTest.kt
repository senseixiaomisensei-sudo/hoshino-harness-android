// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ReleasesTest {
    private val sample = javaClass.getResource("/about/releases-sample.json")!!.readText()

    @Test fun `parses real release list including prereleases and skips drafts and releases without versionCode`() {
        val list = Releases.parseList(sample)
        assertEquals(listOf("v0.2.1-m2.9.5", "v0.2.1-m2.9.4"), list.map { it.tag })
        val top = list.first()
        assertEquals(218L, top.versionCode)
        assertTrue(top.prerelease)
        assertEquals("Hoshino-Harness-0.2.1-m2.9.5-arm64-v8a.apk", top.apkName)
        assertEquals(77434865L, top.apkSize)
        assertEquals("sha256:de28dc1dc1f21dc7eb83954907d0cb75feedb77c6958b220c1d7146847440fcc", top.apkDigest)
        assertTrue(top.apkUrl.startsWith(HoshinoRepo.DOWNLOAD_PREFIX))
    }

    @Test fun `picks only the arm64 phone apk, never x86_64-debug`() {
        assertTrue(Releases.isPhoneApk("Hoshino-Harness-0.2.1-m2.9.5-arm64-v8a.apk"))
        assertFalse(Releases.isPhoneApk("Hoshino-Harness-0.2.1-m2.9.5-x86_64-debug.apk"))
        assertFalse(Releases.isPhoneApk("Hoshino-Harness-0.2.1-arm64-v8a-debug.apk"))
        assertFalse(Releases.isPhoneApk("SHA256SUMS-0.2.1-m2.9.5.txt"))
        Releases.parseList(sample).forEach { assertFalse(it.apkName.contains("x86")) }
    }

    @Test fun `compares by versionCode, not by version name`() {
        val list = Releases.parseList(sample)
        assertEquals(218L, Releases.newest(list, 216)?.versionCode)
        assertEquals(218L, Releases.newest(list, 217)?.versionCode)
        assertNull(Releases.newest(list, 218))
        assertNull(Releases.newest(list, 400))
    }

    @Test fun `versionCode is read from the release notes`() {
        assertEquals(218L, Releases.versionCodeOf("包名 `dev.hoshino.app`，versionCode 218（高于 217）"))
        assertEquals(5L, Releases.versionCodeOf(null, "versionCode: 5"))
        assertNull(Releases.versionCodeOf("no code", "M0"))
    }
}

class ApkIntegrityTest {
    private val signer = "e20b0b97" + "0".repeat(52) + "8fd1"
    private val other = "ab".repeat(32)
    private fun facts(code: Long, pkg: String = "dev.hoshino.app", signers: Set<String> = setOf(signer), history: Set<String> = emptySet()) =
        PackageFacts(pkg, code, signers, history)

    private fun rejected(reason: ApkRejected.Reason, block: () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: ApkRejected) { assertEquals(reason, e.reason) }
    }

    @Test fun `accepts same package, higher versionCode and same signer`() {
        ApkIntegrity.requireUpdate(facts(219), facts(218))
    }

    @Test fun `rejects other package, equal or lower versionCode, and different signer`() {
        rejected(ApkRejected.Reason.Package) { ApkIntegrity.requireUpdate(facts(219, pkg = "dev.hoshino.app.evil"), facts(218)) }
        rejected(ApkRejected.Reason.NotNewer) { ApkIntegrity.requireUpdate(facts(218), facts(218)) }
        rejected(ApkRejected.Reason.NotNewer) { ApkIntegrity.requireUpdate(facts(100), facts(218)) }
        rejected(ApkRejected.Reason.Signer) { ApkIntegrity.requireUpdate(facts(219, signers = setOf(other)), facts(218)) }
        rejected(ApkRejected.Reason.Signer) { ApkIntegrity.requireUpdate(facts(219, signers = emptySet()), facts(218)) }
        rejected(ApkRejected.Reason.Signer) { ApkIntegrity.requireUpdate(facts(219, signers = setOf(signer, other)), facts(218)) }
    }

    @Test fun `rotated signing key is accepted only when the installed cert is in its history`() {
        ApkIntegrity.requireUpdate(facts(219, signers = setOf(other), history = setOf(signer, other)), facts(218))
        rejected(ApkRejected.Reason.Signer) { ApkIntegrity.requireUpdate(facts(219, signers = setOf(other), history = setOf(other)), facts(218)) }
    }

    @Test fun `file size must equal the release asset size and digest must match`() {
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(ByteArray(1000) { it.toByte() }); deleteOnExit() }
        val digest = "sha256:" + ApkIntegrity.sha256(f)
        ApkIntegrity.verifyFile(f, 1000, digest)
        ApkIntegrity.verifyFile(f, 1000, null)
        rejected(ApkRejected.Reason.Corrupt) { ApkIntegrity.verifyFile(f, 999, digest) }
        rejected(ApkRejected.Reason.Corrupt) { ApkIntegrity.verifyFile(f, 1000, "sha256:" + "0".repeat(64)) }
        rejected(ApkRejected.Reason.Corrupt) { ApkIntegrity.verifyFile(f, 1000, "md5:abc") }
        rejected(ApkRejected.Reason.Corrupt) { ApkIntegrity.verifyFile(File(f.path + ".missing"), 1000, null) }
    }

    @Test fun `download url must be an https release asset of this repository`() {
        val ok = HoshinoRepo.DOWNLOAD_PREFIX + "v0.2.1-m2.9.5/Hoshino-Harness-0.2.1-m2.9.5-arm64-v8a.apk"
        ApkIntegrity.requireOfficialUrl(ok)
        for (bad in listOf(
            ok.replace("https://", "http://"),
            ok.replace("senseixiaomisensei-sudo", "someone-else"),
            ok.replace("github.com", "github.com.evil.example"),
            ok.replace("arm64-v8a.apk", "x86_64-debug.apk"),
            "https://user@github.com/senseixiaomisensei-sudo/hoshino-harness-android/releases/download/v1/a-arm64-v8a.apk",
            "$ok?x=1",
        )) rejected(ApkRejected.Reason.Source) { ApkIntegrity.requireOfficialUrl(bad) }
    }
}
