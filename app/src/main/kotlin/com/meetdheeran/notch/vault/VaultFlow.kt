package com.meetdheeran.notch.vault

/**
 * The way into the vault, as Dheer wanted it, pure so it's unit-tested. Four taps on the island → the face, two tries.
 * Both fail → the face stays on the island until a tap anywhere else. Three taps on it → one more try, and if that
 * fails the phone's PIN pad opens by itself. A pass (face or PIN) → the hidden apps.
 */
class VaultFlow(private val windowMs: Long = 1_500) {
    enum class Stage { IDLE, SCANNING, HELD }

    /** What to do next, after an event. */
    sealed interface Next {
        /** Start a face check of [tries] tries; when the last fails, [thenPin] opens the PIN pad, else the face is held. */
        data class Scan(val tries: Int, val thenPin: Boolean) : Next
        /** Keep the failed face on the island, and listen for a tap elsewhere. */
        data object Hold : Next
        /** Take the face off the island. */
        data object Clear : Next
        data object Nothing : Next
    }

    var stage = Stage.IDLE
        private set
    private val faceTaps = ArrayDeque<Long>()

    /** Four taps on the island. */
    fun knock(): Next = if (stage == Stage.IDLE) { stage = Stage.SCANNING; Next.Scan(2, thenPin = false) } else Next.Nothing

    /** Every try failed, and there's no PIN to fall back on this time: the face stays. */
    fun failed(): Next { stage = Stage.HELD; faceTaps.clear(); return Next.Hold }

    /** In (face or PIN), cancelled, or the PIN said no: back to the start. */
    fun done(): Next { stage = Stage.IDLE; faceTaps.clear(); return Next.Clear }

    /** The check's activity went away without a word (Android took it): back to the start. */
    fun lost(): Next = if (stage == Stage.SCANNING) done() else Next.Nothing

    /** A tap on the island at [now]. First: whether the vault took it (so it's not part of a knock and opens nothing). */
    fun islandTap(now: Long): Pair<Boolean, Next> = when (stage) {
        Stage.IDLE -> false to Next.Nothing
        Stage.SCANNING -> true to Next.Nothing
        Stage.HELD -> {
            faceTaps.addLast(now)
            while (faceTaps.isNotEmpty() && now - faceTaps.first() > windowMs) faceTaps.removeFirst()
            if (faceTaps.size >= 3) {
                faceTaps.clear()
                stage = Stage.SCANNING
                true to Next.Scan(1, thenPin = true)
            } else {
                true to Next.Nothing
            }
        }
    }

    /** A tap anywhere else on the screen: a held face goes away. */
    fun outsideTap(): Next = if (stage == Stage.HELD) done() else Next.Nothing
}
