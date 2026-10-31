package dev.hoshino.bridge.chatgpt

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptModelsTest {
    private val offline = JSONArray("""[
        {"id":"gpt-6.1-terra","displayName":"GPT-6.1 Terra","contextLength":1050000,"maxOutput":128000,"vision":true,"reasoning":true,"defaultEffort":"Medium","reasoningLevels":["none","low","medium","high","xhigh","max"]},
        {"id":"gpt-6-sol","displayName":"GPT-6 Sol","contextLength":272000,"maxOutput":128000,"vision":true,"reasoning":true,"defaultEffort":"Medium"}
    ]""")

    @Test fun openAiListKeepsChatModelsInServerOrder() {
        val rows = ChatGptModels.parse("""{"object":"list","data":[{"id":"gpt-6.1-terra"},{"id":"text-embedding-3-large"},{"id":"gpt-image-2"},{"id":"gpt-7-nova","type":"chat"},{"id":"tts-1"},{"id":"gpt-6.1-terra"}]}""")
        assertEquals(listOf("gpt-6.1-terra", "gpt-7-nova"), (0 until rows.length()).map { rows.getJSONObject(it).getString("id") })
    }

    @Test fun chatGptListUsesSlugVisibilityAndStatedCapabilities() {
        val rows = ChatGptModels.parse("""{"models":[
            {"slug":"gpt-6.1-terra","display_name":"GPT-6.1 Terra","visibility":"list","input_modalities":["text","image"],"context_window":1050000,"supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"xhigh"}],"default_reasoning_level":"medium"},
            {"slug":"internal-x","visibility":"hide"}]}""")
        assertEquals(1, rows.length())
        val r = rows.getJSONObject(0)
        assertEquals("GPT-6.1 Terra", r.getString("displayName"))
        assertTrue(r.getBoolean("vision"))
        assertEquals(1050000, r.getInt("contextLength"))
        assertEquals(listOf("low", "medium", "xhigh"), r.getJSONArray("reasoningLevels").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun mergeTakesListedModelsAndFillsFromTheOfflineTable() {
        val merged = ChatGptModels.merge(ChatGptModels.parse("""{"data":[{"id":"gpt-6.1-terra"},{"id":"gpt-7-nova"},{"id":"legacy-chat"}]}"""), offline)
        assertEquals(3, merged.length())
        val terra = merged.getJSONObject(0)
        assertEquals(1050000, terra.getInt("contextLength"))
        assertEquals(128000, terra.getInt("maxOutput"))
        assertTrue(terra.getBoolean("vision"))
        assertEquals("Medium", terra.getString("defaultEffort"))
        assertEquals(6, terra.getJSONArray("reasoningLevels").length())
        val nova = merged.getJSONObject(1)
        assertTrue("GPT-5+ ids reason (documented inference)", nova.getBoolean("reasoning"))
        assertFalse("vision is not guessed", nova.getBoolean("vision"))
        assertFalse(merged.getJSONObject(2).getBoolean("reasoning"))
        assertFalse("models the account does not list are dropped", (0 until merged.length()).any { merged.getJSONObject(it).getString("id") == "gpt-6-sol" })
    }

    @Test fun offlineFallbackReturnsTheTable() {
        assertEquals(2, ChatGptModels.merge(null, offline).length())
    }

    @Test fun fetchSendsTheBearerAndMapsErrors() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gpt-6.1-terra"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"subscription_sharing_user_not_eligible"}}"""))
            val base = server.url("/v1").toString()
            assertEquals("gpt-6.1-terra", ChatGptModels.fetch(OkHttpClient(), base, "tok").getJSONObject(0).getString("id"))
            val r = server.takeRequest()
            assertEquals("/v1/models", r.path)
            assertEquals("Bearer tok", r.getHeader("Authorization"))
            try { ChatGptModels.fetch(OkHttpClient(), base, "tok"); fail() } catch (e: ModelListException) { assertEquals(403, e.status); assertTrue(e.message!!.contains("Plus")) }
        } finally { server.shutdown() }
    }

    @Test fun shippedOfflineTableHasTerraWithDocumentedLimits() {
        val file = listOf("../app/src/main/assets/codex-models.json", "app/src/main/assets/codex-models.json").map { java.io.File(it) }.first { it.exists() }
        val table = JSONArray(file.readText())
        val terra = (0 until table.length()).map { table.getJSONObject(it) }.first { it.getString("id") == "gpt-6.1-terra" }
        assertEquals(1050000, terra.getInt("contextLength"))
        assertEquals(128000, terra.getInt("maxOutput"))
        assertTrue(terra.getBoolean("vision"))
        assertEquals("Medium", terra.getString("defaultEffort"))
        assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), terra.getJSONArray("reasoningLevels").let { a -> (0 until a.length()).map { a.getString(it) } })
        JSONObject(terra.toString())
    }

    @Test fun refreshKeepsUserContextAndOutputOverrides() {
        // Stored rows: the user raised terra's output and lowered its window, and set search off.
        val stored = ChatGptModels.applyUserLimits(ChatGptModels.merge(null, offline), null)
        val terraRow = (0 until stored.length()).map { stored.getJSONObject(it) }.first { it.getString("id") == "gpt-6.1-terra" }
        ChatGptModels.setUserLimits(terraRow, 400000, 64000)
        terraRow.put("webSearchMode", "off")
        assertEquals(400000, terraRow.getInt("contextLength"))
        assertEquals(1050000, terraRow.getInt("officialContextLength"))

        // A fresh /v1/models listing (which states its own context window) is merged and saved over the stored rows.
        val fresh = ChatGptModels.merge(ChatGptModels.parse("""{"data":[{"id":"gpt-6.1-terra","context_window":1050000},{"id":"gpt-7-nova"}]}"""), offline)
        val saved = ChatGptModels.applyUserLimits(fresh, stored)
        val terra = saved.getJSONObject(0)
        assertEquals("user window survives the refresh", 400000, terra.getInt("contextLength"))
        assertEquals("user output survives the refresh", 64000, terra.getInt("maxOutput"))
        assertEquals(1050000, terra.getInt("officialContextLength"))
        assertEquals(128000, terra.getInt("officialMaxOutput"))
        assertEquals("off", terra.getString("webSearchMode"))
        val nova = saved.getJSONObject(1)
        assertEquals("models without an override keep their own limits", 128000, nova.getInt("contextLength"))
        assertFalse(nova.has("userContextLength"))

        // Saving again with the stored rows themselves is stable.
        val again = ChatGptModels.applyUserLimits(JSONArray(saved.toString()), saved)
        assertEquals(400000, again.getJSONObject(0).getInt("contextLength"))
    }

    @Test fun restoringTheDefaultClearsTheOverride() {
        val row = JSONObject().put("id", "gpt-6.1-terra").put("contextLength", 1050000).put("maxOutput", 128000)
        ChatGptModels.setUserLimits(row, 200000, 128000)
        assertEquals(200000, row.getInt("userContextLength"))
        assertFalse("an output equal to the model's own limit is not stored as an override", row.has("userMaxOutput"))
        ChatGptModels.setUserLimits(row, 1050000, 128000)
        assertFalse(row.has("userContextLength"))
        assertEquals(1050000, row.getInt("contextLength"))
        // Above the official limit is allowed (with a warning in the UI).
        ChatGptModels.setUserLimits(row, 2000000, 128000)
        assertEquals(2000000, row.getInt("contextLength"))
        assertTrue(ChatGptModels.limitWarning(2000000, 1050000) != null)
    }

    @Test fun limitsBelowTheFloorAreRejected() {
        val row = JSONObject().put("id", "gpt-6.1-terra").put("contextLength", 1050000).put("maxOutput", 128000)
        try { ChatGptModels.setUserLimits(row, 2048, 1024); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("4096")) }
        try { ChatGptModels.setUserLimits(row, 8192, 8192); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("小于")) }
        assertEquals(1050000, row.getInt("contextLength"))
    }

}
