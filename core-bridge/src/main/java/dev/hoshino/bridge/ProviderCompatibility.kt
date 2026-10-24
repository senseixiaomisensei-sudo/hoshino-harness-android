package dev.hoshino.bridge

/** Repair the previous editable mask and accept keys copied from Authorization examples. */
fun normalizeApiKey(input: String): String = input.trim().removePrefix("••••••••").trim()
    .replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

fun providerBaseURL(type: String, input: String): String {
    val uri = java.net.URI(input.trim())
    var path = uri.path.orEmpty().trimEnd('/')
    path = if (type == "anthropic") path.removeSuffix("/messages").removeSuffix("/v1")
        else path.removeSuffix("/chat/completions").ifEmpty { "/v1" }
    return java.net.URI(uri.scheme, null, uri.host, uri.port, path, null, null).toString().trimEnd('/')
}
