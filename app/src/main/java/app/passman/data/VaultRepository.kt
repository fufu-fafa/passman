package app.passman.data

import android.content.Context
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
    private val store = VaultFileStore(context.filesDir)
    private val prefs = context.getSharedPreferences("passman", Context.MODE_PRIVATE)
    private val writeMutex = Mutex()

    @Volatile private var keys: VaultKeys? = null

    private val _state = MutableStateFlow<VaultState>(if (store.exists()) VaultState.Locked else VaultState.NoVault)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    val isUnlocked: Boolean get() = _state.value is VaultState.Unlocked

    // ---- Preferences that hold no entry data ----

    var sortOrder: SortOrder
        get() = runCatching { SortOrder.valueOf(prefs.getString(KEY_SORT, null) ?: "") }.getOrDefault(SortOrder.TITLE)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    /** Milliseconds the user must wait before the next unlock attempt (SEC-8). */
    fun unlockWaitMillis(now: Long = System.currentTimeMillis()): Long =
        UnlockThrottle.remainingMillis(prefs.getInt(KEY_FAILS, 0), prefs.getLong(KEY_LAST_FAIL, 0), now)

    // ---- Lifecycle ----

    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        writeMutex.withLock {
            check(!store.exists()) { "Vault already exists" }
            val (bytes, newKeys) = VaultCrypto.create(password, emptyList(), kdfParams)
            withContext(Dispatchers.IO) { store.write(bytes) }
            keys = newKeys
            _state.value = VaultState.Unlocked(emptyList())
        }
    }

    suspend fun unlock(password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        val wait = unlockWaitMillis()
        if (wait > 0) return@withContext UnlockResult.Throttled(wait)
        val bytes = withContext(Dispatchers.IO) { store.read() }
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val opened = VaultCrypto.open(bytes, password)
            // Timing only (no entry data); release builds strip Log calls.
            android.util.Log.d("PassMan", "unlock took ${android.os.SystemClock.elapsedRealtime() - started} ms")
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LAST_FAIL, 0).apply()
            keys?.destroy()
            keys = opened.keys
            _state.value = VaultState.Unlocked(opened.entries)
            UnlockResult.Success
        } catch (e: WrongPasswordException) {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            prefs.edit().putInt(KEY_FAILS, fails).putLong(KEY_LAST_FAIL, System.currentTimeMillis()).commit()
            UnlockResult.WrongPassword
        } catch (e: CorruptVaultException) {
            UnlockResult.Damaged(e.message ?: "Vault is damaged")
        }
    }

    /** SEC-6: forget the key and every decrypted entry. Safe to call at any time, from any thread. */
    fun lock() {
        val k = keys
        keys = null
        k?.destroy()
        if (_state.value is VaultState.Unlocked) _state.value = VaultState.Locked
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

    /** FR-13. Returns false when [current] is wrong. A new vault key and salt replace the old ones. */
    suspend fun changePassword(current: CharArray, new: CharArray): Boolean = withContext(Dispatchers.Default) {
        writeMutex.withLock {
            val k = keys ?: throw IllegalStateException("Vault is locked")
            val bytes = withContext(Dispatchers.IO) { store.read() }
            val check = try {
                VaultCrypto.open(bytes, current)
            } catch (e: WrongPasswordException) {
                return@withLock false
            }
            check.keys.destroy()
            val entries = (_state.value as? VaultState.Unlocked)?.entries ?: throw IllegalStateException("Vault is locked")
            val (newBytes, newKeys) = VaultCrypto.create(new, entries, kdfParams)
            withContext(Dispatchers.IO) { store.write(newBytes) }
            if (keys === k) {
                keys = newKeys
                k.destroy()
            } else {
                newKeys.destroy()
            }
            true
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
     * vault. Throws [WrongPasswordException] or [CorruptVaultException].
     */
    suspend fun openBackup(bytes: ByteArray, password: CharArray): PendingRestore = withContext(Dispatchers.Default) {
        PendingRestore(bytes.copyOf(), VaultCrypto.open(bytes, password))
    }

    /** Replaces the current vault (or creates one on a fresh install) with a verified backup. */
    suspend fun restore(pending: PendingRestore) = withContext(Dispatchers.Default) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) { store.write(pending.file) }
            val old = keys
            keys = pending.opened.keys
            old?.destroy()
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LAST_FAIL, 0).apply()
            _state.value = VaultState.Unlocked(pending.opened.entries)
        }
    }

    companion object {
        private const val KEY_SORT = "sort_order"
        private const val KEY_FAILS = "unlock_failures"
        private const val KEY_LAST_FAIL = "unlock_last_failure"
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    }
}
