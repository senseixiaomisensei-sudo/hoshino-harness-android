package dev.hoshino.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCompatibilityTest {
    @Test fun keysCopiedOverMaskOrFromAuthorizationRemainUsable() {
        assertEquals("sk-example",normalizeApiKey("  ••••••••Bearer sk-example  "))
        assertEquals("sk-example",normalizeApiKey("bearer sk-example"))
        assertEquals("",normalizeApiKey("••••••••"))
        assertEquals("a.b+c",normalizeApiKey("a.b+c"))
    }
    @Test fun nativeSdkAndModelListShareTheSameBase() {
        assertEquals("https://relay.example/api",providerBaseURL("anthropic","https://relay.example/api/v1/messages/"))
        assertEquals("https://relay.example",providerBaseURL("anthropic","https://relay.example/v1"))
        assertEquals("https://relay.example/v1",providerBaseURL("openai-compatible","https://relay.example/v1/chat/completions"))
        assertEquals("http://127.0.0.1:11434/v1",providerBaseURL("openai-compatible","http://127.0.0.1:11434/"))
    }
}
