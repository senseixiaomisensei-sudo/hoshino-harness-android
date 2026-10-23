package dev.hoshino.bridge
import org.junit.Assert.*
import org.junit.Test
class DiagnosticsTest {
    @Test fun arbitraryChildOutputNeverReachesDiagnostics() {
        listOf("sk-secret-token", "Error: sk-secret-token", "proot error: user file contents").forEach {
            val value=classifyDiagnostic(it)
            assertFalse(value.contains("sk-secret-token"));assertFalse(value.contains("user file contents"))
        }
    }
}
