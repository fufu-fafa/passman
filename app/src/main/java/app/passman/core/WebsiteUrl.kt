package app.passman.core

import java.net.URI

/** Website rules: https:// is assumed when no scheme is entered; only http and https links open. */
object WebsiteUrl {
    private val schemeWithSlashes = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    // A scheme such as "javascript:" or "mailto:" — but not "example.com:8080" (host and port).
    private val opaqueScheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?!\\d)")

    /** The URL to hand to the browser, or null if it cannot or must not be opened. */
    fun toOpenable(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() }) return null
        val candidate = when {
            schemeWithSlashes.containsMatchIn(s) -> s
            opaqueScheme.containsMatchIn(s) -> return null
            else -> "https://$s"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return candidate
    }
}
