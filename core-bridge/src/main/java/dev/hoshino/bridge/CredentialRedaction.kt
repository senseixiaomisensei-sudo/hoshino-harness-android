package dev.hoshino.bridge

fun redactCredentials(text: String,secrets: Iterable<String>): String = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }.fold(text) { safe,secret -> safe.replace(secret,"[凭据已隐藏]") }
