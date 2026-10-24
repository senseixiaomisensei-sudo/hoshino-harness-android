package dev.hoshino.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuConnectionTest {
    @Test fun installedManagerIsNeverReportedMissing() {
        assertEquals(ShizukuConnectionState.NotRunning,shizukuConnectionState(true,false,false))
        assertEquals(ShizukuConnectionState.NotAuthorized,shizukuConnectionState(true,true,false))
        assertEquals(ShizukuConnectionState.Ready,shizukuConnectionState(true,true,true))
        assertEquals(ShizukuConnectionState.NotInstalled,shizukuConnectionState(false,false,false))
    }
    @Test fun binderCanBeSuppliedBySuiWithoutTheManager() {
        assertEquals(ShizukuConnectionState.NotAuthorized,shizukuConnectionState(false,true,false))
        assertEquals(ShizukuConnectionState.Ready,shizukuConnectionState(false,true,true))
    }
}
