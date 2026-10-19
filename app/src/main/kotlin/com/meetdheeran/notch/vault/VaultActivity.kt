package com.meetdheeran.notch.vault

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.meetdheeran.notch.NotchApp
import com.meetdheeran.notch.core.Settings
import com.meetdheeran.notch.face.FaceCheck
import com.meetdheeran.notch.face.FaceSetup
import com.meetdheeran.notch.face.FaceStore
import com.meetdheeran.notch.face.FaceTest
import com.meetdheeran.notch.face.cameraAllowed
import com.meetdheeran.notch.island.FaceScan
import com.meetdheeran.notch.island.IslandService
import com.meetdheeran.notch.shizuku.ShizukuBridge
import com.meetdheeran.notch.shizuku.ShizukuState
import com.meetdheeran.notch.ui.NotchTheme
import kotlinx.coroutines.launch

/**
 * The vault's screen, see-through: the face check shows nothing of its own (the island does the talking) and a tap
 * anywhere gives up; once you're in, the hidden apps come up as a sheet over whatever was there. Also the face setup
 * and test screens. An Activity because Android 12 lets no background service open the camera.
 */
class VaultActivity : ComponentActivity() {
    enum class Mode { SCAN, SETUP, TEST }

    private sealed interface Step {
        data class Scan(val tries: Int, val thenPin: Boolean) : Step
        data object Pin : Step
        data object Grid : Step
        data object Picker : Step
        data object Setup : Step
        data object Test : Step
    }

    private var step by mutableStateOf<Step?>(null)
    /** This is a way in (not setup or test), so the flow must hear how it ended. */
    private var checking = false
    private var reported = false
    private var learnAfterPin: () -> Unit = {}
    /** The app being restored, hidden or unhidden right now, and what went wrong last. */
    private var busy by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) step = Step.Setup else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        alive = true
        val mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(Mode.SCAN)
        step = when (mode) {
            Mode.SETUP -> if (cameraAllowed(this)) Step.Setup else { askCamera.launch(Manifest.permission.CAMERA); null }
            Mode.TEST -> Step.Test
            Mode.SCAN -> {
                checking = true
                // No face set up yet, or no camera: the PIN pad straight away.
                if (FaceStore.enrolled(this) && cameraAllowed(this)) Step.Scan(intent.getIntExtra(EXTRA_TRIES, 2), intent.getBooleanExtra(EXTRA_THEN_PIN, false))
                else Step.Pin
            }
        }
        setContent { NotchTheme(dark = true) { Screen() } }
    }

    override fun onDestroy() {
        alive = false
        if (checking && !reported) { reported = true; Vault.checkLost() }
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val s by NotchApp.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
        val shizuku by ShizukuBridge.state.collectAsStateWithLifecycle()
        val apps by VaultStore.apps.collectAsStateWithLifecycle()
        when (val st = step) {
            null -> Unit
            is Step.Scan -> {
                // Nothing on screen: the island shows the face. A tap anywhere else gives up.
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { giveUp() })
                FaceCheck(
                    tries = st.tries,
                    island = { IslandService.running?.setFace(it) },
                    onUnlocked = { reported = true; Vault.unlocked(); step = Step.Grid },
                    onFailed = { learn ->
                        if (st.thenPin) { learnAfterPin = learn; step = Step.Pin } else { reported = true; Vault.checkFailed(); finish() }
                    },
                    onCancel = { giveUp() },
                    learn = s.faceLearn,
                )
            }
            Step.Pin -> {
                Box(Modifier.fillMaxSize())
                LaunchedEffect(Unit) {
                    if (!PinLock.available(this@VaultActivity)) { letIn(); return@LaunchedEffect }
                    PinLock.ask(this@VaultActivity, "Hidden apps") { ok -> if (ok) { learnAfterPin(); letIn() } else giveUp() }
                }
            }
            Step.Grid -> VaultGrid(
                apps = apps, busy = busy, error = error, shizuku = shizuku,
                onOpen = ::openApp, onAdd = { error = null; step = Step.Picker }, onUnhide = ::unhide,
                onShizuku = { ShizukuBridge.requestPermission() }, onClose = { finish() },
            )
            Step.Picker -> AppPicker(busy = busy, error = error, onPick = ::hideApp, onBack = { error = null; step = Step.Grid })
            Step.Setup -> FaceSetup(onDone = { finish() }, onCancel = { finish() })
            Step.Test -> FaceTest(onDone = { finish() })
        }
    }

    private fun letIn() {
        reported = true
        Vault.unlocked()
        IslandService.running?.setFace(FaceScan.OFF)
        step = Step.Grid
    }

    private fun giveUp() {
        if (checking && !reported) { reported = true; Vault.checkDone() }
        finish()
    }

    // ------------------------------------------------------------ the grid's doings

    private fun openApp(app: HiddenApp) {
        if (busy != null) return
        busy = app.pkg
        error = null
        lifecycleScope.launch {
            if (!Hider.installed(this@VaultActivity, app.pkg)) {
                // Uninstalled since (from the Play Store, say): nothing left to open.
                VaultStore.remove(this@VaultActivity, app.pkg)
                error = "${app.label} isn’t installed any more"
                busy = null
                return@launch
            }
            val r = Hider.restore(this@VaultActivity, app.pkg)
            val launch = if (r.isSuccess) packageManager.getLaunchIntentForPackage(app.pkg) else null
            when {
                r.isFailure -> error = why(r)
                launch == null -> { error = "${app.label} couldn’t be opened"; Hider.hide(this@VaultActivity, app.pkg) }
                else -> {
                    Vault.opened(this@VaultActivity, app.pkg)
                    runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    finish()
                }
            }
            busy = null
        }
    }

    private fun hideApp(app: PickedApp) {
        if (busy != null) return
        busy = app.pkg
        error = null
        lifecycleScope.launch {
            val r = Hider.hide(this@VaultActivity, app.pkg)
            if (r.isSuccess) { VaultStore.add(this@VaultActivity, app.pkg, app.label, app.icon); step = Step.Grid } else error = why(r)
            busy = null
        }
    }

    private fun unhide(app: HiddenApp) {
        if (busy != null) return
        busy = app.pkg
        error = null
        lifecycleScope.launch {
            val r = if (Hider.installed(this@VaultActivity, app.pkg)) Hider.restore(this@VaultActivity, app.pkg) else Result.success(Unit)
            if (r.isSuccess) VaultStore.remove(this@VaultActivity, app.pkg) else error = why(r)
            busy = null
        }
    }

    private fun why(r: Result<*>): String = when (ShizukuBridge.refresh()) {
        ShizukuState.READY -> r.exceptionOrNull()?.message ?: "Shizuku couldn’t do it"
        else -> ShizukuBridge.state.value.label
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TRIES = "tries"
        private const val EXTRA_THEN_PIN = "thenPin"

        /** Whether the screen is up (so a second knock doesn't start a second check). */
        @Volatile var alive = false
            private set

        private fun intent(ctx: Context, mode: Mode) =
            Intent(ctx, VaultActivity::class.java).putExtra(EXTRA_MODE, mode.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** A way in: the face, [tries] tries; the PIN pad after the last ([thenPin]), or the face held on the island. */
        fun scan(ctx: Context, tries: Int, thenPin: Boolean) {
            runCatching { ctx.startActivity(intent(ctx, Mode.SCAN).putExtra(EXTRA_TRIES, tries).putExtra(EXTRA_THEN_PIN, thenPin)) }
        }

        fun open(ctx: Context, mode: Mode) { runCatching { ctx.startActivity(intent(ctx, mode)) } }
    }
}
