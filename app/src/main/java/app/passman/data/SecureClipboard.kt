package app.passman.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies values to the clipboard. Passwords and codes are flagged sensitive (kept out of the
 * clipboard preview, Samsung/Gboard clipboard history and cross-device sync where Android honours
 * the flag) and cleared after 30 seconds if the clipboard still holds them (SEC-7).
 */
class SecureClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingLabel: String? = null
    private var copyCounter = 0L

    fun copyPlain(label: String, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun copySecret(label: String, text: String) {
        // A unique label lets us recognise our own clip 30 s later.
        val uniqueLabel = "$label · PassMan #${++copyCounter}"
        val clip = ClipData.newPlainText(uniqueLabel, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        pendingLabel = uniqueLabel
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::clearIfStillOurs, CLEAR_AFTER_MS)
    }

    private fun clearIfStillOurs() {
        val ours = pendingLabel ?: return
        pendingLabel = null
        // Android only lets the focused app read the clipboard. When we can read it, clear only our
        // own clip; when we can't (PassMan is in the background), clear it regardless — a copy made
        // elsewhere in the last 30 s may be cleared too. That is the documented platform limit.
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (description == null || description.label?.toString() == ours) {
            runCatching { clipboard.clearPrimaryClip() }
        }
    }

    companion object {
        const val CLEAR_AFTER_MS = 30_000L
    }
}
