package app.hoshino.harness.core

import org.json.JSONArray
import org.json.JSONObject

/** modes.json rows written by the app (pure, unit-tested). */
object PresetRows {
    /**
     * An empty tool selection means "every tool" and is written without a `tools` field, so tools added later
     * (phone_* in m2.8) are not hidden. Only the chat preset keeps an explicit empty list (= no tools).
     */
    fun row(preset: AgentPreset): JSONObject = JSONObject()
        .put("id", preset.id).put("name", preset.name).put("description", preset.description).put("systemPrompt", preset.systemPrompt)
        .apply {
            if (preset.tools.isNotEmpty() || preset.id == "chat") put("tools", JSONArray(preset.tools))
            // refreshControl maps readOnly <-> PermissionTier.ReadOnly; keep it so saving a read-only preset does not widen it.
            if (preset.defaultTier == PermissionTier.ReadOnly) put("readOnly", true)
        }
}
