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

data class Provider(val id: String, val type: String, val baseURL: String, val models: List<String>) {
    val credentialRef get() = "KEY_" + id.uppercase().replace('-', '_')
    fun json() = JSONObject().put("id", id).put("type", type).put("baseURL", baseURL).put("apiKey", credentialRef).put("models", JSONArray(models))
}

/** Only credential references are in providers.json. Secret values are AES-GCM wrapped by a non-exportable Keystore key. */
class ProviderStore(private val context: Context) {
    private val config = File(context.filesDir, "providers.json")
    private val encrypted = context.getSharedPreferences("keystore-wrapped-credentials", Context.MODE_PRIVATE)
    @Synchronized fun list(): List<Provider> = if (!config.exists()) emptyList() else {
        val array = JSONArray(config.readText())
        (0 until array.length()).map { i -> val p = array.getJSONObject(i); Provider(p.getString("id"), p.getString("type"), p.getString("baseURL"), p.getJSONArray("models").let { a -> (0 until a.length()).map { a.getString(it) } }) }
    }
    @Synchronized fun save(provider: Provider, apiKey: String) {
        require(provider.id.matches(Regex("[a-z][a-z0-9-]*"))) { "ID 仅支持小写字母、数字和连字符" }
        require(provider.type in listOf("anthropic", "openai-compatible"))
        val uri = java.net.URI(provider.baseURL)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的 API 地址" }
        require(provider.models.isNotEmpty() && provider.models.all { it.isNotBlank() })
        if (apiKey.isNotEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, masterKey()) }
            val value = cipher.iv + cipher.doFinal(apiKey.toByteArray())
            check(encrypted.edit().putString(provider.credentialRef, Base64.encodeToString(value, Base64.NO_WRAP)).commit())
        }
        val all = list().filterNot { it.id == provider.id } + provider
        val temp = File(context.filesDir, "providers.json.tmp")
        temp.writeText(JSONArray(all.map { it.json() }).toString())
        check(temp.renameTo(config))
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
