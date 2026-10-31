package dev.hoshino.bridge

import org.junit.Assert.*
import org.junit.Test

class KernelNetworkTest {
    private fun servers(conf: String) = conf.lines().filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }

    @Test fun emptySystemDnsFallsBackToDomesticResolvers() {
        val conf = KernelNetwork.resolvConf(emptyList())
        assertEquals(listOf("223.5.5.5", "119.29.29.29", "180.76.76.76"), servers(conf))
        assertFalse(conf.contains("8.8.8.8")); assertFalse(conf.contains("1.1.1.1"))
        assertTrue(conf.contains("options timeout:3 attempts:2"))
    }

    @Test fun systemDnsComesFirstIpv4BeforeIpv6AndFallbackStaysWithinMuslLimit() {
        val conf = KernelNetwork.resolvConf(listOf("2409:8088::a", "192.168.1.1", "211.138.24.66", "192.168.1.1"))
        val list = servers(conf)
        assertEquals(3, list.size)
        assertEquals(listOf("192.168.1.1", "211.138.24.66", "223.5.5.5"), list)
    }

    @Test fun unusableEntriesAreDropped() {
        val conf = KernelNetwork.resolvConf(listOf("127.0.0.1", "fe80::1%wlan0", "::1", "0.0.0.0", "169.254.1.1", "999.1.1.1", "not-an-ip", ""))
        assertEquals(KernelNetwork.FALLBACK_DNS, servers(conf))
    }

    @Test fun scopedGlobalIpv6KeepsAddressWithoutScope() {
        val list = servers(KernelNetwork.resolvConf(listOf("2408:8000::8%rmnet0")))
        assertEquals("2408:8000::8", list.first())
        assertTrue(list.none { it.contains('%') })
    }

    @Test fun writeResolvConfRewritesInPlace() {
        val file = java.io.File.createTempFile("resolv", ".conf")
        try {
            KernelNetwork.writeResolvConf(file, listOf("10.0.0.1"))
            assertEquals("10.0.0.1", servers(file.readText()).first())
            KernelNetwork.writeResolvConf(file, emptyList())
            assertEquals("223.5.5.5", servers(file.readText()).first())
        } finally { file.delete() }
    }

    @Test fun proxyNormalization() {
        assertEquals("http://127.0.0.1:7890", KernelNetwork.normalizeProxy(" 127.0.0.1:7890 "))
        assertEquals("socks5://127.0.0.1:7891", KernelNetwork.normalizeProxy("socks5://127.0.0.1:7891/"))
        assertEquals("http://u:p@proxy.example:8080", KernelNetwork.normalizeProxy("http://u:p@proxy.example:8080"))
        assertNull(KernelNetwork.normalizeProxy(""))
        assertNull(KernelNetwork.normalizeProxy("ftp://x:1"))
        assertNull(KernelNetwork.normalizeProxy("127.0.0.1"))
        assertNull(KernelNetwork.normalizeProxy("127.0.0.1:70000"))
    }

    @Test fun httpProxyInjectsNodeAndShellVariablesAndBypassesLoopback() {
        val env = KernelNetwork.proxyEnvironment("127.0.0.1:7890", null)
        assertEquals("http://127.0.0.1:7890", env["HTTPS_PROXY"])
        assertEquals("http://127.0.0.1:7890", env["https_proxy"])
        assertEquals("http://127.0.0.1:7890", env["HTTP_PROXY"])
        assertEquals("http://127.0.0.1:7890", env["ALL_PROXY"])
        assertEquals("1", env["NODE_USE_ENV_PROXY"])
        assertTrue(env.getValue("NO_PROXY").split(',').containsAll(listOf("127.0.0.1", "localhost", "::1")))
    }

    @Test fun socksProxyOnlyReachesAllProxy() {
        val env = KernelNetwork.proxyEnvironment("socks5://127.0.0.1:7891", null)
        assertEquals("socks5://127.0.0.1:7891", env["ALL_PROXY"])
        assertNull(env["HTTPS_PROXY"]); assertNull(env["NODE_USE_ENV_PROXY"])
    }

    @Test fun appProxyWinsOverSystemAndSystemIsInheritedWhenAppEmpty() {
        assertEquals("http://10.0.0.2:8080", KernelNetwork.proxyEnvironment("10.0.0.2:8080", "http://192.168.1.5:3128")["HTTPS_PROXY"])
        assertEquals("http://192.168.1.5:3128", KernelNetwork.proxyEnvironment("", "http://192.168.1.5:3128")["HTTPS_PROXY"])
        assertTrue(KernelNetwork.proxyEnvironment(null, null).isEmpty())
        assertTrue(KernelNetwork.proxyEnvironment("garbage", null).isEmpty())
    }

    @Test fun nodeFlagsPreferIpv4() {
        assertTrue(KernelNetwork.NODE_NETWORK_FLAGS.contains("--dns-result-order=ipv4first"))
    }

    @Test fun transportClassification() {
        assertEquals(TransportFailure.Dns, classifyTransportFailure("getaddrinfo EAI_AGAIN api.openai.com"))
        assertEquals(TransportFailure.Dns, classifyTransportFailure("Connection error. cause: ENOTFOUND relay.example.cn"))
        assertEquals(TransportFailure.ConnectTimeout, classifyTransportFailure("Connect Timeout Error (UND_ERR_CONNECT_TIMEOUT)"))
        assertEquals(TransportFailure.ConnectTimeout, classifyTransportFailure("connect ETIMEDOUT 104.18.1.1:443"))
        assertEquals(TransportFailure.Tls, classifyTransportFailure("unable to verify the first certificate"))
        assertEquals(TransportFailure.Tls, classifyTransportFailure("SSL routines:ssl3_get_record:wrong version number"))
        assertEquals(TransportFailure.Refused, classifyTransportFailure("connect ECONNREFUSED 127.0.0.1:11434"))
        assertEquals(TransportFailure.Reset, classifyTransportFailure("read ECONNRESET"))
        assertEquals(TransportFailure.Unknown, classifyTransportFailure("Connection error."))
        assertEquals(TransportFailure.Unknown, classifyTransportFailure(null))
    }

    @Test fun transportDetectionAndHints() {
        assertTrue(isTransportFailure("TRANSPORT", "Connection error."))
        assertTrue(isTransportFailure(null, "fetch failed"))
        assertFalse(isTransportFailure("HTTP", "Incorrect API key provided"))
        val direct = transportFailureHint(TransportFailure.Dns, proxyActive = false)
        assertTrue(direct.contains("DNS")); assertTrue(direct.contains("中转站")); assertTrue(direct.contains("代理"))
        assertTrue(transportFailureHint(TransportFailure.ConnectTimeout, false).contains("超时"))
        assertTrue(transportFailureHint(TransportFailure.Tls, false).contains("TLS"))
        assertTrue(transportFailureHint(TransportFailure.Unknown, proxyActive = true).contains("代理地址"))
    }
}
