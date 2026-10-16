package com.meetdheeran.notch.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.graphics.Color

/**
 * The phone's accent colour, as OxygenOS 12 itself uses it. Measured on the OnePlus 7 (2026-10-16): the system UI's
 * accent is stored as Settings.Secure `sysui_type_accent_color` (e.g. "#ff4f67d2"), and follows the colour the person
 * picked (or the wallpaper, when theming takes its colour from there). Falls back to Android 12's
 * system_accent1_600 (wallpaper colours), then to the OxygenOS default blue. Kept up to date while Notch runs.
 */
object SystemAccent {
    private const val KEY = "sysui_type_accent_color"
    private var observer: ContentObserver? = null

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        apply(app)
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = apply(app)
        }
        runCatching { app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY), false, observer!!) }
    }

    fun read(ctx: Context): Color? {
        val raw = runCatching { Settings.Secure.getString(ctx.contentResolver, KEY) }.getOrNull()
        parse(raw)?.let { return it }
        return runCatching { Color(ctx.getColor(android.R.color.system_accent1_600)) }.getOrNull()
    }

    private fun apply(ctx: Context) { read(ctx)?.let { Oos.accent = it } }

    /** "#ff4f67d2" / "#4f67d2" / a decimal int → a colour; null when it isn't one. */
    fun parse(raw: String?): Color? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val argb = runCatching {
            if (s.startsWith("#")) {
                val hex = s.substring(1)
                val v = hex.toLong(16)
                when (hex.length) { 6 -> (0xFF000000 or v).toInt(); 8 -> v.toInt(); else -> return null }
            } else s.toLong().toInt()
        }.getOrNull() ?: return null
        return Color(argb)
    }
}
