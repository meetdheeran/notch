package com.meetdheeran.notch.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * A face in a camera frame: MediaPipe's 478 points on it (in the frame's pixels), the box around them, and how the
 * head is turned ([FaceGeometry.pose]).
 */
class TrackedFace(val xs: FloatArray, val ys: FloatArray) {
    val box: RectF = RectF(xs.min(), ys.min(), xs.max(), ys.max())
    val pose: FaceGeometry.Pose = FaceGeometry.pose(xs, ys)
    val area: Float get() = box.width() * box.height()
}

/**
 * Finds faces and follows them from frame to frame with Google's MediaPipe Face Landmarker, on the phone's GPU (or
 * its CPU if the GPU won't have it). Tracking between frames is what keeps the points steady.
 */
class FaceTracker(ctx: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker = create(ctx, Delegate.GPU) ?: create(ctx, Delegate.CPU)
        ?: error("MediaPipe face landmarker couldn't start")
    private var lastTime = 0L
    private var closed = false

    /** Every face in [frame] (upright), biggest first. [timeMs] must keep going up; it's nudged if it doesn't. */
    @Synchronized
    fun track(frame: Bitmap, timeMs: Long): List<TrackedFace> {
        if (closed) return emptyList()
        val t = maxOf(timeMs, lastTime + 1)
        lastTime = t
        val result = landmarker.detectForVideo(BitmapImageBuilder(frame).build(), t)
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return result.faceLandmarks().map { points ->
            TrackedFace(FloatArray(points.size) { points[it].x() * w }, FloatArray(points.size) { points[it].y() * h })
        }.sortedByDescending { it.area }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        landmarker.close()
    }

    private companion object {
        fun create(ctx: Context, delegate: Delegate): FaceLandmarker? = runCatching {
            FaceLandmarker.createFromOptions(
                ctx,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/face_landmarker.task").setDelegate(delegate).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(2)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build(),
            )
        }.onFailure { Log.w("NotchFace", "face landmarker on $delegate failed", it) }.getOrNull()
    }
}
