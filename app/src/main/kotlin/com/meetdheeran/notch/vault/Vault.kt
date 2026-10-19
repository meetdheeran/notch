package com.meetdheeran.notch.vault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.meetdheeran.notch.NotchApp
import com.meetdheeran.notch.access.NotchAccessibility
import com.meetdheeran.notch.face.FaceStore
import com.meetdheeran.notch.face.ScannerPool
import com.meetdheeran.notch.face.cameraAllowed
import com.meetdheeran.notch.island.FaceScan
import com.meetdheeran.notch.island.IslandService
import com.meetdheeran.notch.shizuku.ShizukuBridge
import com.meetdheeran.notch.shizuku.ShizukuState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The vault, wired to the island: the knock and the taps on a held face come in from [IslandService]; the face check,
 * the PIN and the grid run in [VaultActivity]; and a hidden app that was opened is hidden again the moment it's left
 * (another app or the launcher in front, or the screen going off). What happens when is decided in [VaultFlow].
 */
object Vault {
    private const val TAG = "NotchVault"
    private val flow = VaultFlow()
    private var app: Context? = null
    /** Exposed apps seen in front since they were opened — before that, "something else in front" is just the launch taking a moment. */
    private val seen = mutableSetOf<String>()
    private val hiding = mutableSetOf<String>()
    private var launchWatch: Job? = null

    /** Idempotent. Call from Application.onCreate. */
    fun install(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        VaultStore.load(a)
        IslandService.onKnock = { svc -> act(svc, flow.knock()) }
        // The third tap of a knock: load the face models now, so the check starts the moment the fourth lands.
        IslandService.onKnockNear = { svc -> if (FaceStore.enrolled(svc) && cameraAllowed(svc)) ScannerPool.warm(svc) }
        IslandService.onIslandTap = { svc, now ->
            val (took, next) = flow.islandTap(now)
            act(svc, next)
            took
        }
        IslandService.onOutsideTap = { svc -> act(svc, flow.outsideTap()) }
        NotchApp.scope.launch { NotchAccessibility.foreground.collect { pkg -> if (pkg != null) inFront(pkg) } }
        NotchApp.scope.launch { ShizukuBridge.state.collect { if (it == ShizukuState.READY) rehideAll("Shizuku is back") } }
        ContextCompat.registerReceiver(
            a,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_OFF) rehideAll("screen off") }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun act(svc: IslandService, next: VaultFlow.Next) {
        when (next) {
            is VaultFlow.Next.Scan -> {
                // A knock while the grid is already up: nothing to start.
                if (VaultActivity.alive) { flow.done(); return }
                svc.watchOutsideTaps(false)
                VaultActivity.scan(svc, next.tries, next.thenPin)
            }
            VaultFlow.Next.Hold -> { svc.setFace(FaceScan.HELD); svc.watchOutsideTaps(true) }
            VaultFlow.Next.Clear -> { svc.setFace(FaceScan.OFF); svc.watchOutsideTaps(false) }
            VaultFlow.Next.Nothing -> Unit
        }
    }

    /** From Notch's settings: the same way in as the knock, without the knock. */
    fun open(ctx: Context) {
        if (VaultActivity.alive) return
        val next = flow.knock()
        if (next is VaultFlow.Next.Scan) VaultActivity.scan(ctx, next.tries, next.thenPin)
    }

    // ------------------------------------------------------------ told by VaultActivity

    /** Both tries failed and there's no PIN this time: the face stays on the island. */
    fun checkFailed() { IslandService.running?.let { act(it, flow.failed()) } ?: flow.failed() }

    /** The face (or the PIN) said yes: the flow starts over; the island's smile is left to fade by itself. */
    fun unlocked() { flow.done() }

    /** Cancelled, or the PIN said no. */
    fun checkDone() { IslandService.running?.let { act(it, flow.done()) } ?: flow.done() }

    /** The check's activity went away without saying how it ended. */
    fun checkLost() { IslandService.running?.let { act(it, flow.lost()) } ?: flow.lost() }

    /** [pkg] was restored and launched: hide it again once it has been in front and then isn't. */
    fun opened(ctx: Context, pkg: String) {
        VaultStore.setExposed(ctx, pkg, true)
        seen -= pkg
        launchWatch?.cancel()
        // If it never shows up (its launch failed), don't leave it out.
        launchWatch = NotchApp.scope.launch {
            delay(LAUNCH_GRACE_MS)
            if (pkg in VaultStore.exposed.value && pkg !in seen) rehide(pkg, "never came to the front")
        }
    }

    // ------------------------------------------------------------ hiding again

    private fun inFront(pkg: String) {
        val out = VaultStore.exposed.value
        if (out.isEmpty()) return
        if (pkg in out) { seen += pkg; return }
        if (pkg in PASSING) return
        for (p in out) if (p in seen) rehide(p, "left for $pkg")
    }

    private fun rehideAll(why: String) {
        for (p in VaultStore.exposed.value) rehide(p, why)
    }

    private fun rehide(pkg: String, why: String) {
        val a = app ?: return
        if (!hiding.add(pkg)) return
        NotchApp.scope.launch {
            try {
                val r = Hider.hide(a, pkg)
                if (r.isSuccess) {
                    VaultStore.setExposed(a, pkg, false)
                    seen -= pkg
                    Log.d(TAG, "hid $pkg again ($why)")
                } else {
                    Log.w(TAG, "couldn't hide $pkg again ($why): ${r.exceptionOrNull()?.message}")
                }
            } finally {
                hiding -= pkg
            }
        }
    }

    private const val LAUNCH_GRACE_MS = 15_000L

    /** Windows that come up over an app without it being "left": permission prompts, the share sheet, the file picker. */
    private val PASSING = setOf(
        "android", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.documentsui", "com.google.android.documentsui", "com.android.packageinstaller", "com.google.android.packageinstaller",
    )
}
