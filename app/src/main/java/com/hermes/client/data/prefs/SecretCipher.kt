package com.hermes.client.data.prefs

import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secret settings (API key, push token) before they reach DataStore.
 *
 * Android's DataStore is plain protobuf on disk; anything written there is readable by anything
 * running with the app uid (and by backup/forensics tooling on debuggable builds). The master
 * API key is the credential to an agent that executes terminal commands on the server, so it is
 * worth the extra hop through the hardware-backed keystore.
 */
interface SecretCipher {
    /** Returns an encrypted, self-describing string ("v1:<base64>"), or the plain value unchanged
     *  when no keystore is available (rare broken devices degrade to the previous behavior). */
    fun encrypt(plain: String): String

    /**
     * Reverses [encrypt]. Values without the "v1:" prefix are legacy plaintext and are returned
     * unchanged so the caller can migrate them. Undecryptable "v1:" values return "" — a
     * corrupted or keystore-rotated entry is unusable and the user must re-enter the secret.
     */
    fun decrypt(stored: String): String

    companion object {
        const val V1_PREFIX = "v1:"
    }
}

/** AndroidKeyStore-backed AES-256/GCM implementation; falls back to identity on keystore failure. */
class KeystoreSecretCipher : SecretCipher {

    private val key: SecretKey? = runCatching { loadOrCreateKey() }.getOrNull()

    override fun encrypt(plain: String): String {
        val k = key ?: return plain
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            SecretCipher.V1_PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrElse { plain }
    }

    override fun decrypt(stored: String): String {
        if (!stored.startsWith(SecretCipher.V1_PREFIX)) return stored // legacy plaintext
        val k = key ?: return "" // encrypted entry but no keystore: unrecoverable
        return runCatching {
            val blob = Base64.decode(
                stored.removePrefix(SecretCipher.V1_PREFIX),
                Base64.NO_WRAP,
            )
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        // Belt-and-braces: an alias could exist as a non-secret-key entry from an older format.
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hermes_settings_v1"
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** Deterministic cipher for unit tests and previews: identity both ways. */
class InsecureSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(stored: String): String = stored
}
