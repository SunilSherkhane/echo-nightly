package dev.brahmkshatriya.echo.utils.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by the Android Keystore.
 *
 * The key is generated inside the platform keystore and never leaves it, so
 * extracting the app's preferences or database yields ciphertext only. Values
 * are stored as `enc::<base64(iv + ciphertext)>`.
 */
class SecretStore(context: Context) {

    private val key: SecretKey? by lazy {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        }.getOrElse {
            Log.w("SecretStore", "Keystore unavailable, credentials will not be encrypted", it)
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS / 8)
                .build()
        )
        return generator.generateKey()
    }

    /** @return ciphertext marked with [PREFIX], or [plain] if no key is available. */
    fun encrypt(plain: String): String {
        val key = key ?: return plain
        if (plain.isEmpty()) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            Log.w("SecretStore", "Encryption failed, storing plaintext", it)
            plain
        }
    }

    /**
     * @return the decrypted value. Anything not marked with [PREFIX] -- legacy
     * plaintext rows, or a value whose key is no longer available -- is
     * returned unchanged, so existing data keeps working.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        val key = key ?: return stored
        return runCatching {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(combined.size > GCM_IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES)
            )
            String(cipher.doFinal(combined, GCM_IV_BYTES, combined.size - GCM_IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            Log.w("SecretStore", "Decryption failed", it)
            stored
        }
    }

    companion object {
        private const val TAG = "SecretStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "echo_secret_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "enc::"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}

/**
 * Process-wide handle to the [SecretStore].
 *
 * Set once from [android.app.Application.onCreate], which always runs before
 * any credential is read or written.
 */
object SecretStores {

    @Volatile
    private var store: SecretStore? = null

    fun init(context: Context) {
        if (store == null) synchronized(this) {
            if (store == null) store = SecretStore(context.applicationContext)
        }
    }

    val current: SecretStore? get() = store
}
