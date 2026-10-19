package com.meetdheeran.notch.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/** What the camera saw in one frame: how many faces, and the biggest one. */
class FaceLook(val faces: Int, val face: TrackedFace?)

/** The slow stage's answer for one frame: the print, how real the face looked, how the head was turned. */
class FaceRead(val print: FloatArray?, val realness: Float?, val pose: FaceGeometry.Pose, val faces: Int, val ms: Long)

/**
 * Notch's face unlock (Morse's second version, ported). MediaPipe follows the face on every frame, which is quick; when the slow
 * stage is free, that frame also goes there for a FaceNet-512 print and a MiniFASNet spoof check — so tracking
 * stays smooth while the heavy models work. Nothing leaves the phone and no picture is kept.
 */
class FaceScanner(ctx: Context) : AutoCloseable {
    private val tracker = FaceTracker(ctx)
    private val printer = FacePrinter(ctx)
    private val spoof = SpoofCheck(ctx)
    private val slow = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /**
     * One upright camera frame, on the camera's thread. Says at once what's in view; when the face is near enough
     * and the slow stage is free, also reads it there and hands the result to [onRead] (on the slow thread).
     */
    fun feed(frame: Bitmap, timeMs: Long, onRead: (FaceRead) -> Unit): FaceLook {
        if (closed) return FaceLook(0, null)
        val faces = runCatching { tracker.track(frame, timeMs) }.getOrDefault(emptyList())
        val face = faces.firstOrNull() ?: return FaceLook(0, null)
        val e = FaceGeometry.eyes(face.xs, face.ys)
        val near = hypot(e[2] - e[0], e[3] - e[1]) >= MIN_EYES_PX
        if (near && busy.compareAndSet(false, true)) {
            val count = faces.size
            slow.execute {
                try {
                    if (closed) return@execute
                    val t0 = SystemClock.elapsedRealtime()
                    val print = runCatching { printer.print(frame, FaceGeometry.printCrop(face.xs, face.ys)) }.getOrNull()
                    val real = runCatching { spoof.realness(frame, face.box) }.getOrNull()
                    if (!closed) onRead(FaceRead(print, real, face.pose, count, SystemClock.elapsedRealtime() - t0))
                } finally {
                    busy.set(false)
                }
            }
        }
        return FaceLook(faces.size, face)
    }

    /** Stops at once; the models close after any read already under way. */
    override fun close() {
        if (closed) return
        closed = true
        tracker.close()
        slow.execute {
            printer.close()
            spoof.close()
        }
        slow.shutdown()
    }

    private companion object {
        /** Eyes at least this far apart (pixels) — nearer than about an arm and a half — before a print is worth taking. */
        const val MIN_EYES_PX = 36f
    }
}
