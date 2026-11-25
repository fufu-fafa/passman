package app.passman.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Binary encoding of the entry list (the plaintext that gets encrypted). Strings are stored as
 * length-prefixed UTF-8 so Unicode, line breaks, spaces and leading zeros survive exactly.
 */
object EntryCodec {
    private const val FORMAT = 1
    private const val MAX_STRING_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 1_000_000

    /** Returns the encoded bytes. The caller should wipe them once encrypted. */
    fun encode(entries: List<Entry>): ByteArray {
        val buffer = WipeableByteArrayOutputStream(4096)
        DataOutputStream(buffer).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(entries.size)
            for (e in entries) {
                out.writeStr(e.id)
                out.writeStr(e.title)
                out.writeStr(e.userName)
                out.writeStr(e.password)
                out.writeStr(e.code)
                out.writeStr(e.website)
                out.writeStr(e.notes)
                out.writeLong(e.modifiedAt)
            }
        }
        val result = buffer.toByteArray()
        buffer.wipe()
        return result
    }

    fun decode(bytes: ByteArray): List<Entry> {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val format = input.readInt()
            if (format != FORMAT) throw IOException("Unsupported entry format $format")
            val count = input.readInt()
            if (count < 0 || count > MAX_ENTRIES) throw IOException("Bad entry count")
            val list = ArrayList<Entry>(minOf(count, 10_000))
            repeat(count) {
                list += Entry(
                    id = input.readStr(),
                    title = input.readStr(),
                    userName = input.readStr(),
                    password = input.readStr(),
                    code = input.readStr(),
                    website = input.readStr(),
                    notes = input.readStr(),
                    modifiedAt = input.readLong(),
                )
            }
            if (input.available() != 0) throw IOException("Trailing data")
            return list
        }
    }

    private fun DataOutputStream.writeStr(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
        b.fill(0)
    }

    private fun DataInputStream.readStr(): String {
        val len = readInt()
        if (len < 0 || len > MAX_STRING_BYTES) throw IOException("Bad string length")
        val b = ByteArray(len)
        readFully(b)
        val s = String(b, Charsets.UTF_8)
        b.fill(0)
        return s
    }
}

/** A ByteArrayOutputStream whose internal buffer can be zeroed after use. */
internal class WipeableByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
    fun wipe() {
        buf.fill(0)
        count = 0
    }
}
