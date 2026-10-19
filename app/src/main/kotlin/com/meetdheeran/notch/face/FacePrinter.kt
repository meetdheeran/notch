package com.meetdheeran.notch.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FaceNet-512 (deepface's FaceNet, MIT — docs/licenses/FACE-MODELS.md) on ONNX Runtime: the face cut out at a
 * [FaceGeometry.printCrop] becomes a print of 512 numbers, and prints of the same person point the same way.
 * About a tenth of a second a print: run it off the camera thread.
 */
class FacePrinter(ctx: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SIZE * SIZE)
    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var closed = false

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile(ctx).absolutePath, options)
        inputName = session.inputNames.first()
    }

    /** The print of the face at [c] in [frame], or null once closed. */
    @Synchronized
    fun print(frame: Bitmap, c: FaceMath.Crop): FloatArray? {
        if (closed) return null
        val m = Matrix().apply {
            postTranslate(-c.cx, -c.cy)
            postRotate(-Math.toDegrees(c.angle.toDouble()).toFloat())
            postScale(SIZE / c.size, SIZE / c.size)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        crop.eraseColor(0xFF000000.toInt())
        Canvas(crop).drawBitmap(frame, m, paint)
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            // RGB, standardised the way FaceNet was trained: (x − 127.5) / 128.
            input.put((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.put((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.put(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return FaceMath.normalize((out[0].value as Array<FloatArray>)[0])
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private companion object {
        const val SIZE = 160
        const val ASSET = "models/facenet512_weights.onnx"

        /** ONNX Runtime opens models from a file: the model is copied out of the APK once (and again after an update). */
        fun modelFile(ctx: Context): File {
            val f = File(ctx.noBackupFilesDir, "facenet512.onnx")
            val size = ctx.assets.openFd(ASSET).use { it.length }
            if (f.length() != size) {
                val part = File(f.path + ".part")
                ctx.assets.open(ASSET).use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
                f.delete()
                part.renameTo(f)
            }
            return f
        }
    }
}
