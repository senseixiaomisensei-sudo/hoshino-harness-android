package app.hoshino.harness.core

/** Which system executor serves full-access / system_shell. Shizuku wins when both are ready. */
enum class SystemAccessRoute(val label: String) {
    None("未就绪"), Shizuku("Shizuku"), Builtin("内置无线调试助手"),
}

/**
 * Pure decisions behind full-access (m2.8.1). Full-access no longer means "Shizuku only": the built-in
 * wireless-debugging helper (paired once, helper process running as adb uid 2000) is an equal executor.
 * Turning full-access on is still a manual per-session choice with a risk dialog; nothing here enables it.
 */
object SystemAccess {
    const val REQUIREMENT = "需要 Shizuku 或 内置无线调试助手"
    const val UNAVAILABLE = "Shizuku 未运行/未授权，且内置无线调试助手未就绪：请到 设置 → 设置手机操作 配对并启动内置无线调试助手（或启动并授权 Shizuku）"
    const val ENABLE_FIRST = "完全访问$REQUIREMENT：请到 设置 → 设置手机操作 配对内置无线调试助手，或启动并授权 Shizuku"
    const val SESSION_NOT_FULL_ACCESS = "系统命令仅允许在已授权的完全访问会话中执行（在输入框权限里手动开启完全访问）"

    fun route(shizukuReady: Boolean, builtinPaired: Boolean, builtinRunning: Boolean): SystemAccessRoute = when {
        shizukuReady -> SystemAccessRoute.Shizuku
        builtinPaired && builtinRunning -> SystemAccessRoute.Builtin
        else -> SystemAccessRoute.None
    }

    fun authorized(route: SystemAccessRoute) = route != SystemAccessRoute.None

    /**
     * Whether a system-shell callback may run for this session. The kernel's own session tier (already gated by
     * the 'system-access' check when it was set) is authoritative when the kernel reports it; the local row is a
     * cache that can lag behind (e.g. the first turn of a new session sends the composer's tier). A kernel that
     * reports another tier always refuses, whatever the local row says.
     */
    fun sessionAllowsSystemShell(localTier: PermissionTier?, kernelTier: String?): Boolean =
        if (!kernelTier.isNullOrEmpty()) kernelTier == PermissionTier.FullAccess.key else localTier == PermissionTier.FullAccess

    /** Order in which executors are tried for one system-shell call. */
    fun executors(shizukuReady: Boolean, builtinReady: Boolean): List<SystemAccessRoute> = buildList {
        if (shizukuReady) add(SystemAccessRoute.Shizuku)
        if (builtinReady) add(SystemAccessRoute.Builtin)
    }
}
