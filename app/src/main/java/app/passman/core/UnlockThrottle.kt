package app.passman.core

/**
 * SEC-8: increasing delays after repeated failed unlock attempts. The vault is never erased.
 * The first three failures are free; after that the wait grows to a five-minute cap.
 */
object UnlockThrottle {
    private val delaysSeconds = longArrayOf(0, 0, 0, 5, 15, 30, 60, 120, 300)

    fun delayMillisAfter(failures: Int): Long =
        if (failures <= 0) 0 else delaysSeconds[minOf(failures, delaysSeconds.size - 1)] * 1000

    /** Milliseconds still to wait at [now], given the failure count and time of the last failure. */
    fun remainingMillis(failures: Int, lastFailureAt: Long, now: Long): Long {
        val delay = delayMillisAfter(failures)
        if (delay == 0L) return 0
        val elapsed = now - lastFailureAt
        // A clock moved backwards counts as no time passed, capped so a bad clock can't lock forever.
        if (elapsed < 0) return delay
        return (delay - elapsed).coerceAtLeast(0)
    }
}
