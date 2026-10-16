package com.meetdheeran.notch.quick

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.meetdheeran.notch.NotchApp
import com.meetdheeran.notch.shizuku.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The six tiles in the island's quick panel. */
enum class QuickTile(val label: String) { TORCH("Torch"), WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), CAMERA("Camera"), TIMER("Timer"), PRISM("Ask Prism") }

/** What the switch tiles show: on or off. */
data class QuickStates(val torch: Boolean = false, val wifi: Boolean = false, val bluetooth: Boolean = false)

/**
 * The quick actions. Torch, camera, timer and Prism need nothing special. Wi-Fi and Bluetooth: Android 10+ doesn't
 * let apps switch Wi-Fi, so with Shizuku running they're switched as the shell would (`svc wifi`,
 * `cmd bluetooth_manager`); without it the tile opens the system's own Wi-Fi / Bluetooth panel instead.
 */
object QuickActions {
    private val _states = MutableStateFlow(QuickStates())
    val states: StateFlow<QuickStates> = _states

    private var app: Context? = null
    private var torchId: String? = null
    private var receiver: BroadcastReceiver? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) _states.value = _states.value.copy(torch = enabled)
        }
    }

    /** Starts following the switches' real state (call once; the island service does). */
    fun start(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val cm = a.getSystemService(CameraManager::class.java)
        torchId = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        runCatching { cm.registerTorchCallback(torchCallback, null) }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = readSwitches()
        }
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(a, receiver!!, f, ContextCompat.RECEIVER_EXPORTED)
        readSwitches()
    }

    private fun readSwitches() {
        val a = app ?: return
        val wifi = runCatching { a.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        val bt = runCatching { a.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
        _states.value = _states.value.copy(wifi = wifi, bluetooth = bt)
    }

    /** Runs [tile]. Returns true when it opened something (the panel should close), false for a switch. */
    fun run(ctx: Context, tile: QuickTile): Boolean = when (tile) {
        QuickTile.TORCH -> { torch(ctx); false }
        QuickTile.WIFI -> wifi(ctx)
        QuickTile.BLUETOOTH -> bluetooth(ctx)
        QuickTile.CAMERA -> open(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        QuickTile.TIMER -> open(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS))
        QuickTile.PRISM -> open(ctx, Intent(PRISM_ASSISTANT).setPackage(PRISM))
    }

    /** Hold a tile: its full settings page. */
    fun settingsFor(ctx: Context, tile: QuickTile) {
        val i = when (tile) {
            QuickTile.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            QuickTile.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            QuickTile.TIMER -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 300).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            else -> return
        }
        open(ctx, i)
    }

    fun prismInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(PRISM, 0); true }.getOrDefault(false)

    private fun torch(ctx: Context) {
        val id = torchId ?: return
        runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, !_states.value.torch) }
    }

    private fun wifi(ctx: Context): Boolean {
        val on = !_states.value.wifi
        if (!ShizukuBridge.isReady()) return open(ctx, Intent(Settings.Panel.ACTION_WIFI))
        _states.value = _states.value.copy(wifi = on)
        NotchApp.scope.launch {
            val r = ShizukuBridge.exec("svc wifi ${if (on) "enable" else "disable"}")
            if (r.getOrNull()?.ok != true) readSwitches()
        }
        return false
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun bluetooth(ctx: Context): Boolean {
        val on = !_states.value.bluetooth
        if (ShizukuBridge.isReady()) {
            _states.value = _states.value.copy(bluetooth = on)
            NotchApp.scope.launch {
                val r = ShizukuBridge.exec("cmd bluetooth_manager ${if (on) "enable" else "disable"}")
                if (r.getOrNull()?.ok != true) readSwitches()
            }
            return false
        }
        // Without Shizuku: Android 12 still lets an app with Bluetooth permission switch it.
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter ?: return open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        val done = runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
        return if (done) false else open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun open(ctx: Context, i: Intent): Boolean {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    private const val PRISM = "com.meetdheeran.prism"
    private const val PRISM_ASSISTANT = "com.meetdheeran.prism.OPEN_ASSISTANT"
}
