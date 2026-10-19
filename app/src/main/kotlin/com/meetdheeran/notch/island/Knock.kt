package com.meetdheeran.notch.island

/**
 * [needed] taps, each within [windowMs] of the first: a knock — the secret door to the hidden apps. Pure, so it's
 * unit-tested. (Ported from Morse, where Prism's island used to send it each tap.)
 */
class TapCounter(private val needed: Int = 4, private val windowMs: Long = 1_500) {
    private val taps = ArrayDeque<Long>()

    /** A tap at [now]; true when it completes a knock (the count starts over). */
    @Synchronized
    fun tap(now: Long): Boolean {
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > windowMs) taps.removeFirst()
        if (taps.size < needed) return false
        taps.clear()
        return true
    }

    @Synchronized
    fun reset() = taps.clear()

    /** Taps so far towards the next knock. */
    val pending: Int
        @Synchronized get() = taps.size
}
