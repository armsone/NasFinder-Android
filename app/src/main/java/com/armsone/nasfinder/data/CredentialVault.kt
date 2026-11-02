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
        preferences.edit().putString(connectionId, encrypted(password)).apply()
    }

    /**
     * 여러 키를 한 번의 SharedPreferences 편집으로 저장·삭제한다. 암호화를 모두 끝낸 뒤 편집을 시작하므로
     * 암호화 실패는 아무것도 바꾸지 않고, 묶음은 전부 이전 값이거나 전부 새 값으로만 남는다.
     * 디스크에 기록하지 못하면 false를 돌려준다.
     */
    fun commitBatch(saves: Map<String, String> = emptyMap(), deletes: Set<String> = emptySet()): Boolean {
        require(saves.values.none(String::isEmpty))
        val values = saves.mapValues { (_, value) -> encrypted(value) }
        return preferences.edit().apply {
            deletes.forEach { remove(it) }
            values.forEach { (key, value) -> putString(key, value) }
        }.commit()
    }

    /** read()의 null이 값이 없는 것인지 저장된 값을 읽지 못한 것인지 구분할 때 쓴다. */
    fun contains(connectionId: String): Boolean = preferences.contains(connectionId)

    private fun encrypted(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
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
