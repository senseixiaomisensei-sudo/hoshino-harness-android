package app.hoshino.harness

import app.hoshino.harness.core.AgentPreset
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.PresetRows
import org.junit.Assert.*
import org.junit.Test

/** m2.9: saving a preset must not write `"tools": []`, which the kernel read as "no tools" and which hid phone_*. */
class PresetRowsTest {
    private fun preset(id: String, tools: List<String>, tier: PermissionTier = PermissionTier.WorkspaceWrite) =
        AgentPreset(id, "名称", "描述", false, "prompt", tools, null, tier)

    @Test fun emptySelectionMeansAllToolsAndIsNotWritten() {
        val row = PresetRows.row(preset("standard", emptyList()))
        assertFalse(row.has("tools"))
        assertFalse(PresetRows.row(preset("custom-1", emptyList())).has("tools"))
    }

    @Test fun chatKeepsAnExplicitEmptyList() {
        val row = PresetRows.row(preset("chat", emptyList()))
        assertTrue(row.has("tools"))
        assertEquals(0, row.getJSONArray("tools").length())
    }

    @Test fun explicitSelectionIsKeptAndReadOnlySurvivesSaving() {
        val row = PresetRows.row(preset("custom-2", listOf("read", "bash"), PermissionTier.ReadOnly))
        assertEquals(2, row.getJSONArray("tools").length())
        assertEquals("read", row.getJSONArray("tools").getString(0))
        assertTrue(row.getBoolean("readOnly"))
        assertFalse(PresetRows.row(preset("custom-3", listOf("read"))).has("readOnly"))
        assertEquals("custom-2", row.getString("id"))
        assertEquals("prompt", row.getString("systemPrompt"))
    }
}
