// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.hoshino.bridge.phone.IHoshinoPhoneShell

/**
 * System state Hoshino borrows during a phone task and must give back (RT6, PH18):
 * - apps muted on the agent display (PLAY_AUDIO app-op): the original mode is written down before the
 *   change and restored when the task ends, and again when the helper comes back after a crash;
 * - the clipboard borrowed for a paste: when the process died mid-paste, our text is cleared on next start
 *   (the user's own clip is never written to disk, so it cannot be restored after a crash).
 * Only package names and app-op modes are stored; no content.
 */
object PhoneLedger {
    private const val PREFS = "phone_ledger"
    private const val KEY_CLIP = "clipboard_borrowed"
    private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mute(context: Context, shell: IHoshinoPhoneShell, pkg: String) {
        if (!PhoneConsent.of(context).muteAgentApps) return
        val p = prefs(context)
        if (p.contains("mute:$pkg")) return
        val current = runCatching { shell.runCommand("appops get $pkg PLAY_AUDIO").getString("output").orEmpty() }.getOrDefault("")
        val mode = parseMode(current) ?: "default"
        p.edit().putString("mute:$pkg", mode).commit()
        runCatching { shell.runCommand("appops set $pkg PLAY_AUDIO ignore") }
    }

    /** Gives every muted app its original audio mode back. Returns the packages restored. */
    fun restoreAll(context: Context, shell: IHoshinoPhoneShell?): List<String> {
        val p = prefs(context)
        val entries = p.all.filterKeys { it.startsWith("mute:") }
        if (entries.isEmpty() || shell == null) return emptyList()
        val done = mutableListOf<String>()
        val edit = p.edit()
        for ((k, v) in entries) {
            val pkg = k.removePrefix("mute:")
            val mode = (v as? String)?.takeIf { it in MODES } ?: "default"
            val ok = runCatching { shell.runCommand("appops set $pkg PLAY_AUDIO $mode").getInt("exitCode", -1) == 0 }.getOrDefault(false)
            if (ok) { edit.remove(k); done += pkg }
        }
        edit.commit()
        return done
    }

    fun mutedPackages(context: Context): List<String> = prefs(context).all.keys.filter { it.startsWith("mute:") }.map { it.removePrefix("mute:") }

    /** `appops get` prints e.g. "PLAY_AUDIO: ignore; time=..." or "No operations." */
    fun parseMode(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.trim().startsWith("PLAY_AUDIO") } ?: return null
        val mode = line.substringAfter(':').substringBefore(';').trim().lowercase()
        return mode.takeIf { it in MODES }
    }

    fun borrowClipboard(context: Context) { prefs(context).edit().putBoolean(KEY_CLIP, true).commit() }
    fun returnClipboard(context: Context) { prefs(context).edit().remove(KEY_CLIP).apply() }

    /** On process start: a paste was interrupted, so our text may still be on the clipboard. */
    fun recover(context: Context) {
        if (!prefs(context).getBoolean(KEY_CLIP, false)) return
        runCatching { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        returnClipboard(context)
    }
}

/** Launcher apps: label ↔ package (PH8 list_apps / launch_app). Requires the launcher <queries> entry. */
object PhoneApps {
    data class App(val label: String, val packageName: String)

    fun list(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Exact package, else exact label, else the single label containing [query]. */
    fun resolve(context: Context, query: String): String? {
        val q = query.trim()
        val apps = list(context)
        apps.firstOrNull { it.packageName == q }?.let { return it.packageName }
        if (q == context.packageName) return q
        fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase()
        apps.filter { key(it.label) == key(q) }.singleOrNull()?.let { return it.packageName }
        return apps.filter { key(it.label).contains(key(q)) }.singleOrNull()?.packageName
    }
}
