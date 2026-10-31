package dev.hoshino.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaLanTest {
    @Test fun schemeLessInputBecomesHttp() {
        assertEquals("http://192.168.1.5:11434", normalizeProviderInput(" 192.168.1.5:11434 "))
        assertEquals("https://api.x.com/v1", normalizeProviderInput("https://api.x.com/v1"))
        assertEquals("http://192.168.1.5:11434/v1", providerBaseURL("openai-compatible", "192.168.1.5:11434"))
    }

    @Test fun localNetworkHosts() {
        listOf("192.168.1.5", "10.0.0.2", "172.20.1.1", "127.0.0.1", "localhost", "my-pc.local", "100.101.1.2", "desktop").forEach { assertTrue(it, isLocalNetworkHost(it)) }
        listOf("api.openai.com", "8.8.8.8", "172.32.0.1", "") .forEach { assertFalse(it, isLocalNetworkHost(it)) }
    }

    @Test fun noProxyCoversLanProviders() {
        val env = KernelNetwork.proxyEnvironment("http://10.1.1.1:7890", null, listOf("192.168.1.5", "api.openai.com"))
        val np = env.getValue("NO_PROXY").split(',')
        assertTrue("192.168.1.5" in np); assertFalse("api.openai.com" in np); assertTrue("127.0.0.1" in np)
    }

    @Test fun readableErrors() {
        val uri = java.net.URI("http://192.168.1.5:11434/v1")
        assertTrue(describeNetworkError(java.net.ConnectException("refused"), uri).contains("OLLAMA_HOST=0.0.0.0"))
        assertTrue(describeNetworkError(java.net.ConnectException("refused"), java.net.URI("http://127.0.0.1:11434/v1")).contains("手机自己"))
        assertTrue(describeNetworkError(java.net.SocketTimeoutException(), uri).contains("超时"))
    }
}
