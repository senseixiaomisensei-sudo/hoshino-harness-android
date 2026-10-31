// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the built-in helper hands its binder to the app. One per process (see [PhoneServices]). */
class HelperHub(context: Context) {
    private val app = context.applicationContext
    private val _shell = MutableStateFlow<IHoshinoPhoneShell?>(null)
    val shell: StateFlow<IHoshinoPhoneShell?> = _shell.asStateFlow()
    /** Given to the helper: when the app process dies, the helper sees it through linkToDeath. */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime) }

    fun issueLaunchNonce(): String = nonces.issueLaunch()

    fun forget() {
        nonces.clear()
        _shell.value = null
    }

    /**
     * Accept only shell (uid 2000) callers whose reported uid/pid match the binder caller, running the
     * currently installed APK, with a valid nonce; then confirm the binder lives in that same process.
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(it) }
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        if (!nonces.matches(nonce)) return reject(HelperHandoff.Reject.BadNonce)
        val shell = IHoshinoPhoneShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) return reject(HelperHandoff.Reject.BinderElsewhere)
        val next = nonces.accept(nonce) ?: return reject(HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ _shell.compareAndSet(shell, null) }, 0)
        } catch (e: Exception) {
            return reject(HelperHandoff.Reject.NoBinder)
        }
        _shell.value = shell
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    private fun reject(why: HelperHandoff.Reject): Bundle {
        Log.w(TAG, "helper handoff rejected: $why")
        return Bundle().apply { putBoolean(HelperHandoff.KEY_ACCEPTED, false); putString(HelperHandoff.KEY_REASON, why.name) }
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences("phone_helper_nonces", Context.MODE_PRIVATE)
        override fun load() = NonceSlots(prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0))
        // Synchronous: the app may be killed right after the helper got its new nonce.
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt).commit()
        }
    }

    private companion object { const val TAG = "HoshinoHelperHub" }
}

/**
 * Binder handoff entry. Exported for the shell user and additionally protected by
 * INTERACT_ACROSS_USERS_FULL in the manifest (shell has it, ordinary apps do not); the real gate is [HelperHub.offer].
 */
class HelperHandoffProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return PhoneServices.of(ctx).hub.offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
