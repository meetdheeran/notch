package com.meetdheeran.notch.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("notch")

/** Everything the person can change about the island. */
data class Settings(
    val enabled: Boolean = true,
    val showMedia: Boolean = true,
    val showCharging: Boolean = true,
    val showCalls: Boolean = true,
    val showTimers: Boolean = true,
    val showNotifications: Boolean = true,
    /** Wi-Fi / Bluetooth joined, battery low, fully charged: three-second pops. */
    val showEvents: Boolean = true,
    /** Screen recording (red dot + time) and the hotspot, while they're on. */
    val showSystem: Boolean = true,
    /** Earbuds connecting, with their battery. */
    val showEarbuds: Boolean = true,
    /** Two live things at once: the island plus a bubble beside it. */
    val split: Boolean = true,
    /** The Face-ID-style moment after unlocking the phone. */
    val unlockGlyph: Boolean = true,
    val haptics: Boolean = true,
    /** The vault's face unlock keeps up with your look (glasses, a haircut, other light) — FaceLearner. */
    val faceLearn: Boolean = true,
    /** Animation speed, 0.6 (calm) to 1.6 (snappy). */
    val speed: Float = 1f,
    /** Without the accessibility switch the island floats as a pill under the status bar: its size and drop. */
    val pillExtraWidthDp: Int = 24,
    val pillExtraHeightDp: Int = 10,
    val pillOffsetDp: Int = 2,
)

class Prefs(private val ctx: Context) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val media = booleanPreferencesKey("media")
        val charging = booleanPreferencesKey("charging")
        val calls = booleanPreferencesKey("calls")
        val timers = booleanPreferencesKey("timers")
        val notifications = booleanPreferencesKey("notifications")
        val events = booleanPreferencesKey("events")
        val system = booleanPreferencesKey("system")
        val earbuds = booleanPreferencesKey("earbuds")
        val split = booleanPreferencesKey("split")
        val unlock = booleanPreferencesKey("unlock_glyph")
        val haptics = booleanPreferencesKey("haptics")
        val faceLearn = booleanPreferencesKey("face_learn")
        val speed = floatPreferencesKey("speed")
        val pillW = intPreferencesKey("pill_w")
        val pillH = intPreferencesKey("pill_h")
        val pillY = intPreferencesKey("pill_y")
    }

    val settings: Flow<Settings> = ctx.store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) {
        ctx.store.edit { p ->
            val s = change(p.toSettings())
            p[K.enabled] = s.enabled
            p[K.media] = s.showMedia
            p[K.charging] = s.showCharging
            p[K.calls] = s.showCalls
            p[K.timers] = s.showTimers
            p[K.notifications] = s.showNotifications
            p[K.events] = s.showEvents
            p[K.system] = s.showSystem
            p[K.earbuds] = s.showEarbuds
            p[K.split] = s.split
            p[K.unlock] = s.unlockGlyph
            p[K.haptics] = s.haptics
            p[K.faceLearn] = s.faceLearn
            p[K.speed] = s.speed
            p[K.pillW] = s.pillExtraWidthDp
            p[K.pillH] = s.pillExtraHeightDp
            p[K.pillY] = s.pillOffsetDp
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            enabled = this[K.enabled] ?: d.enabled,
            showMedia = this[K.media] ?: d.showMedia,
            showCharging = this[K.charging] ?: d.showCharging,
            showCalls = this[K.calls] ?: d.showCalls,
            showTimers = this[K.timers] ?: d.showTimers,
            showNotifications = this[K.notifications] ?: d.showNotifications,
            showEvents = this[K.events] ?: d.showEvents,
            showSystem = this[K.system] ?: d.showSystem,
            showEarbuds = this[K.earbuds] ?: d.showEarbuds,
            split = this[K.split] ?: d.split,
            unlockGlyph = this[K.unlock] ?: d.unlockGlyph,
            haptics = this[K.haptics] ?: d.haptics,
            faceLearn = this[K.faceLearn] ?: d.faceLearn,
            speed = this[K.speed] ?: d.speed,
            pillExtraWidthDp = this[K.pillW] ?: d.pillExtraWidthDp,
            pillExtraHeightDp = this[K.pillH] ?: d.pillExtraHeightDp,
            pillOffsetDp = this[K.pillY] ?: d.pillOffsetDp,
        )
    }
}
