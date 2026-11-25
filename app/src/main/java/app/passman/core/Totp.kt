package app.passman.core

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A parsed 2FA setup key (RFC 6238 TOTP). */
class TotpConfig(
    internal val secret: ByteArray,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val algorithm: Algorithm = Algorithm.SHA1,
) {
    enum class Algorithm(val macName: String) { SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512") }
}

/**
 * The Code field holds a 2FA setup key: either a Base32 secret ("JBSW Y3DP EHPK 3PXP") or an
 * otpauth://totp/... URI as found in QR codes. [parse] returns null when it is neither.
 */
object Totp {
    fun parse(raw: String): TotpConfig? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        return if (input.startsWith("otpauth://", ignoreCase = true)) parseUri(input) else {
            Base32.decode(input)?.takeIf { it.size >= 10 }?.let { TotpConfig(it) }
        }
    }

    fun isValid(raw: String): Boolean = parse(raw) != null

    private fun parseUri(input: String): TotpConfig? {
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        if (!uri.host.equals("totp", ignoreCase = true)) return null
        val params = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            k.lowercase() to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        val secret = params["secret"]?.let(Base32::decode)?.takeIf { it.size >= 10 } ?: return null
        val digits = params["digits"]?.toIntOrNull() ?: 6
        val period = params["period"]?.toIntOrNull() ?: 30
        val algorithm = when (params["algorithm"]?.uppercase()) {
            null, "SHA1" -> TotpConfig.Algorithm.SHA1
            "SHA256" -> TotpConfig.Algorithm.SHA256
            "SHA512" -> TotpConfig.Algorithm.SHA512
            else -> return null
        }
        if (digits !in 6..8 || period !in 1..300) return null
        return TotpConfig(secret, digits, period, algorithm)
    }

    /** The code for [timeMillis]; zero-padded to the configured digit count. */
    fun code(config: TotpConfig, timeMillis: Long): String {
        val counter = Math.floorDiv(timeMillis / 1000, config.periodSeconds.toLong())
        val mac = Mac.getInstance(config.algorithm.macName)
        mac.init(SecretKeySpec(config.secret, config.algorithm.macName))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(config.digits) { mod *= 10 }
        return (binary % mod).toString().padStart(config.digits, '0')
    }

    /** Seconds until the current code changes (1..period). */
    fun secondsRemaining(config: TotpConfig, timeMillis: Long): Int {
        val elapsed = Math.floorMod(timeMillis / 1000, config.periodSeconds.toLong()).toInt()
        return config.periodSeconds - elapsed
    }
}

/** RFC 4648 Base32 decoding, tolerant of spaces, hyphens, lowercase and padding. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray? {
        val clean = input.filterNot { it == ' ' || it == '-' || it == '=' }.uppercase()
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
