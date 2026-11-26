package app.passman.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Argon2id cost settings (SEC-2). Stored in each vault file so they can be tuned later. */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    init {
        require(memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "memory out of range" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism out of range" }
        require(memoryKiB >= 8 * parallelism) { "memory too small for parallelism" }
    }

    companion object {
        /** PRD starting point: 64 MiB, 3 iterations, parallelism 4. */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 4)

        // Bounds applied to files we read, so a crafted backup cannot demand absurd resources.
        // 256 MiB is 4x the default and still fits a phone's app heap; [VaultCrypto.open] also
        // turns an OutOfMemoryError into CorruptVaultException in case a phone allows less.
        const val MIN_MEMORY_KIB = 8
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MAX_ITERATIONS = 20
        const val MAX_PARALLELISM = 16
    }
}

class WrongPasswordException : Exception("Wrong master password")
class CorruptVaultException(message: String) : Exception(message)

/**
 * The unlocked vault key plus the file header that wraps it. [destroy] zeroes the key (SEC-6).
 */
class VaultKeys internal constructor(
    private val vaultKey: ByteArray,
    /** Header bytes from the magic through the wrapped key; reused on every save. */
    internal val keyHeader: ByteArray,
    val kdfParams: KdfParams,
) {
    private var destroyed = false

    /** Runs [block] with the key; [destroy] waits for it, so a lock never zeroes a key mid-use. */
    @Synchronized
    internal fun <T> useKey(block: (ByteArray) -> T): T {
        check(!destroyed) { "Vault is locked" }
        return block(vaultKey)
    }

    val isDestroyed: Boolean @Synchronized get() = destroyed

    @Synchronized
    fun destroy() {
        destroyed = true
        vaultKey.fill(0)
    }
}

class OpenedVault(val keys: VaultKeys, val entries: List<Entry>)

/**
 * Vault file format, version 1 (all integers big-endian):
 *
 * ```
 * "PMV1"                      magic
 * u8   version = 1
 * i32  Argon2id memory (KiB), i32 iterations, i32 parallelism
 * u8   salt length (16), salt
 * u8   wrap nonce length (12), wrap nonce
 * u16  wrapped key length (48), AES-256-GCM(KEK, vault key, AAD = bytes from magic to end of salt)
 * u8   data nonce length (12), data nonce
 * ...  AES-256-GCM(vault key, EntryCodec bytes, AAD = every byte before the ciphertext)
 * ```
 *
 * KEK = Argon2id(master password, salt). The vault key is random (SEC-3). Every encryption uses a
 * fresh random 96-bit nonce (SEC-1). Any change to any byte makes decryption fail (SEC-12).
 * A backup is a copy of this file.
 */
object VaultCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128
    private const val WRAPPED_LEN = KEY_LEN + TAG_BITS / 8

    private val random = SecureRandom()

    /** Creates a new vault for [password] holding [entries]. Returns the file bytes and keys. */
    fun create(
        password: CharArray,
        entries: List<Entry>,
        params: KdfParams = KdfParams.DEFAULT,
    ): Pair<ByteArray, VaultKeys> {
        val vaultKey = ByteArray(KEY_LEN).also(random::nextBytes)
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val kek = deriveKek(password, salt, params)
        try {
            val prefix = ByteArrayOutputStream().also { bos ->
                DataOutputStream(bos).use { out ->
                    out.write(MAGIC)
                    out.writeByte(VERSION)
                    out.writeInt(params.memoryKiB)
                    out.writeInt(params.iterations)
                    out.writeInt(params.parallelism)
                    out.writeByte(SALT_LEN)
                    out.write(salt)
                }
            }.toByteArray()
            val wrapNonce = ByteArray(NONCE_LEN).also(random::nextBytes)
            val wrapped = gcm(Cipher.ENCRYPT_MODE, kek, wrapNonce, prefix, vaultKey)
            val keyHeader = ByteArrayOutputStream().also { bos ->
                DataOutputStream(bos).use { out ->
                    out.write(prefix)
                    out.writeByte(NONCE_LEN)
                    out.write(wrapNonce)
                    out.writeShort(wrapped.size)
                    out.write(wrapped)
                }
            }.toByteArray()
            val keys = VaultKeys(vaultKey, keyHeader, params)
            return seal(keys, entries) to keys
        } finally {
            kek.fill(0)
        }
    }

    /** Encrypts [entries] under existing [keys] with a fresh nonce; returns the full file bytes. */
    fun seal(keys: VaultKeys, entries: List<Entry>): ByteArray {
        val dataNonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val aadOut = ByteArrayOutputStream()
        DataOutputStream(aadOut).use { out ->
            out.write(keys.keyHeader)
            out.writeByte(NONCE_LEN)
            out.write(dataNonce)
        }
        val aad = aadOut.toByteArray()
        val plain = EntryCodec.encode(entries)
        try {
            val cipherText = keys.useKey { gcm(Cipher.ENCRYPT_MODE, it, dataNonce, aad, plain) }
            return aad + cipherText
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Opens [file] with [password]. Slow (runs Argon2id).
     * @throws WrongPasswordException if the key cannot be unwrapped (wrong password or a damaged header)
     * @throws CorruptVaultException if the file is malformed or its contents were changed
     */
    fun open(file: ByteArray, password: CharArray): OpenedVault {
        val header = parseHeader(file)
        val kek = try {
            deriveKek(password, header.salt, header.params)
        } catch (e: OutOfMemoryError) {
            throw CorruptVaultException("This vault's key settings need more memory than the phone allows")
        }
        val vaultKey = try {
            gcm(Cipher.DECRYPT_MODE, kek, header.wrapNonce, file.copyOfRange(0, header.prefixEnd), header.wrapped)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        } finally {
            kek.fill(0)
        }
        if (vaultKey.size != KEY_LEN) {
            vaultKey.fill(0)
            throw CorruptVaultException("Bad key length")
        }
        val keys = VaultKeys(vaultKey, file.copyOfRange(0, header.keyHeaderEnd), header.params)
        try {
            val aad = file.copyOfRange(0, header.dataStart)
            val cipherText = file.copyOfRange(header.dataStart, file.size)
            val plain = try {
                gcm(Cipher.DECRYPT_MODE, vaultKey, header.dataNonce, aad, cipherText)
            } catch (e: AEADBadTagException) {
                throw CorruptVaultException("Vault contents failed verification")
            }
            try {
                return OpenedVault(keys, EntryCodec.decode(plain))
            } catch (e: Exception) {
                throw CorruptVaultException("Vault contents could not be read")
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            keys.destroy()
            throw e
        }
    }

    /** Reads only the KDF parameters (for display/tuning); throws [CorruptVaultException]. */
    fun readParams(file: ByteArray): KdfParams = parseHeader(file).params

    private class Header(
        val params: KdfParams,
        val salt: ByteArray,
        val prefixEnd: Int,
        val wrapNonce: ByteArray,
        val wrapped: ByteArray,
        val keyHeaderEnd: Int,
        val dataNonce: ByteArray,
        val dataStart: Int,
    )

    private fun parseHeader(file: ByteArray): Header {
        try {
            val buf = ByteBuffer.wrap(file)
            val magic = ByteArray(4).also(buf::get)
            if (!magic.contentEquals(MAGIC)) throw CorruptVaultException("Not a PassMan vault")
            val version = buf.get().toInt() and 0xff
            if (version != VERSION) throw CorruptVaultException("Unsupported vault version $version")
            val params = try {
                KdfParams(buf.int, buf.int, buf.int)
            } catch (e: IllegalArgumentException) {
                throw CorruptVaultException("Unsupported key settings")
            }
            val saltLen = buf.get().toInt() and 0xff
            if (saltLen != SALT_LEN) throw CorruptVaultException("Bad salt")
            val salt = ByteArray(saltLen).also(buf::get)
            val prefixEnd = buf.position()
            if ((buf.get().toInt() and 0xff) != NONCE_LEN) throw CorruptVaultException("Bad nonce")
            val wrapNonce = ByteArray(NONCE_LEN).also(buf::get)
            val wrappedLen = buf.short.toInt() and 0xffff
            if (wrappedLen != WRAPPED_LEN) throw CorruptVaultException("Bad key block")
            val wrapped = ByteArray(wrappedLen).also(buf::get)
            val keyHeaderEnd = buf.position()
            if ((buf.get().toInt() and 0xff) != NONCE_LEN) throw CorruptVaultException("Bad nonce")
            val dataNonce = ByteArray(NONCE_LEN).also(buf::get)
            val dataStart = buf.position()
            if (file.size - dataStart < TAG_BITS / 8) throw CorruptVaultException("Truncated vault")
            return Header(params, salt, prefixEnd, wrapNonce, wrapped, keyHeaderEnd, dataNonce, dataStart)
        } catch (e: java.nio.BufferUnderflowException) {
            throw CorruptVaultException("Truncated vault")
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(input)
        } catch (e: AEADBadTagException) {
            throw e
        } catch (e: GeneralSecurityException) {
            if (mode == Cipher.DECRYPT_MODE) throw AEADBadTagException(e.message) else throw e
        }
    }

    /** Argon2id(password as UTF-8, salt) -> 32-byte key-encryption key (SEC-2). */
    fun deriveKek(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val pwBytes = ByteArray(encoded.remaining()).also(encoded::get)
        if (encoded.hasArray()) encoded.array().fill(0)
        try {
            val argon = Argon2BytesGenerator()
            argon.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(params.memoryKiB)
                    .withIterations(params.iterations)
                    .withParallelism(params.parallelism)
                    .build(),
            )
            val out = ByteArray(KEY_LEN)
            argon.generateBytes(pwBytes, out)
            return out
        } finally {
            pwBytes.fill(0)
        }
    }
}
