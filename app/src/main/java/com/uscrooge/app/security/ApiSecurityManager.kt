package com.uscrooge.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiSecurityManager {

    companion object {
        private const val TAG = "ApiSecurityManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "uscrooge_api_key_encryption"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
    }

    private val keyStore: KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Android KeyStore", e)
        null
    }

    private val isInitialized: Boolean

    init {
        isInitialized = try {
            if (keyStore != null && !keyStore.containsAlias(KEY_ALIAS)) {
                generateKey()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate encryption key, encryption disabled", e)
            false
        }
    }

    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(parameterSpec)
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            keyStore?.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get secret key", e)
            null
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty() || !isInitialized) return plaintext

        return try {
            val secretKey = getSecretKey() ?: return plaintext
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = iv + ciphertext
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            plaintext
        }
    }

    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty() || !isInitialized) return encrypted

        return try {
            val secretKey = getSecretKey() ?: return encrypted
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            encrypted
        }
    }

    fun isKeyAvailable(): Boolean {
        return isInitialized && try {
            keyStore?.containsAlias(KEY_ALIAS) == true
        } catch (e: Exception) {
            false
        }
    }

    fun resetKey() {
        if (!isInitialized) return
        try {
            keyStore?.deleteEntry(KEY_ALIAS)
            generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset key", e)
        }
    }
}
