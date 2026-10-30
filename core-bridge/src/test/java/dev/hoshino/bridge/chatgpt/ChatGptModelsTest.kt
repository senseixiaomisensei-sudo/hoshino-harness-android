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
}
