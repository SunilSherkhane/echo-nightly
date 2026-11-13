package dev.brahmkshatriya.echo.extensions.repo

import android.content.Context
import android.os.Build
import dev.brahmkshatriya.echo.utils.ShaUtils.getSha256
import java.io.File
import java.security.MessageDigest

/**
 * Verifies extension APKs before they are installed or loaded as code.
 *
 * Extensions are third-party APKs that run inside Echo's own process, so an
 * unverified APK is arbitrary code execution with the app's full permissions.
 * This verifier enforces three things:
 *  1. the APK is signed — unsigned APKs are rejected;
 *  2. an optional SHA-256 pin supplied by the extension repository matches;
 *  3. signer continuity — once an extension id has been trusted, its signing
 *     certificate must not change between updates (trust on first use).
 *
 * The trusted fingerprint is stored per extension id in a private
 * SharedPreferences file, so a compromised update URL cannot silently swap in
 * an APK signed by a different key.
 */
class ExtensionVerifier(private val context: Context) {

    class VerificationException(message: String) : Exception(message)

    /**
     * @param file the extension APK to check
     * @param id the extension id, used as the trust key
     * @param expectedSha256 optional SHA-256 (hex) advertised by the repository
     */
    fun verify(file: File, id: String, expectedSha256: String? = null) {
        if (!file.exists()) {
            throw VerificationException("Extension '$id' file not found: ${file.absolutePath}")
        }

        expectedSha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = runCatching { getSha256(file) }.getOrElse {
                throw VerificationException("Failed to hash extension '$id': ${it.message}")
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw VerificationException(
                    "Extension '$id' failed its integrity check: expected SHA-256 " +
                        "$expected but the downloaded file is $actual."
                )
            }
        }

        val key = id.ifBlank { file.name }
        val fingerprint = signingFingerprint(file, key)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trusted = prefs.getString(key, null)
        if (trusted == null) {
            prefs.edit().putString(key, fingerprint).apply()
        } else if (!trusted.equals(fingerprint, ignoreCase = true)) {
            throw VerificationException(
                "Extension '$key' is signed by a different certificate than the one " +
                    "already trusted for it. Trusted: $trusted; found: $fingerprint. " +
                    "Install aborted — this can indicate a compromised update source."
            )
        }
    }

    private fun signingFingerprint(file: File, id: String): String {
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath, ExtensionParser.PACKAGE_FLAGS
        ) ?: throw VerificationException("Extension '$id' is not a valid APK.")

        val certificates: List<ByteArray> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                    ?: throw VerificationException("Extension '$id' is not signed.")
                signingInfo.apkContentsSigners
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { it.toByteArray() }
                    ?: throw VerificationException("Extension '$id' is not signed.")
            } else {
                @Suppress("DEPRECATION")
                info.signatures
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { it.toByteArray() }
                    ?: throw VerificationException("Extension '$id' is not signed.")
            }

        return certificates.joinToString(":") { certificateFingerprint(it) }
    }

    private fun certificateFingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS_NAME = "echo_extension_signers"
    }
}
