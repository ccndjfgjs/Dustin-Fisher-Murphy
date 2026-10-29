package dev.opencode.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The data key remains in Android Keystore; only authenticated ciphertext is persisted. */
internal class SettingsCipher(private val alias: String) {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Ключ шифрования настроек отсутствует")

    private fun keyForEncryption(): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf("v1", encode(cipher.iv), encode(encrypted)).joinToString(":")
    }

    fun decrypt(envelope: String): String {
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Неизвестный формат зашифрованных настроек" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size >= 16) { "Повреждены зашифрованные настройки" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        val AAD = "OpenCodeMobile.settings.v1".toByteArray(Charsets.UTF_8)
    }
}
