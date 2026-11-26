package app.passman.core

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** One account from a Google Authenticator export; [setupKey] fits the Code field. */
class ExportedAccount(val issuer: String, val name: String, val setupKey: String)

class AuthenticatorExport(
    val accounts: List<ExportedAccount>,
    /** Accounts PassMan can't use: counter-based (HOTP), MD5, or a too-short secret. */
    val skipped: Int,
    /** Authenticator splits big exports over several QR codes: this is code [batchIndex] + 1 of [batchSize]. */
    val batchIndex: Int,
    val batchSize: Int,
) {
    companion object {
        fun isExport(text: String) = text.trim().startsWith("otpauth-migration://", ignoreCase = true)

        /**
         * Reads an `otpauth-migration://offline?data=…` QR code: Base64 of a protobuf
         * MigrationPayload. Null when [text] isn't one or can't be read.
         */
        fun parse(text: String): AuthenticatorExport? {
            if (!isExport(text)) return null
            return runCatching {
                val query = URI(text.trim()).rawQuery ?: return null
                val raw = query.split('&').firstOrNull { it.startsWith("data=") }?.substring(5) ?: return null
                // URLDecoder turns a literal '+' into a space; Base64 needs it back.
                val b64 = URLDecoder.decode(raw, "UTF-8").replace(' ', '+').trimEnd('=')
                val bytes = Base64.getDecoder().decode(b64 + "=".repeat((4 - b64.length % 4) % 4))
                readPayload(bytes)
            }.getOrNull()
        }

        private fun readPayload(bytes: ByteArray): AuthenticatorExport {
            val accounts = ArrayList<ExportedAccount>()
            var skipped = 0
            var batchSize = 1
            var batchIndex = 0
            Proto(bytes).forEachField { field, p ->
                when (field) {
                    1 -> readAccount(p.bytes())?.let(accounts::add) ?: skipped++
                    3 -> batchSize = p.varint().toInt()
                    4 -> batchIndex = p.varint().toInt()
                    else -> p.skip()
                }
            }
            return AuthenticatorExport(accounts, skipped, batchIndex, batchSize.coerceAtLeast(1))
        }

        private fun readAccount(bytes: ByteArray): ExportedAccount? {
            var secret = ByteArray(0)
            var name = ""
            var issuer = ""
            var algorithm = 0
            var digits = 0
            var type = 0
            Proto(bytes).forEachField { field, p ->
                when (field) {
                    1 -> secret = p.bytes()
                    2 -> name = String(p.bytes(), Charsets.UTF_8)
                    3 -> issuer = String(p.bytes(), Charsets.UTF_8)
                    4 -> algorithm = p.varint().toInt()
                    5 -> digits = p.varint().toInt()
                    6 -> type = p.varint().toInt()
                    else -> p.skip()
                }
            }
            if (type == 1) return null // HOTP (counter-based)
            val params = buildString {
                append("otpauth://totp/?secret=").append(Base32.encode(secret))
                when (algorithm) {
                    0, 1 -> {}
                    2 -> append("&algorithm=SHA256")
                    3 -> append("&algorithm=SHA512")
                    else -> return null // MD5
                }
                if (digits == 2) append("&digits=8")
            }
            secret.fill(0)
            val key = Totp.fitSetupKey(params, Limits.CODE) ?: return null
            // Authenticator stores "Issuer:account" in the name when the site put it in the label.
            val shownName = if (issuer.isNotEmpty() && name.startsWith("$issuer:")) name.substring(issuer.length + 1) else name
            return ExportedAccount(issuer.trim(), shownName.trim(), key)
        }
    }
}

/** Just enough of the protobuf wire format for a MigrationPayload. */
private class Proto(private val data: ByteArray) {
    private var pos = 0
    private var wireType = 0

    fun forEachField(block: (field: Int, p: Proto) -> Unit) {
        while (pos < data.size) {
            val tag = varint()
            wireType = (tag and 7).toInt()
            block((tag ushr 3).toInt(), this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(shift < 64 && pos < data.size)
            val b = data[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun bytes(): ByteArray {
        require(wireType == 2)
        val len = varint().toInt()
        require(len >= 0 && pos + len <= data.size)
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> bytes()
            5 -> pos += 4
            else -> throw IllegalArgumentException("wire type $wireType")
        }
        require(pos <= data.size)
    }
}
