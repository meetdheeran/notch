package com.meetdheeran.notch.vault

import android.content.Context
import android.content.pm.PackageManager
import com.meetdheeran.notch.shizuku.ShizukuBridge

/**
 * How an app is hidden: disabled for this user (`pm disable-user`), as the shell can — through Shizuku. The launcher
 * and the app drawer drop it, it gets no notifications, its data stays, and `pm enable` brings it back in well under
 * a second (392 ms on Ledger Demo). The "not installed" route (`pm uninstall -k` + `install-existing`) was tried and
 * rejected: on OxygenOS 12 the restore said it worked and didn't — see docs/PLAN.md. Trade-off: the Play Store shows
 * a hidden app as disabled ("Enable"), not as missing.
 */
object Hider {
    suspend fun hide(ctx: Context, pkg: String): Result<Unit> =
        pm(ctx, "disable-user", pkg) { it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }

    suspend fun restore(ctx: Context, pkg: String): Result<Unit> =
        pm(ctx, "enable", pkg) { it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }

    /** Disabled for this user: hidden by the vault (or switched off by hand in Settings). */
    fun isHidden(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }.getOrDefault(false)

    fun installed(ctx: Context, pkg: String): Boolean = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** Runs pm, then believes the package manager about the app's state, not pm's words. */
    private suspend fun pm(ctx: Context, verb: String, pkg: String, isNow: (Int) -> Boolean): Result<Unit> {
        if (!pkg.matches(SAFE_PKG)) return Result.failure(IllegalArgumentException("Odd package name"))
        return ShizukuBridge.exec("pm $verb --user 0 $pkg").mapCatching { r ->
            val state = runCatching { ctx.packageManager.getApplicationEnabledSetting(pkg) }.getOrNull()
            check(state != null && isNow(state)) { (r.stderr + r.stdout).trim().ifBlank { "pm said nothing" } }
        }
    }

    private val SAFE_PKG = Regex("[A-Za-z0-9._]+")
}
