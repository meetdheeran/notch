package com.meetdheeran.notch.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An app in the vault: disabled for this user (gone from the launcher) until the vault brings it back. */
data class HiddenApp(val pkg: String, val label: String)

/**
 * The vault's list: which apps are hidden, their names and icons (saved when they're hidden, so the grid never has
 * to ask the system about a disabled app), and which are out for a moment ([exposed]) — restored and opened, to be
 * hidden again when left. On disk in Notch's private files, never backed up.
 */
object VaultStore {
    private val _apps = MutableStateFlow<List<HiddenApp>>(emptyList())
    val apps: StateFlow<List<HiddenApp>> = _apps
    private val _exposed = MutableStateFlow<Set<String>>(emptySet())
    /** Restored and not hidden again yet. Kept on disk, so a reboot or a Shizuku outage can't leave one out for good. */
    val exposed: StateFlow<Set<String>> = _exposed
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "vault").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "apps.json")
    private fun iconFile(ctx: Context, pkg: String) = File(dir(ctx), "$pkg.png")

    /** Reads the list once (at app start). */
    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            val a = o.getJSONArray("apps")
            _apps.value = List(a.length()) { i -> a.getJSONObject(i).let { HiddenApp(it.getString("pkg"), it.getString("label")) } }
            val e = o.optJSONArray("exposed") ?: JSONArray()
            _exposed.value = List(e.length()) { e.getString(it) }.toSet()
        }
    }

    fun isHidden(pkg: String): Boolean = _apps.value.any { it.pkg == pkg }

    @Synchronized
    fun add(ctx: Context, pkg: String, label: String, icon: Drawable?) {
        if (isHidden(pkg)) return
        if (icon != null) runCatching {
            iconFile(ctx, pkg).outputStream().use { out -> icon.toBitmap(ICON_PX, ICON_PX).compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        _apps.value = (_apps.value + HiddenApp(pkg, label)).sortedBy { it.label.lowercase() }
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, pkg: String) {
        _apps.value = _apps.value.filter { it.pkg != pkg }
        _exposed.value = _exposed.value - pkg
        iconFile(ctx, pkg).delete()
        save(ctx)
    }

    @Synchronized
    fun setExposed(ctx: Context, pkg: String, out: Boolean) {
        val next = if (out) _exposed.value + pkg else _exposed.value - pkg
        if (next == _exposed.value) return
        _exposed.value = next
        save(ctx)
    }

    /** The icon saved when the app was hidden; failing that, the one the package manager still has for it. */
    fun icon(ctx: Context, pkg: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(iconFile(ctx, pkg).path) }.getOrNull()
            ?: runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX) }.getOrNull()

    private fun save(ctx: Context) {
        val o = JSONObject()
            .put("apps", JSONArray().apply { _apps.value.forEach { put(JSONObject().put("pkg", it.pkg).put("label", it.label)) } })
            .put("exposed", JSONArray().apply { _exposed.value.forEach { put(it) } })
        runCatching { file(ctx).writeText(o.toString()) }
    }

    private const val ICON_PX = 192
}
