package dev.hoshino.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Provider(val id: String, val type: String, val baseURL: String, val models: List<String>, val metadata: JSONObject = JSONObject()) {
    val credentialRef get() = "KEY_" + id.uppercase().replace('-', '_')
    fun json() = JSONObject().put("id", id).put("type", type).put("baseURL", baseURL).put("apiKey", credentialRef).put("models", JSONArray(models)).put("metadata",metadata)
}

/** Only credential references are in providers.json. Secret values are AES-GCM wrapped by a non-exportable Keystore key. */
class ProviderStore(private val context: Context) {
    private val config = File(context.filesDir, "providers.json")
    private val encrypted = context.getSharedPreferences("keystore-wrapped-credentials", Context.MODE_PRIVATE)
    @Synchronized fun list(): List<Provider> = if (!config.exists()) emptyList() else {
        val array = JSONArray(config.readText())
        (0 until array.length()).map { i -> val p = array.getJSONObject(i); Provider(p.getString("id"), p.getString("type"), p.getString("baseURL"), p.getJSONArray("models").let { a -> (0 until a.length()).map { a.getString(it) } },p.optJSONObject("metadata") ?: JSONObject()) }
    }
    @Synchronized fun save(provider: Provider, apiKey: String) {
        require(provider.id.matches(Regex("[a-z][a-z0-9-]*"))) { "ID 仅支持小写字母、数字和连字符" }
        require(provider.type in listOf("anthropic", "openai-compatible"))
        val uri = java.net.URI(provider.baseURL)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的 API 地址" }
        require(provider.models.isNotEmpty() && provider.models.all { it.isNotBlank() })
        if (apiKey.isNotEmpty()) putSecret(provider.credentialRef,apiKey)
        writeConfig(list().filterNot { it.id == provider.id } + provider)
    }
    private fun writeConfig(all: List<Provider>) {
        val temp = File(context.filesDir, "providers.json.tmp")
        temp.writeText(JSONArray(all.map { it.json() }).toString())
        check(temp.renameTo(config))
    }
    @Synchronized fun putSecret(ref: String, value: String) {
        require(ref.matches(Regex("[A-Z0-9_]{1,180}")))
        if (value.isNotEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
            val wrapped = cipher.iv + cipher.doFinal(value.toByteArray())
            check(encrypted.edit().putString(ref, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit())
        }
    }
    @Synchronized fun delete(id: String) {
        list().firstOrNull { it.id==id }?.let { p ->
            val refs=p.metadata.optJSONArray("headerRefs") ?: JSONArray()
            encrypted.edit().remove(p.credentialRef).apply { for(i in 0 until refs.length()) remove(refs.getJSONObject(i).getString("ref")) }.commit()
        }
        writeConfig(list().filterNot { it.id==id })
    }
    fun hasSecret(ref: String) = encrypted.contains(ref)
    fun runtimeJson(provider: Provider): JSONObject {
        val headers=JSONObject();val refs=provider.metadata.optJSONArray("headerRefs") ?: JSONArray()
        for(i in 0 until refs.length()) { val row=refs.getJSONObject(i);headers.put(row.getString("name"),resolve(row.getString("ref")) ?: "") }
        return provider.json().put("headers",headers)
    }
    fun redact(text: String): String {
        return redactCredentials(text,encrypted.all.keys.mapNotNull { ref -> runCatching { resolve(ref) }.getOrNull() })
    }
    @Synchronized fun resolve(ref: String): String? {
        val value = encrypted.getString(ref, null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hoshino.providers", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hoshino.providers", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
