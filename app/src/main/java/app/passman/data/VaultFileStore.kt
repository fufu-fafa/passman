package app.passman.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Stores the encrypted vault file. Writes go to a temporary file that is synced to disk and then
 * renamed over the vault, so a crash or power loss leaves either the old or the new vault intact.
 */
class VaultFileStore(private val dir: File) {
    val file = File(dir, "vault.pmv")
    private val temp = File(dir, "vault.pmv.tmp")

    init {
        // A leftover temp file is a save that never completed; the real vault is still intact.
        if (temp.exists()) temp.delete()
    }

    fun exists(): Boolean = file.isFile

    fun read(): ByteArray = file.readBytes()

    @Synchronized
    fun write(bytes: ByteArray) {
        dir.mkdirs()
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Could not replace vault file")
        }
    }
}
