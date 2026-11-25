package com.wasti.airpad

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores per-host Wi-Fi bearer tokens encrypted with a non-exportable Android Keystore key.
 * The desktop stores only SHA-256 token hashes; the phone never stores the pairing code.
 */
internal class WifiTrustedHostStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getToken(hostFingerprint: String): String? {
        val preferenceKey = preferenceKey(hostFingerprint) ?: return null
        val encoded = preferences.getString(preferenceKey, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES) { "Invalid trusted token payload." }
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
                .takeIf(::isValidToken)
                ?: throw IllegalStateException("Invalid trusted token.")
        } catch (_: Exception) {
            // A lost/invalidated Keystore key must fail closed; never fall back to plaintext.
            preferences.edit().remove(preferenceKey).commit()
            null
        }
    }

    @Synchronized
    fun storeToken(hostFingerprint: String, token: String): Boolean {
        val preferenceKey = preferenceKey(hostFingerprint) ?: return false
        if (!isValidToken(token)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
            val payload = cipher.iv + ciphertext
            preferences.edit()
                .putString(preferenceKey, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun forget(hostFingerprint: String): Boolean {
        val preferenceKey = preferenceKey(hostFingerprint) ?: return false
        return preferences.edit().remove(preferenceKey).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun preferenceKey(fingerprint: String): String? {
        if (fingerprint.any { !(it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == ' ' || it == '-') }) {
            return null
        }
        val normalized = fingerprint.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.lowercase()
        return normalized.takeIf { it.length == 64 }?.let { HOST_PREFIX + it }
    }

    private fun isValidToken(token: String): Boolean =
        token.matches(Regex("[A-Za-z0-9_-]{43}"))

    companion object {
        private const val PREFERENCES_NAME = "wifi_trusted_hosts"
        private const val HOST_PREFIX = "host_"
        private const val KEY_ALIAS = "WastiAirPadWifiTrustKeyV1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
