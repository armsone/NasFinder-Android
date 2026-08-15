package com.armsone.nasfinder.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("credentials.v1", Context.MODE_PRIVATE)
    private val keyAlias = "com.armsone.nasfinder.credentials"

    fun save(connectionId: String, password: String) {
        if (password.isEmpty()) return delete(connectionId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val value = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(connectionId, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    fun read(connectionId: String): String? = runCatching {
        val bytes = Base64.decode(preferences.getString(connectionId, null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun delete(connectionId: String) {
        preferences.edit().remove(connectionId).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
