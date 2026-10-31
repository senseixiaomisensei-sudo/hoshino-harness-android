// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Contract shared by the helper process and the app when the helper hands its Binder over:
 * provider method and bundle keys, process name, launch command and the checks the app runs
 * before accepting a binder.
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "dev.hoshino.bridge.phone.shell.HelperMain"
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String) = "$packageName.phonehelper"

    /** app_process --nice-name; also used to recognise older helper instances. */
    fun processName(packageName: String) = "$packageName:phonehelper"

    /**
     * Command run by adbd's exec: service (`sh -c`). adbd kills the whole group when the stream closes,
     * so the helper starts in its own session (setsid); sh then waits on `read` until the app closes the
     * stream after the handoff. The nonce never appears on the command line (other shell processes can
     * read /proc/<pid>/cmdline): the app writes [stdinLine] and sh pipes it to the helper with the builtin echo.
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /** SHA-256 fingerprints of the signing certificates (DER), sorted and comma-joined; null without certs. */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
    }

    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class Reject { NotShell, IdMismatch, StaleApk, NoBinder, BadNonce, BinderElsewhere }

    /** Checks that need no binder call. Null when they pass; the nonce is checked by [HandoffNonces]. */
    fun check(callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int, helperApk: String?, appApk: String, hasBinder: Boolean): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/** [resume] for the running helper's next handoff; [launch] for a helper just started through adbd ([launchAt] in elapsed ms). */
data class NonceSlots(val resume: String? = null, val launch: String? = null, val launchAt: Long = 0)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/** One-time handoff nonces. Using any of them voids all and issues a new resume nonce to the accepted helper. */
class HandoffNonces(private val store: NonceStore, private val clock: () -> Long, private val random: () -> String = ::randomNonce) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val launch = s.launch?.takeIf { clock() - s.launchAt in 0..LAUNCH_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch)
    }

    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    @Synchronized
    fun clear() = store.save(NonceSlots())

    private fun same(a: String, b: String?) = b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val LAUNCH_TTL_MS = 30_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
