package dev.pocketshare.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Reversible encryption used for the named-tunnel token. */
interface SecretCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String?

    fun deleteKey()
}

/**
 * AES-256-GCM keys kept in the Android Keystore. The encrypted blob contains a version byte, IV
 * and ciphertext. No key material ever leaves the Keystore.
 */
@Singleton
class AndroidKeystoreSecretCipher @Inject constructor() : SecretCipher {
    override fun encrypt(plaintext: String): String {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(2 + iv.size + encrypted.size)
            payload[0] = VERSION
            payload[1] = iv.size.toByte()
            iv.copyInto(payload, destinationOffset = 2)
            encrypted.copyInto(payload, destinationOffset = 2 + iv.size)
            return Base64.getEncoder().encodeToString(payload)
        }
    }

    override fun decrypt(ciphertext: String): String? =
        try {
            synchronized(lock) {
                val payload = Base64.getDecoder().decode(ciphertext)
                if (payload.size < 3 || payload[0] != VERSION) return null
                val ivLength = payload[1].toInt() and 0xff
                if (ivLength !in 12..16 || payload.size <= 2 + ivLength) return null
                val iv = payload.copyOfRange(2, 2 + ivLength)
                val encrypted = payload.copyOfRange(2 + ivLength, payload.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }

    override fun deleteKey() {
        synchronized(lock) {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pocketshare_named_tunnel_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        val lock = Any()
    }
}
