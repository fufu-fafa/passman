package app.passman.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.passman.core.CorruptVaultException
import app.passman.core.Entry
import app.passman.core.KdfParams
import app.passman.core.OpenedVault
import app.passman.core.SortOrder
import app.passman.core.UnlockThrottle
import app.passman.core.VaultCrypto
import app.passman.core.VaultKeys
import app.passman.core.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import java.io.OutputStream

sealed interface VaultState {
    /** First launch: no vault file yet (Setup screen). */
    data object NoVault : VaultState
    data object Locked : VaultState
    data class Unlocked(val entries: List<Entry>) : VaultState
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassword : UnlockResult
    data class Damaged(val reason: String) : UnlockResult
    data class Throttled(val waitMillis: Long) : UnlockResult
}

/** Too many wrong master passwords; try again after [waitMillis] (SEC-8). */
class ThrottledException(val waitMillis: Long) : Exception("Too many attempts")

sealed interface BiometricUnlockResult {
    data object Success : BiometricUnlockResult

    /**
     * Fingerprint unlock was turned off: the fingerprints changed (the Keystore key is gone) or
     * the stored key no longer opens the vault. The master password still works.
     */
    data object TurnedOff : BiometricUnlockResult
}

/** A backup that decrypted successfully and is waiting for the user's confirmation to restore. */
class PendingRestore internal constructor(internal val file: ByteArray, internal val opened: OpenedVault) {
    val entryCount: Int get() = opened.entries.size
    fun discard() = opened.keys.destroy()
}

/**
 * Owns the vault: the encrypted file on disk and, while unlocked, the vault key and decrypted
 * entries in memory. [lock] drops both (SEC-6).
 */
class VaultRepository(context: Context, private val kdfParams: KdfParams = KdfParams.DEFAULT) {
    private val resolver = context.contentResolver
    private val store = VaultFileStore(context.filesDir)
    private val biometric = BiometricKeyStore(context)
    private val prefs = context.getSharedPreferences("passman", Context.MODE_PRIVATE)
    private val writeMutex = Mutex()

    @Volatile private var keys: VaultKeys? = null

    /** Called after every [lock], on the locking thread (PassManApp clears the clipboard). */
    @Volatile var onLock: (() -> Unit)? = null

    private val _state = MutableStateFlow<VaultState>(if (store.exists()) VaultState.Locked else VaultState.NoVault)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val isUnlocked: Boolean get() = _state.value is VaultState.Unlocked

    private val _biometricEnabled = MutableStateFlow(biometric.hasBlob())

    /** Whether fingerprint unlock is turned on (a wrapped key is stored). */
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    // ---- Preferences that hold no entry data ----

    var sortOrder: SortOrder
        get() = runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null) ?: "") }.getOrDefault(SortOrder.TITLE)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    /** Milliseconds the user must wait before the next master-password attempt (SEC-8). */
    @Synchronized
    fun unlockWaitMillis(): Long {
        val fails = prefs.getInt(KEY_FAILS, 0)
        if (fails <= 0) return 0
        val now = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val stored = prefs.getLong(KEY_LAST_FAIL_ELAPSED, -1)
        val from = UnlockThrottle.failureTimeFor(stored, prefs.getInt(KEY_LAST_FAIL_BOOT, Int.MIN_VALUE), now, boot)
        if (from != stored) {
            // Rebooted since the last failure: restart the wait from now.
            prefs.edit().putLong(KEY_LAST_FAIL_ELAPSED, from).putInt(KEY_LAST_FAIL_BOOT, boot).commit()
        }
        return UnlockThrottle.remainingMillis(fails, from, now)
    }

    @Synchronized
    private fun recordFailure() {
        prefs.edit()
            .putInt(KEY_FAILS, prefs.getInt(KEY_FAILS, 0) + 1)
            .putLong(KEY_LAST_FAIL_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_LAST_FAIL_BOOT, bootCount())
            .commit()
    }

    @Synchronized
    private fun resetFailures() {
        prefs.edit().putInt(KEY_FAILS, 0).remove("unlock_last_failure").remove(KEY_LAST_FAIL_ELAPSED).remove(KEY_LAST_FAIL_BOOT).apply()
    }

    private fun bootCount(): Int = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)

    // ---- Lifecycle ----

    // Every function below that takes a password CharArray zeroes it before returning.

    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        try {
            writeMutex.withLock {
                check(!store.exists()) { "Vault already exists" }
                disableBiometric()
                val (bytes, newKeys) = VaultCrypto.create(password, emptyList(), kdfParams)
                withContext(Dispatchers.IO) { store.write(bytes) }
                keys = newKeys
                _state.value = VaultState.Unlocked(emptyList())
            }
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun unlock(password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        try {
            val wait = unlockWaitMillis()
            if (wait > 0) return@withContext UnlockResult.Throttled(wait)
            val bytes = withContext(Dispatchers.IO) { store.read() }
            val started = SystemClock.elapsedRealtime()
            val opened = VaultCrypto.open(bytes, password)
            // Timing only (no entry data); release builds strip Log calls.
            android.util.Log.d("PassMan", "unlock took ${SystemClock.elapsedRealtime() - started} ms")
            adopt(opened)
            UnlockResult.Success
        } catch (e: WrongPasswordException) {
            recordFailure()
            UnlockResult.WrongPassword
        } catch (e: CorruptVaultException) {
            UnlockResult.Damaged(e.message ?: "Vault is damaged")
        } finally {
            password.fill('\u0000')
        }
    }

    /** Makes [opened] the unlocked vault. The one place a successful unlock resets SEC-8. */
    private fun adopt(opened: OpenedVault) {
        resetFailures()
        keys?.destroy()
        keys = opened.keys
        _state.value = VaultState.Unlocked(opened.entries)
    }

    // ---- Fingerprint unlock (a second, Keystore-protected wrap of the same vault key) ----

    /** A `BiometricManager` status code; `BIOMETRIC_SUCCESS` when a strong fingerprint is usable. */
    fun biometricAvailability(): Int = biometric.availability()

    /**
     * Step 1 of turning fingerprint unlock on: makes a new Keystore key and returns the cipher for
     * the fingerprint prompt. Pass the authenticated cipher to [enableBiometric], or call
     * [disableBiometric] if the prompt is cancelled.
     */
    suspend fun prepareBiometricEnable(): Cipher = withContext(Dispatchers.IO) {
        check(!_biometricEnabled.value) { "Fingerprint unlock is already on" }
        biometric.createEncryptCipher()
    }

    /** Step 2: wraps the vault key with the fingerprint-authorized [cipher]. */
    suspend fun enableBiometric(cipher: Cipher) = withContext(Dispatchers.IO) {
        val k = keys ?: throw IllegalStateException("Vault is locked")
        val raw = k.exportKeyCopy()
        try {
            biometric.store(cipher, raw)
            _biometricEnabled.value = true
        } catch (e: Exception) {
            disableBiometric()
            throw e
        } finally {
            raw.fill(0)
        }
    }

    /**
     * The cipher for the unlock prompt, or null when fingerprint unlock was just turned off
     * because the fingerprints changed.
     */
    suspend fun prepareBiometricUnlock(): Cipher? = withContext(Dispatchers.IO) {
        try {
            biometric.createDecryptCipher()
        } catch (e: BiometricInvalidatedException) {
            _biometricEnabled.value = false
            null
        }
    }

    /**
     * Unlocks with the fingerprint-authorized [cipher]. No Argon2id, so it's fast. Fingerprint
     * failures don't count toward SEC-8 (Android has its own lockout). A stored key that no
     * longer opens the vault turns fingerprint unlock off; it is never reported as a damaged
     * vault, and the vault file is never written here.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): BiometricUnlockResult = withContext(Dispatchers.Default) {
        val raw = try {
            biometric.unwrap(cipher)
        } catch (e: BiometricInvalidatedException) {
            _biometricEnabled.value = false
            return@withContext BiometricUnlockResult.TurnedOff
        }
        try {
            val bytes = withContext(Dispatchers.IO) { store.read() }
            val opened = try {
                VaultCrypto.openWithKey(bytes, raw)
            } catch (e: CorruptVaultException) {
                disableBiometric()
                return@withContext BiometricUnlockResult.TurnedOff
            }
            adopt(opened)
            BiometricUnlockResult.Success
        } finally {
            raw.fill(0)
        }
    }

    /** Turns fingerprint unlock off: deletes the wrapped key and the Keystore key. */
    fun disableBiometric() {
        biometric.disable()
        _biometricEnabled.value = false
    }

    /** SEC-6: forget the key and every decrypted entry. Safe to call at any time, from any thread. */
    fun lock() {
        val k = keys
        keys = null
        k?.destroy()
        if (_state.value is VaultState.Unlocked) _state.value = VaultState.Locked
        onLock?.invoke()
    }

    // ---- Entries ----

    /**
     * Adds or replaces [entry]. Modified date is set here, and only when content changed.
     * Returns the stored entry.
     */
    suspend fun save(entry: Entry): Entry = mutate { current ->
        val existing = current.firstOrNull { it.id == entry.id }
        if (existing != null && existing.sameContentAs(entry)) return@mutate null
        val stored = entry.copy(modifiedAt = System.currentTimeMillis())
        val next = if (existing == null) current + stored else current.map { if (it.id == entry.id) stored else it }
        next to stored
    } ?: requireNotNull(entries().firstOrNull { it.id == entry.id })

    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } to Unit }
    }

    private fun entries(): List<Entry> = (_state.value as? VaultState.Unlocked)?.entries ?: emptyList()

    /** Applies [change] to the entry list, writes the re-encrypted vault, then publishes it. */
    private suspend fun <T> mutate(change: (List<Entry>) -> Pair<List<Entry>, T>?): T? =
        withContext(Dispatchers.Default) {
            writeMutex.withLock {
                val k = keys ?: throw IllegalStateException("Vault is locked")
                val current = (_state.value as? VaultState.Unlocked)?.entries ?: throw IllegalStateException("Vault is locked")
                val (next, result) = change(current) ?: return@withLock null
                val bytes = VaultCrypto.seal(k, next)
                withContext(Dispatchers.IO) { store.write(bytes) }
                // Only publish if the vault wasn't locked while we were saving.
                if (keys === k) _state.value = VaultState.Unlocked(next)
                result
            }
        }

    // ---- Master password ----

    /**
     * FR-13. Returns [UnlockResult.WrongPassword] when [current] is wrong; wrong guesses count
     * toward the same SEC-8 delay as the unlock screen. A new vault key and salt replace the old,
     * which turns fingerprint unlock off.
     * [UnlockResult.Damaged] is never returned: a damaged file throws [CorruptVaultException].
     */
    suspend fun changePassword(current: CharArray, new: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        try {
            writeMutex.withLock {
                val wait = unlockWaitMillis()
                if (wait > 0) return@withLock UnlockResult.Throttled(wait)
                val k = keys ?: throw IllegalStateException("Vault is locked")
                val bytes = withContext(Dispatchers.IO) { store.read() }
                val check = try {
                    VaultCrypto.open(bytes, current)
                } catch (e: WrongPasswordException) {
                    recordFailure()
                    return@withLock UnlockResult.WrongPassword
                }
                check.keys.destroy()
                resetFailures()
                val entries = (_state.value as? VaultState.Unlocked)?.entries ?: throw IllegalStateException("Vault is locked")
                val (newBytes, newKeys) = VaultCrypto.create(new, entries, kdfParams)
                withContext(Dispatchers.IO) { store.write(newBytes) }
                // The new vault key isn't wrapped for the fingerprint; the user turns it on again.
                disableBiometric()
                if (keys === k) {
                    keys = newKeys
                    k.destroy()
                } else {
                    newKeys.destroy()
                }
                UnlockResult.Success
            }
        } finally {
            current.fill('\u0000')
            new.fill('\u0000')
        }
    }

    // ---- Backup and restore (FR-14) ----

    /** Writes the encrypted vault file, exactly as stored, to [out]. */
    suspend fun exportBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val bytes = store.read()
            out.write(bytes)
            out.flush()
        }
    }

    /**
     * Decrypts a backup with the master password that protected it, without touching the current
     * vault. Throws [ThrottledException], [WrongPasswordException] or [CorruptVaultException].
     * Wrong guesses count toward the SEC-8 delay. A success doesn't reset it: opening a backup
     * proves knowledge of that backup's password, which may not be this vault's.
     */
    suspend fun openBackup(bytes: ByteArray, password: CharArray): PendingRestore = withContext(Dispatchers.Default) {
        try {
            val wait = unlockWaitMillis()
            if (wait > 0) throw ThrottledException(wait)
            PendingRestore(bytes.copyOf(), VaultCrypto.open(bytes, password))
        } catch (e: WrongPasswordException) {
            recordFailure()
            throw e
        } finally {
            password.fill('\u0000')
        }
    }

    /** Replaces the current vault (or creates one on a fresh install) with a verified backup. */
    suspend fun restore(pending: PendingRestore) = withContext(Dispatchers.Default) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) { store.write(pending.file) }
            // The backup has its own vault key; the fingerprint wrap is for the old one.
            disableBiometric()
            val old = keys
            keys = pending.opened.keys
            old?.destroy()
            resetFailures()
            _state.value = VaultState.Unlocked(pending.opened.entries)
        }
    }

    companion object {
        private const val KEY_SORT = "sort_order"
        private const val KEY_FAILS = "unlock_failures"
        // Monotonic time and boot number of the last failure. (The old wall-clock key,
        // "unlock_last_failure", is ignored; a stale count restarts its wait once.)
        private const val KEY_LAST_FAIL_ELAPSED = "unlock_last_failure_elapsed"
        private const val KEY_LAST_FAIL_BOOT = "unlock_last_failure_boot"
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    }
}
