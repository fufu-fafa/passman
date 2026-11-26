package app.passman.core

/**
 * SEC-8: increasing delays after repeated failed master-password attempts (unlock, change
 * password, open backup). The vault is never erased. The first three failures are free; after
 * that the wait grows to a five-minute cap.
 *
 * Times are the monotonic clock since boot (`SystemClock.elapsedRealtime()`), so changing the
 * phone's date and time can't skip a wait. That clock restarts at boot, so a failure recorded in
 * an earlier boot restarts its wait from the first check after the reboot ([failureTimeFor]).
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
        // A clock that went backwards counts as no time passed.
        if (elapsed < 0) return delay
        return (delay - elapsed).coerceAtLeast(0)
    }

    /**
     * The time to measure the wait from: [lastFailureAt] when it was recorded in this boot, or
     * [now] when the phone rebooted since (or the stored value is missing or impossible), which
     * restarts the wait. A reboot can lengthen a wait, never shorten it.
     */
    fun failureTimeFor(lastFailureAt: Long, lastFailureBoot: Int, now: Long, boot: Int): Long =
        if (lastFailureBoot != boot || lastFailureAt < 0 || lastFailureAt > now) now else lastFailureAt
}
