package app.passman

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.passman.data.SecureClipboard
import app.passman.data.VaultRepository

class PassManApp : Application() {
    lateinit var vault: VaultRepository
        private set
    lateinit var clipboard: SecureClipboard
        private set
    lateinit var autoLock: AutoLock
        private set

    override fun onCreate() {
        super.onCreate()
        vault = VaultRepository(this)
        clipboard = SecureClipboard(this)
        autoLock = AutoLock(vault)
        // SEC-5: lock when the screen turns off (device sleep or screen lock).
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = vault.lock()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )
    }
}

/**
 * SEC-5 timers: lock after 5 minutes without interaction while in the foreground, and after
 * 1 minute in the background. Times use the monotonic clock.
 */
class AutoLock(private val vault: VaultRepository) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var backgroundSince = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastInteraction
            if (idle >= IDLE_MS) vault.lock() else handler.postDelayed(this, IDLE_MS - idle)
        }
    }
    private val backgroundLock = Runnable { vault.lock() }

    fun onInteraction() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        handler.removeCallbacks(backgroundLock)
        if (backgroundSince != 0L && SystemClock.elapsedRealtime() - backgroundSince >= BACKGROUND_MS) vault.lock()
        backgroundSince = 0L
        onInteraction()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, IDLE_MS)
    }

    fun onBackground() {
        backgroundSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(backgroundLock, BACKGROUND_MS)
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
        const val BACKGROUND_MS = 60_000L
    }
}
