package com.meetdheeran.notch.face

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Face geometry from MediaPipe's face points, pure so it's unit-tested: how the head is turned, where to cut the
 * face out for its print, and the box the anti-spoof check looks at. Points are in the frame's pixels.
 */
object FaceGeometry {
    // MediaPipe face mesh points. "Left"/"right" here mean the left and right of the picture.
    private const val NOSE_TIP = 1
    private const val MOUTH_LEFT = 61
    private const val MOUTH_RIGHT = 291
    private const val IRIS_LEFT = 468
    private const val IRIS_RIGHT = 473
    private val EYE_LEFT_CORNERS = intArrayOf(33, 133)
    private val EYE_RIGHT_CORNERS = intArrayOf(362, 263)

    /**
     * How the head is turned. [yaw]: the nose's sideways offset from the middle of the eyes, in eye-widths (0 facing
     * the camera; positive when the nose points to the picture's right). [pitch]: how far down the nose sits between
     * the eyes and the mouth (about 0.5–0.6 facing the camera; less looking up, more looking down). [roll]: the tilt
     * of the line through the eyes, in radians.
     */
    data class Pose(val yaw: Float, val pitch: Float, val roll: Float)

    fun eyes(xs: FloatArray, ys: FloatArray): FloatArray =
        if (xs.size > IRIS_RIGHT) {
            floatArrayOf(xs[IRIS_LEFT], ys[IRIS_LEFT], xs[IRIS_RIGHT], ys[IRIS_RIGHT])
        } else {
            floatArrayOf(
                (xs[EYE_LEFT_CORNERS[0]] + xs[EYE_LEFT_CORNERS[1]]) / 2, (ys[EYE_LEFT_CORNERS[0]] + ys[EYE_LEFT_CORNERS[1]]) / 2,
                (xs[EYE_RIGHT_CORNERS[0]] + xs[EYE_RIGHT_CORNERS[1]]) / 2, (ys[EYE_RIGHT_CORNERS[0]] + ys[EYE_RIGHT_CORNERS[1]]) / 2,
            )
        }

    fun pose(xs: FloatArray, ys: FloatArray): Pose {
        val e = eyes(xs, ys)
        val roll = atan2(e[3] - e[1], e[2] - e[0])
        // Undo the tilt, then measure along the eye line (x) and across it (y).
        val c = cos(-roll)
        val s = sin(-roll)
        fun ux(i: Int) = xs[i] * c - ys[i] * s
        fun uy(i: Int) = xs[i] * s + ys[i] * c
        val lx = e[0] * c - e[1] * s
        val ly = e[0] * s + e[1] * c
        val rx = e[2] * c - e[3] * s
        val ry = e[2] * s + e[3] * c
        val eyeW = (rx - lx).coerceAtLeast(1f)
        val eyeMidX = (lx + rx) / 2
        val eyeMidY = (ly + ry) / 2
        val mouthY = (uy(MOUTH_LEFT) + uy(MOUTH_RIGHT)) / 2
        val yaw = (ux(NOSE_TIP) - eyeMidX) / eyeW
        val pitch = (uy(NOSE_TIP) - eyeMidY) / (mouthY - eyeMidY).coerceAtLeast(1f)
        return Pose(yaw, pitch, roll)
    }

    /**
     * Where to cut the face out for its print: centred between the eyes and the mouth, turned so the eyes are level,
     * and big enough to hold the whole face with a little margin — how FaceNet saw faces when it learned them. Sized
     * from the eye-to-mouth distance, which barely changes when the head turns sideways.
     */
    fun printCrop(xs: FloatArray, ys: FloatArray): FaceMath.Crop {
        val e = eyes(xs, ys)
        val angle = atan2(e[3] - e[1], e[2] - e[0])
        val eyeMidX = (e[0] + e[2]) / 2
        val eyeMidY = (e[1] + e[3]) / 2
        val mouthX = (xs[MOUTH_LEFT] + xs[MOUTH_RIGHT]) / 2
        val mouthY = (ys[MOUTH_LEFT] + ys[MOUTH_RIGHT]) / 2
        val eyeMouth = hypot(mouthX - eyeMidX, mouthY - eyeMidY)
        val eyeDist = hypot(e[2] - e[0], e[3] - e[1])
        val size = 2.7f * maxOf(eyeMouth, 0.9f * eyeDist)
        return FaceMath.Crop((eyeMidX + mouthX) / 2, (eyeMidY + mouthY) / 2, size, angle)
    }

    /**
     * The anti-spoof model's view: the face box grown [scale] times around its centre (less if the frame is too
     * small), then slid back inside the frame. Returns left, top, right, bottom. From MiniFASNet's own preprocessing.
     */
    fun spoofBox(left: Float, top: Float, right: Float, bottom: Float, scale: Float, frameW: Int, frameH: Int): IntArray {
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        val s = minOf((frameH - 1f) / h, (frameW - 1f) / w, scale)
        val nw = w * s
        val nh = h * s
        val cx = left + w / 2
        val cy = top + h / 2
        var x0 = cx - nw / 2
        var y0 = cy - nh / 2
        var x1 = cx + nw / 2
        var y1 = cy + nh / 2
        if (x0 < 0) { x1 -= x0; x0 = 0f }
        if (y0 < 0) { y1 -= y0; y0 = 0f }
        if (x1 > frameW - 1) { x0 -= x1 - (frameW - 1); x1 = frameW - 1f }
        if (y1 > frameH - 1) { y0 -= y1 - (frameH - 1); y1 = frameH - 1f }
        return intArrayOf(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
    }
}
