package io.github.jonjonesbr.jangada

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chaves de API guardadas com criptografia. A chave que cifra fica no
 * Android Keystore (hardware do celular, quando disponível) e nunca sai
 * dele; no armazenamento do app só fica o texto cifrado.
 *
 * As chaves viram variáveis de ambiente (ex.: OPENAI_API_KEY) nas sessões
 * e nos chats abertos depois de salvas.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun names(): List<String> = prefs.all.keys.sorted()

    fun has(name: String) = prefs.contains(name)

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? = runCatching {
        val blob = prefs.getString(name, null) ?: return null
        val (iv, data) = blob.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun remove(name: String) = prefs.edit().remove(name).apply()

    /** Todas as chaves, decifradas, como "NOME=valor" para o ambiente do Linux. */
    fun environment(): List<String> = names().mapNotNull { name -> get(name)?.let { "$name=$it" } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "jangada_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        val VALID_NAME = Regex("""^[A-Z_][A-Z0-9_]{1,63}$""")
    }
}
