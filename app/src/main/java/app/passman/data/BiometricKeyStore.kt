package app.passman.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The fingerprint key is gone or was invalidated (fingerprints changed); fingerprint unlock is now off. */
class BiometricInvalidatedException : Exception("Fingerprint key invalidated")

/**
 * Fingerprint unlock: a second wrap of the vault key, under an Android Keystore AES-256-GCM key
 * that only a Class 3 (strong) biometric can use, once per use. Adding a fingerprint, removing
 * them all, or removing the screen lock destroys that key, which turns fingerprint unlock off.
 *
 * Blob file `files/biometric.bin` (written atomically like the vault):
 * ```
 * "PMB1"  magic
 * u8      version = 1
 * u8      iv length (12), iv
 * ...     AES-256-GCM(Keystore key, 32-byte vault key, AAD = every byte before the ciphertext)
 * ```
 * The blob is useless without the Keystore key and a fingerprint. Nothing here is ever logged.
 */
class BiometricKeyStore(private val context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "biometric.bin")
    private val temp = File(dir, "biometric.bin.tmp")

    init {
        if (temp.exists()) temp.delete()
    }

    /** True when a wrapped key is stored (it may still turn out to be invalidated). */
    fun hasBlob(): Boolean = file.isFile

    /** A [BiometricManager] status code; [BiometricManager.BIOMETRIC_SUCCESS] when usable. */
    fun availability(): Int = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)

    /** Replaces any old key with a new one and returns a cipher ready to wrap the vault key. */
    fun createEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /**
     * A cipher ready to unwrap the stored vault key once a fingerprint authorizes it.
     * @throws BiometricInvalidatedException after turning fingerprint unlock off, when the key is
     *   gone or invalidated, or the blob is unreadable
     */
    fun createDecryptCipher(): Cipher {
        val iv = try {
            parse(file.readBytes()).iv
        } catch (e: IOException) {
            disable()
            throw BiometricInvalidatedException()
        }
        try {
            val key = loadKey() ?: throw UnrecoverableKeyException("missing")
            return Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            throw BiometricInvalidatedException()
        } catch (e: UnrecoverableKeyException) {
            disable()
            throw BiometricInvalidatedException()
        }
    }

    /** Wraps [vaultKey] with an authenticated ENCRYPT [cipher] and stores the blob. */
    fun store(cipher: Cipher, vaultKey: ByteArray) {
        val header = header(cipher.iv)
        cipher.updateAAD(header)
        val wrapped = cipher.doFinal(vaultKey)
        dir.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(header)
            out.write(wrapped)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Could not save fingerprint key")
        }
    }

    /**
     * Unwraps the vault key with an authenticated DECRYPT [cipher]. Caller zeroes the result.
     * @throws BiometricInvalidatedException after turning fingerprint unlock off, if the blob
     *   doesn't verify
     */
    fun unwrap(cipher: Cipher): ByteArray {
        try {
            val blob = parse(file.readBytes())
            cipher.updateAAD(blob.header)
            return cipher.doFinal(blob.wrapped)
        } catch (e: IOException) {
            disable()
            throw BiometricInvalidatedException()
        } catch (e: javax.crypto.AEADBadTagException) {
            disable()
            throw BiometricInvalidatedException()
        }
    }

    /** Turns fingerprint unlock off: deletes the blob and the Keystore key. */
    fun disable() {
        file.delete()
        temp.delete()
        deleteKey()
    }

    // ---- Keystore ----

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadKey(): SecretKey? = keyStore().getKey(ALIAS, null) as SecretKey?

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun generateKey(): SecretKey {
        // StrongBox (a separate secure chip) when the phone has one; otherwise the TEE.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return generateKey(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                deleteKey()
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Each use needs its own fingerprint; strong biometrics only, never the phone PIN.
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    // ---- Blob format ----

    private class Blob(val header: ByteArray, val iv: ByteArray, val wrapped: ByteArray)

    private fun header(iv: ByteArray): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeByte(iv.size)
            out.write(iv)
        }
    }.toByteArray()

    private fun parse(bytes: ByteArray): Blob {
        val headerLen = MAGIC.size + 2 + IV_LEN
        if (bytes.size != headerLen + KEY_LEN + TAG_BITS / 8) throw IOException("Bad blob size")
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("Bad magic")
        if (bytes[MAGIC.size].toInt() != VERSION) throw IOException("Bad version")
        if (bytes[MAGIC.size + 1].toInt() != IV_LEN) throw IOException("Bad iv")
        return Blob(
            header = bytes.copyOfRange(0, headerLen),
            iv = bytes.copyOfRange(MAGIC.size + 2, headerLen),
            wrapped = bytes.copyOfRange(headerLen, bytes.size),
        )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "passman_bio"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        const val VERSION = 1
        const val IV_LEN = 12
        const val KEY_LEN = 32
        const val TAG_BITS = 128
    }
}
