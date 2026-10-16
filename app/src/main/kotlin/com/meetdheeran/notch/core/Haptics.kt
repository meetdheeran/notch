package com.meetdheeran.notch.core

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Short system haptics. Off when the person switches haptics off in Notch. */
object Haptics {
    @Volatile var enabled = true

    private fun play(ctx: Context, effect: Int) {
        if (!enabled) return
        val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK)
    fun click(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK)
    fun heavy(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK)
    fun success(ctx: Context) = play(ctx, VibrationEffect.EFFECT_DOUBLE_CLICK)
}
