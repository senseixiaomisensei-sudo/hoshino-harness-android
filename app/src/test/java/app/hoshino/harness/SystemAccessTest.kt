package app.hoshino.harness

import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.SystemAccess
import app.hoshino.harness.core.SystemAccessRoute
import org.junit.Assert.*
import org.junit.Test

/** m2.8.1: full-access is no longer Shizuku-only; the built-in wireless-debugging helper counts as a system executor. */
class SystemAccessTest {
    @Test fun shizukuOrReadyBuiltinHelperAuthorizes() {
        assertEquals(SystemAccessRoute.Shizuku, SystemAccess.route(shizukuReady = true, builtinPaired = false, builtinRunning = false))
        assertEquals(SystemAccessRoute.Shizuku, SystemAccess.route(shizukuReady = true, builtinPaired = true, builtinRunning = true))
        assertEquals(SystemAccessRoute.Builtin, SystemAccess.route(shizukuReady = false, builtinPaired = true, builtinRunning = true))
        assertTrue(SystemAccess.authorized(SystemAccess.route(false, true, true)))
    }

    @Test fun helperMustBeBothPairedAndRunning() {
        assertEquals(SystemAccessRoute.None, SystemAccess.route(false, builtinPaired = true, builtinRunning = false))
        assertEquals(SystemAccessRoute.None, SystemAccess.route(false, builtinPaired = false, builtinRunning = true))
        assertEquals(SystemAccessRoute.None, SystemAccess.route(false, false, false))
        assertFalse(SystemAccess.authorized(SystemAccessRoute.None))
    }

    @Test fun kernelTierIsAuthoritativeOverAStaleLocalRow() {
        // The bug: kernel had full-access (composer tier sent with the turn) while the local row still said workspace-write.
        assertTrue(SystemAccess.sessionAllowsSystemShell(PermissionTier.WorkspaceWrite, "full-access"))
        assertTrue(SystemAccess.sessionAllowsSystemShell(null, "full-access"))
        // A kernel that says anything else always refuses, even if the local row claims full-access.
        assertFalse(SystemAccess.sessionAllowsSystemShell(PermissionTier.FullAccess, "workspace-write"))
        assertFalse(SystemAccess.sessionAllowsSystemShell(PermissionTier.FullAccess, "read-only"))
        // Older kernels without a tier field: fall back to the local row.
        assertTrue(SystemAccess.sessionAllowsSystemShell(PermissionTier.FullAccess, null))
        assertTrue(SystemAccess.sessionAllowsSystemShell(PermissionTier.FullAccess, ""))
        assertFalse(SystemAccess.sessionAllowsSystemShell(PermissionTier.WorkspaceWrite, null))
        assertFalse(SystemAccess.sessionAllowsSystemShell(null, null))
    }

    @Test fun executorOrderPrefersShizukuThenFallsBackToBuiltin() {
        assertEquals(listOf(SystemAccessRoute.Shizuku, SystemAccessRoute.Builtin), SystemAccess.executors(shizukuReady = true, builtinReady = true))
        assertEquals(listOf(SystemAccessRoute.Builtin), SystemAccess.executors(shizukuReady = false, builtinReady = true))
        assertEquals(listOf(SystemAccessRoute.Shizuku), SystemAccess.executors(shizukuReady = true, builtinReady = false))
        assertTrue(SystemAccess.executors(false, false).isEmpty())
    }

    @Test fun userFacingTextNamesBothExecutorsAndTheSetupPage() {
        assertTrue(SystemAccess.REQUIREMENT.contains("Shizuku") && SystemAccess.REQUIREMENT.contains("内置无线调试助手"))
        assertTrue(SystemAccess.UNAVAILABLE.contains("设置 → 设置手机操作"))
        assertTrue(SystemAccess.ENABLE_FIRST.contains("设置 → 设置手机操作"))
        assertTrue(PermissionTier.FullAccess.description.contains("内置无线调试助手"))
    }
}
