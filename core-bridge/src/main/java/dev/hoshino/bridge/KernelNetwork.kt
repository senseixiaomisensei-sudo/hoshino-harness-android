package dev.hoshino.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.io.File

/**
 * Network environment of the proot kernel (DNS, proxy) and transport-error classification.
 *
 * Root cause of "请求未完成（TRANSPORT）：Connection error." without a VPN in mainland China:
 * the kernel runs inside proot/Alpine (musl), so it does NOT use Android's resolver. Its
 * /etc/resolv.conf was written once at launch from the active network's DNS list with no fallback;
 * when that list was empty (no active network at prewarm/boot, restricted background data) or held
 * only unusable entries (link-local IPv6 with scope ids, a VPN's private DNS kept after the VPN was
 * turned off), musl fell back to 127.0.0.1 and every name lookup failed, while Android-side OkHttp
 * probes (model list, web-search probe) kept working. The file was also never refreshed on Wi-Fi ↔
 * cellular switches. With a VPN, its DNS happened to be in the list, which is why "开代理就好".
 */
object KernelNetwork {
    /** Domestic public resolvers reachable without a proxy (AliDNS, DNSPod, Baidu). */
    val FALLBACK_DNS = listOf("223.5.5.5", "119.29.29.29", "180.76.76.76")
    const val PREFS = "hoshino-network"
    const val KEY_PROXY = "proxy"
    const val KEY_INHERIT_SYSTEM = "inheritSystemProxy"
    private const val MUSL_MAXNS = 3

    /**
     * resolv.conf for musl: usable system resolvers first (IPv4 before IPv6, deduplicated, no loopback,
     * no link-local, no scope ids), then domestic fallbacks up to musl's 3-nameserver limit. musl queries
     * all listed servers in parallel and takes the first answer, so the fallbacks cost nothing when the
     * system resolver works and rescue lookups when it does not.
     */
    fun resolvConf(systemDns: List<String>): String {
        val usable = systemDns.map { it.trim().substringBefore('%') }.filter { usableNameserver(it) }.distinct()
        val ordered = usable.filter { !it.contains(':') } + usable.filter { it.contains(':') }
        val servers = (ordered.take(MUSL_MAXNS - 1) + FALLBACK_DNS).distinct().take(MUSL_MAXNS)
        return servers.joinToString("") { "nameserver $it\n" } + "options timeout:3 attempts:2\n"
    }

    internal fun usableNameserver(address: String): Boolean {
        if (address.isBlank()) return false
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(address)
        if (ipv4 != null) {
            val parts = ipv4.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return false
            return parts[0] != 127 && parts[0] != 0 && !(parts[0] == 169 && parts[1] == 254)
        }
        if (!address.contains(':') || !Regex("^[0-9A-Fa-f:.]+$").matches(address)) return false
        val lower = address.lowercase()
        return lower != "::1" && lower != "::" && !lower.startsWith("fe80:")
    }

    /** A user-entered proxy: http://host:port, https://host:port, socks5://host:port, socks5h://…, or bare host:port (= http). */
    fun normalizeProxy(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val match = Regex("^(https?|socks5h?)://(?:[^@/\\s]+@)?(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+):(\\d{1,5})/?$").matchEntire(withScheme) ?: return null
        val port = match.groupValues[3].toInt()
        if (port !in 1..65535) return null
        return withScheme.trimEnd('/')
    }

    /**
     * Environment variables handed to the kernel. An app proxy wins over the inherited system proxy.
     * Node's fetch honours HTTP(S)_PROXY when NODE_USE_ENV_PROXY=1 (Node ≥ 24); SOCKS5 is not supported by
     * Node's fetch, so a socks proxy only reaches shell tools via ALL_PROXY (curl, git, pip…).
     * Loopback always bypasses the proxy: the Android callback bridge and local Ollama live there.
     */
    fun proxyEnvironment(appProxy: String?, systemProxy: String?, localHosts: Collection<String> = emptyList()): Map<String, String> {
        val proxy = normalizeProxy(appProxy) ?: normalizeProxy(systemProxy) ?: return emptyMap()
        val env = linkedMapOf<String, String>()
        // m2.9.5: LAN model servers (Ollama on a PC) must not go through the proxy either.
        val noProxy = (listOf("127.0.0.1", "localhost", "::1", ".local", ".lan") + localHosts.filter { isLocalNetworkHost(it) }).distinct().joinToString(",")
        env["ALL_PROXY"] = proxy; env["all_proxy"] = proxy
        if (proxy.startsWith("http")) {
            env["HTTPS_PROXY"] = proxy; env["https_proxy"] = proxy
            env["HTTP_PROXY"] = proxy; env["http_proxy"] = proxy
            env["NODE_USE_ENV_PROXY"] = "1"
        }
        env["NO_PROXY"] = noProxy; env["no_proxy"] = noProxy
        return env
    }

    /** Node flags: prefer IPv4 answers (many CN networks advertise AAAA without a working IPv6 route) and fall back across families quickly. */
    val NODE_NETWORK_FLAGS = listOf("--dns-result-order=ipv4first", "--network-family-autoselection-attempt-timeout=500")

    fun systemDns(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return runCatching { cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty().mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
    }

    /** The system / Wi-Fi proxy Android reports for the active network (PAC is not supported), as http://host:port. */
    fun systemProxy(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val info = cm?.getLinkProperties(cm.activeNetwork)?.httpProxy ?: cm?.defaultProxy ?: return@runCatching null
        val host: String? = info.host
        val port = info.port
        if (host.isNullOrBlank() || port <= 0) null else "http://$host:$port"
    }.getOrNull()

    fun appProxy(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROXY, null)
    fun inheritSystemProxy(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INHERIT_SYSTEM, true)
    fun setProxy(context: Context, proxy: String?, inheritSystem: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROXY, proxy?.trim()?.takeIf { it.isNotEmpty() }).putBoolean(KEY_INHERIT_SYSTEM, inheritSystem).apply()
    }

    fun kernelProxyEnvironment(context: Context): Map<String, String> =
        proxyEnvironment(appProxy(context), if (inheritSystemProxy(context)) systemProxy(context) else null,
            runCatching { ProviderStore(context).list().mapNotNull { runCatching { java.net.URI(normalizeProviderInput(it.baseURL)).host }.getOrNull() } }.getOrDefault(emptyList()))

    /** Rewrites [file] in place (proot binds the path, so the running kernel sees new contents on its next lookup). */
    fun writeResolvConf(file: File, systemDns: List<String>) {
        val text = resolvConf(systemDns)
        if (!file.isFile || file.readText() != text) file.writeText(text)
    }

    /** Keeps resolv.conf current across Wi-Fi ↔ cellular ↔ VPN switches. Returns an unregister handle. */
    fun watchDns(context: Context, file: File): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { refresh() }
            override fun onAvailable(network: Network) { refresh() }
            override fun onLost(network: Network) { refresh() }
            private fun refresh() { runCatching { writeResolvConf(file, systemDns(context)) } }
        }
        return try {
            cm.registerDefaultNetworkCallback(callback)
            val unregister: () -> Unit = { runCatching { cm.unregisterNetworkCallback(callback) } }
            unregister
        } catch (_: RuntimeException) { {} }
    }
}

enum class TransportFailure { Dns, ConnectTimeout, Tls, Refused, Reset, Unknown }

/** Pure classification of a kernel transport error message (Node/undici/OpenAI SDK wording). */
fun classifyTransportFailure(message: String?): TransportFailure {
    val m = message.orEmpty()
    return when {
        Regex("ENOTFOUND|EAI_AGAIN|EAI_NONAME|EAI_FAIL|getaddrinfo|dns", RegexOption.IGNORE_CASE).containsMatchIn(m) -> TransportFailure.Dns
        Regex("certificate|CERT_|SSL|TLS|self[- ]signed|handshake", RegexOption.IGNORE_CASE).containsMatchIn(m) -> TransportFailure.Tls
        Regex("ETIMEDOUT|UND_ERR_CONNECT_TIMEOUT|Connect Timeout|ENETUNREACH|EHOSTUNREACH|timed? ?out", RegexOption.IGNORE_CASE).containsMatchIn(m) -> TransportFailure.ConnectTimeout
        Regex("ECONNREFUSED", RegexOption.IGNORE_CASE).containsMatchIn(m) -> TransportFailure.Refused
        Regex("ECONNRESET|socket hang up|other side closed|UND_ERR_SOCKET", RegexOption.IGNORE_CASE).containsMatchIn(m) -> TransportFailure.Reset
        else -> TransportFailure.Unknown
    }
}

/** Is this kernel failure a network/transport failure (as opposed to an HTTP/API error)? */
fun isTransportFailure(code: String?, message: String?): Boolean =
    code.equals("TRANSPORT", true) || code.equals("TIMEOUT", true) ||
        Regex("Connection error|fetch failed|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|ECONNREFUSED|ECONNRESET|UND_ERR_", RegexOption.IGNORE_CASE).containsMatchIn(message.orEmpty())

/** User-facing Chinese hint appended to transport failures. */
fun transportFailureHint(kind: TransportFailure, proxyActive: Boolean): String {
    val what = when (kind) {
        TransportFailure.Dns -> "域名解析失败（DNS）"
        TransportFailure.ConnectTimeout -> "连接超时，服务器不可达"
        TransportFailure.Tls -> "TLS/证书握手失败"
        TransportFailure.Refused -> "连接被拒绝"
        TransportFailure.Reset -> "连接被中途重置"
        TransportFailure.Unknown -> "网络连接失败"
    }
    val advice = if (proxyActive) "请检查设置 › 网络与代理里的代理地址是否可用。"
        else "该服务商在国内可能需要代理，可换国内可直连的中转站，或在设置 › 网络与代理里填 HTTP 代理。"
    return "$what。$advice"
}
