package com.meetdheeran.notch.face

import kotlin.math.abs

/**
 * Decides when to let you in, from the slow stage's reads (several a second). Your face must match the saved prints
 * on 3 of the last 4 reads, the latest among them, and look really there — not a photo or a screen — on 2 of the
 * last 3. Someone else never matches and is told so after a few reads; a run of photo-like reads is called a spoof;
 * and if it still isn't sure after [UNSURE_AFTER] reads short of the pass mark, it hands over to fingerprint/PIN
 * rather than keep you waiting.
 */
class FaceJudge(private val threshold: Float) {
    enum class State { LOOKING, UNLOCKED, NOT_YOU, SPOOF, UNSURE }

    private val matched = ArrayDeque<Boolean>()
    private val real = ArrayDeque<Boolean>()
    private var strangers = 0
    private var fakes = 0
    private var misses = 0

    /** One read: how alike the face is to the saved ones (null: no print), and how real it looked (null: not checked). */
    fun feed(similarity: Float?, realness: Float?): State {
        if (similarity != null) {
            keep(matched, similarity >= threshold, 4)
            strangers = if (similarity < threshold - STRANGER_GAP) strangers + 1 else 0
            if (similarity < threshold) misses++
        }
        if (realness != null) {
            keep(real, realness >= REAL, 3)
            fakes = if (realness < FAKE) fakes + 1 else 0
        }
        return when {
            fakes >= 4 -> State.SPOOF
            strangers >= 6 -> State.NOT_YOU
            matched.lastOrNull() == true && matched.count { it } >= 3 && real.count { it } >= 2 -> State.UNLOCKED
            misses >= UNSURE_AFTER -> State.UNSURE
            else -> State.LOOKING
        }
    }

    private fun keep(q: ArrayDeque<Boolean>, v: Boolean, max: Int) {
        q.addLast(v)
        while (q.size > max) q.removeFirst()
    }

    companion object {
        /** MiniFASNet's "real" chance at or above this counts as a real face; below [FAKE], as a photo or a screen. */
        const val REAL = 0.6f
        const val FAKE = 0.3f
        /** A read this far under the pass mark is clearly someone else. */
        const val STRANGER_GAP = 0.15f
        /** Reads short of the pass mark (about two seconds' worth) before handing over to fingerprint/PIN. */
        const val UNSURE_AFTER = 12
    }
}

/** The pass mark for one person's face, set from their own setup prints. */
object FaceCalibration {
    const val MIN = 0.6f
    const val MAX = 0.78f
    const val MARGIN = 0.15f
    const val DEFAULT = 0.68f

    /**
     * How alike a new look at this face should be: each setup print is scored against the rest, as an unlock will
     * be; the pass mark sits [MARGIN] under the lower end of those scores (their 20th percentile), between [MIN] and
     * [MAX]. Lighting and angles change from day to day, so the margin is generous — FaceNet puts other people far
     * lower than that.
     */
    fun threshold(prints: List<FloatArray>): Float {
        if (prints.size < 3) return DEFAULT
        val scores = prints.indices.map { i -> prints.indices.filter { it != i }.maxOf { FaceMath.cosine(prints[i], prints[it]) } }.sorted()
        return (scores[scores.size / 5] - MARGIN).coerceIn(MIN, MAX)
    }
}

/**
 * Walks through face setup: prints looking straight at the camera, then turned a little to one side, the other side,
 * chin up and chin down — so it knows you however you hold the phone. Every print must be the same person as the
 * straight-on ones. A turn that doesn't register within a few seconds is skipped rather than keeping you stuck.
 */
class SetupGuide {
    enum class Step(val hint: String, val needed: Int) {
        STRAIGHT("Look straight at the camera", 5),
        SIDE("Turn your head a little to one side", 3),
        OTHER_SIDE("Now a little to the other side", 3),
        UP("Tilt your chin up a little", 3),
        DOWN("Now tilt your chin down a little", 3),
    }

    val prints = mutableListOf<FloatArray>()
    var step: Step? = Step.STRAIGHT
        private set
    val done: Boolean get() = step == null
    val hint: String get() = step?.hint ?: "Done"

    private val straight = mutableListOf<FloatArray>()
    private val pitches = mutableListOf<Float>()
    private var basePitch = 0f
    private var side = 0f
    private var inStep = 0
    private var stepStarted = -1L
    private var lastTaken = -1L
    private var strangers = 0

    /** One read at [timeMs]: its print (null: none) and pose. Returns what to tell the person, if it changed. */
    fun feed(print: FloatArray?, pose: FaceGeometry.Pose, timeMs: Long): String? {
        val s = step ?: return null
        if (stepStarted < 0) stepStarted = timeMs
        if (s != Step.STRAIGHT && timeMs - stepStarted > SKIP_MS) {
            next(timeMs)
            return hint
        }
        if (print == null || (lastTaken >= 0 && timeMs - lastTaken < GAP_MS)) return null
        if (!fits(s, pose)) return null
        if (straight.size >= 2 && FaceMath.cosine(print, mean(straight)) < SAME) {
            if (++strangers >= 5) {
                restart()
                return "Just you, please — starting again"
            }
            return "Just you, please"
        }
        strangers = 0
        prints += print
        lastTaken = timeMs
        if (s == Step.STRAIGHT) {
            straight += print
            pitches += pose.pitch
        }
        if (s == Step.SIDE && inStep == 0) side = if (pose.yaw > 0) 1f else -1f
        if (++inStep >= s.needed) {
            if (s == Step.STRAIGHT) basePitch = pitches.sorted()[pitches.size / 2]
            next(timeMs)
            return hint
        }
        return null
    }

    private fun fits(s: Step, p: FaceGeometry.Pose): Boolean = when (s) {
        Step.STRAIGHT -> abs(p.yaw) < 0.1f && abs(p.roll) < 0.3f
        Step.SIDE -> abs(p.yaw) >= TURN
        Step.OTHER_SIDE -> p.yaw * side <= -TURN
        Step.UP -> p.pitch <= basePitch - TILT && abs(p.yaw) < TURN
        Step.DOWN -> p.pitch >= basePitch + TILT && abs(p.yaw) < TURN
    }

    private fun next(timeMs: Long) {
        val all = Step.entries
        step = all.getOrNull((step?.ordinal ?: all.size) + 1)
        inStep = 0
        stepStarted = timeMs
    }

    private fun restart() {
        prints.clear()
        straight.clear()
        pitches.clear()
        step = Step.STRAIGHT
        inStep = 0
        stepStarted = -1
        strangers = 0
    }

    private fun mean(v: List<FloatArray>): FloatArray = FaceMath.normalize(FloatArray(v.first().size) { k -> v.sumOf { it[k].toDouble() }.toFloat() })

    companion object {
        val TOTAL = Step.entries.sumOf { it.needed }
        const val GAP_MS = 250L
        const val SKIP_MS = 8_000L
        /** Sideways turn (in eye-widths, about 20°) and chin tilt that count as "a little". */
        const val TURN = 0.15f
        const val TILT = 0.07f
        /** Setup prints must be at least this alike to the straight-on ones: the same person. */
        const val SAME = 0.45f
    }
}

/**
 * How the saved face keeps up with you — glasses, a haircut, a beard, other light. After a face unlock, a read that
 * looks a little different from every saved print is kept; after a face check that nearly passed and was then
 * unlocked with fingerprint/PIN, its closest read is kept too — that's how bigger changes get learned. Only
 * real-looking reads with one face in view count, and someone else scores far below a near miss. Learned prints are
 * kept apart from the setup ones, at most [MAX] (the oldest go first), and never move the pass mark.
 */
object FaceLearner {
    const val MAX = 10
    /** At least this sure the face was really there. */
    const val REAL = 0.8f
    /** At least this alike to a saved print already: nothing new to learn. */
    const val KNOWN = 0.92f
    /** A near miss: at most this far under the pass mark. */
    const val NEAR = 0.1f

    class Read(val print: FloatArray, val match: Float, val realness: Float, val faces: Int)

    /** After a face unlock: the read worth keeping, if any. */
    fun fromUnlock(reads: List<Read>, threshold: Float): FloatArray? =
        reads.filter { it.faces == 1 && it.realness >= REAL && it.match >= threshold && it.match < KNOWN }.maxByOrNull { it.match }?.print

    /** After a near miss and then fingerprint/PIN: the closest read, if it really was close. */
    fun fromNearMiss(reads: List<Read>, threshold: Float): FloatArray? =
        reads.filter { it.faces == 1 && it.realness >= REAL && it.match < threshold && it.match >= threshold - NEAR }.maxByOrNull { it.match }?.print

    /** [learned] with [print] added, the oldest dropped beyond [MAX]. */
    fun add(learned: List<FloatArray>, print: FloatArray): List<FloatArray> = (learned + listOf(print)).takeLast(MAX)
}

/**
 * Face first: a face check that fails scans again by itself, and only the second failure within [WINDOW_MS] hands
 * over to fingerprint/PIN. Failures are remembered across checks, so opening hidden apps twice in a minute and
 * failing both times counts too; a face unlock clears them.
 */
class FaceRetry {
    private val fails = ArrayDeque<Long>()

    /** A check failed at [now]: true when it's time for fingerprint/PIN, false to try the face again. */
    @Synchronized
    fun failed(now: Long): Boolean {
        fails.addLast(now)
        while (fails.isNotEmpty() && now - fails.first() > WINDOW_MS) fails.removeFirst()
        return fails.size >= 2
    }

    @Synchronized
    fun unlocked() = fails.clear()

    companion object {
        const val WINDOW_MS = 60_000L
        /** Kept from Morse; Notch counts tries per check instead (see FaceCheck). */
        val shared = FaceRetry()
    }
}
