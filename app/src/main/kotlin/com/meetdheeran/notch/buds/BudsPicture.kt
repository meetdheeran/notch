package com.meetdheeran.notch.buds

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * The picture on the buds card: one the person chose from their gallery (a photo of their own buds, say), kept as a
 * small copy in Notch's own files so it still shows if the original is moved or deleted. Stays on the phone.
 */
object BudsPicture {
    private const val SIZE = 512
    @Volatile private var cached: Bitmap? = null
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "buds.png")

    /** The chosen picture, or null to draw the default buds. */
    fun get(ctx: Context): Bitmap? {
        if (!loaded) {
            cached = runCatching { BitmapFactory.decodeFile(file(ctx).path) }.getOrNull()
            loaded = true
        }
        return cached
    }

    /** Copies the picture at [uri], scaled down so it's small. True when it worked. */
    fun set(ctx: Context, uri: Uri): Boolean = runCatching {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > SIZE) d.setTargetSize(info.size.width * SIZE / big, info.size.height * SIZE / big)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        file(ctx).outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cached = src
        loaded = true
        true
    }.getOrDefault(false)

    fun clear(ctx: Context) {
        file(ctx).delete()
        cached = null
        loaded = true
    }
}
