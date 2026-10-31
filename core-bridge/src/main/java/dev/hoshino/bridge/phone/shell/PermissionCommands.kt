// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

/**
 * Shell commands the helper runs right after pairing so the user never fights Android's
 * "restricted settings" for sideloaded apps: enable our accessibility service by writing the secure
 * setting directly, allow the restricted-settings app-op, grant WRITE_SECURE_SETTINGS (used later to turn
 * wireless debugging back on after a reboot) and exempt the app from battery optimisation.
 * Every command only touches Hoshino's own package or appends our own service; other services stay.
 */
object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__hoshino_exit="

    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun allowRestrictedSettings(pkg: String) = "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow"
    fun whitelist(pkg: String) = "cmd deviceidle whitelist +$pkg"

    /**
     * New value of `enabled_accessibility_services` with [service] (flattened component) added.
     * Existing entries are kept in order; duplicates and blanks are dropped; "null" (unset) reads as empty.
     * Returns null when the service is already present (nothing to write).
     */
    fun withService(current: String?, service: String): String? {
        val items = parse(current)
        if (items.any { same(it, service) }) return null
        return (items + service).joinToString(":")
    }

    fun parse(current: String?): List<String> {
        val text = current?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return emptyList()
        return text.split(':').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** Package/class may be written in short form (`pkg/.Cls`) or full form (`pkg/pkg.Cls`). */
    fun same(a: String, b: String): Boolean = expand(a).equals(expand(b), ignoreCase = true)

    private fun expand(c: String): String {
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/', "")
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }

    fun writeAccessibility(list: String): List<String> =
        listOf("settings put secure enabled_accessibility_services ${HelperHandoff.quote(list)}", "settings put secure accessibility_enabled 1")

    /** adbd exec: gives no exit code; append a marker line. */
    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}
