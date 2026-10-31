// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.Context
import android.content.Intent
import dev.hoshino.bridge.phone.IHoshinoPhoneShell

/**
 * System state Hoshino borrows during a phone task and must give back (RT6, PH18):
 * - apps muted on the agent display (PLAY_AUDIO app-op): the original mode is written down before the
 *   change and restored when the task ends, and again when the helper comes back after a crash;
 * - the clipboard borrowed for a paste (WP-2): only the helper (shell uid) reads and writes it, and it keeps the
 *   user's clip in its own process, which outlives a crashed app. It restores by itself 10 s after a borrow the app
 *   never returned; when the helper is reachable again after a crash we also ask it to restore right away.
 *   The app never clears the clipboard itself (it cannot tell the user's clip from ours in the background).
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

    fun clipboardBorrowed(context: Context): Boolean = prefs(context).getBoolean(KEY_CLIP, false)

    /**
     * A paste was interrupted by a crash: the helper still holds the user's clip, so ask it to put it back
     * (a no-op when its watchdog already did, or nothing is borrowed). Without a helper nothing is touched.
     */
    fun recoverClipboard(context: Context, shell: IHoshinoPhoneShell?) {
        if (!clipboardBorrowed(context) || shell == null) return
        runCatching { shell.restoreClip() }
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
