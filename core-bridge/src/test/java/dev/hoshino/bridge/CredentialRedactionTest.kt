package dev.hoshino.bridge

import org.junit.Assert.*
import org.junit.Test

class CredentialRedactionTest {
    @Test fun overlappingAndLiteralSecretsAreFullyHidden() {
        assertEquals("[凭据已隐藏] [凭据已隐藏] [凭据已隐藏]",redactCredentials("sk-test-long sk-test a.b+c",listOf("sk-test","sk-test-long","a.b+c","")))
    }
    @Test fun ordinaryCodeIsUnchanged() { assertEquals("val x = 1",redactCredentials("val x = 1",emptyList())) }
}
