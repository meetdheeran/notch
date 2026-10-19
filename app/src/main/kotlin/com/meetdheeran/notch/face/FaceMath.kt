package com.meetdheeran.notch.face

import kotlin.math.sqrt

/**
 * The maths of Notch's face unlock, pure so it's unit-tested: face prints and comparing them. Geometry is in
 * [FaceGeometry]; the unlock rules in [FaceJudge].
 */
object FaceMath {
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val len = sqrt(sum).toFloat()
        return if (len > 0f) FloatArray(v.size) { v[it] / len } else v.copyOf()
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "prints of different sizes: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d > 0) (dot / d).toFloat() else 0f
    }

    /** How close [face] is to the closest of the prints saved at setup. */
    fun bestMatch(face: FloatArray, enrolled: List<FloatArray>): Float = enrolled.maxOfOrNull { cosine(face, it) } ?: 0f

    /** Where to cut the face out: a square [size] across, centred at ([cx], [cy]), turned by [angle] radians. */
    data class Crop(val cx: Float, val cy: Float, val size: Float, val angle: Float)
}
