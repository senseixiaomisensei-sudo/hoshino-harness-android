package dev.hoshino.bridge

/** Repair the previous editable mask and accept keys copied from Authorization examples. */
fun normalizeApiKey(input: String): String = input.trim().removePrefix("••••••••").trim()
    .replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

/** m2.9.5: "192.168.1.5:11434" (no scheme) means http://192.168.1.5:11434. */
fun normalizeProviderInput(input: String): String {
    val raw = input.trim()
    if (raw.isEmpty() || raw.contains("://")) return raw
    return "http://$raw"
}

/** Loopback, RFC1918, link-local, CGNAT (Tailscale) or *.local / bare host names: never sent through a proxy. */
fun isLocalNetworkHost(host: String?): Boolean {
    val h = host?.trim()?.trim('[', ']')?.lowercase() ?: return false
    if (h.isEmpty()) return false
    if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
    if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(':')
    val parts = h.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || h.split('.').size != 4) return !h.contains('.') && !h.contains(':')
    val (a, b) = parts[0] to parts[1]
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
}

fun providerBaseURL(type: String, input: String): String {
    val uri = java.net.URI(normalizeProviderInput(input))
    var path = uri.path.orEmpty().trimEnd('/')
    path = if (type == "anthropic") path.removeSuffix("/messages").removeSuffix("/v1")
        else path.removeSuffix("/chat/completions").ifEmpty { "/v1" }
    return java.net.URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString().trimEnd('/')
}
