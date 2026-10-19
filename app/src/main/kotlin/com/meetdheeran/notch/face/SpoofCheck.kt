package com.meetdheeran.notch.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Tells a real face from a photo or a screen held up to the camera: MiniFASNet (minivision, Apache-2.0 —
 * docs/licenses/FACE-MODELS.md), two small models looking at the face from two distances, as its authors run it.
 */
class SpoofCheck(ctx: Context) : AutoCloseable {
    private val near = interpreter(ctx, "models/spoof_model_scale_2_7.tflite")
    private val far = interpreter(ctx, "models/spoof_model_scale_4_0.tflite")
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SIZE * SIZE)
    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val channelsFirst = near.getInputTensor(0).shape().let { it.size == 4 && it[1] == 3 }
    private var closed = false

    /** How sure (0–1) it is that the face in [box] of [frame] is really there, not a photo or a screen. */
    @Synchronized
    fun realness(frame: Bitmap, box: RectF): Float? {
        if (closed) return null
        val a = softmax(run(near, frame, box, 2.7f))
        val b = softmax(run(far, frame, box, 4.0f))
        // Class 1 is "real" (0 and 2 are the two kinds of fake).
        return (a[1] + b[1]) / 2f
    }

    private fun run(model: Interpreter, frame: Bitmap, box: RectF, scale: Float): FloatArray {
        val r = FaceGeometry.spoofBox(box.left, box.top, box.right, box.bottom, scale, frame.width, frame.height)
        Canvas(crop).drawBitmap(frame, Rect(r[0], r[1], r[2], r[3]), Rect(0, 0, SIZE, SIZE), paint)
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        // Blue, green, red, 0–255 — the models were trained on OpenCV's BGR frames.
        if (channelsFirst) {
            for (shift in intArrayOf(0, 8, 16)) for (p in pixels) input.putFloat(((p shr shift) and 0xFF).toFloat())
        } else {
            for (p in pixels) {
                input.putFloat((p and 0xFF).toFloat())
                input.putFloat(((p shr 8) and 0xFF).toFloat())
                input.putFloat(((p shr 16) and 0xFF).toFloat())
            }
        }
        input.rewind()
        val out = Array(1) { FloatArray(3) }
        model.run(input, out)
        return out[0]
    }

    private fun softmax(x: FloatArray): FloatArray {
        val max = x.max()
        val e = FloatArray(x.size) { exp(x[it] - max) }
        val sum = e.sum()
        return FloatArray(e.size) { e[it] / sum }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        near.close()
        far.close()
    }

    private companion object {
        const val SIZE = 80

        fun interpreter(ctx: Context, asset: String): Interpreter {
            val fd = ctx.assets.openFd(asset)
            val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            return Interpreter(model, Interpreter.Options().setNumThreads(2))
        }
    }
}
