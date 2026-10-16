package com.meetdheeran.notch.buds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Left, right and case battery (0–100, or null when the buds don't say), and whether each is charging. */
data class BudsBattery(
    val left: Int?, val right: Int?, val case: Int?,
    val leftCharging: Boolean = false, val rightCharging: Boolean = false, val caseCharging: Boolean = false,
    /** elapsedRealtime when this was heard (set by the scanner). */
    val at: Long = 0L,
)

/**
 * Listens for Google Fast Pair adverts (service 0xFE2C), which earbuds like the CMF Buds Pro 2 send when their case
 * opens. When the advert carries a battery field, it holds left, right and case: one byte each, bit 7 = charging,
 * bits 0–6 = level (0x7F = unknown). Only adverts from close by (strong signal) count, so the neighbour's buds don't
 * pop up. Needs BLUETOOTH_SCAN (declared neverForLocation).
 *
 * Measured with Dheer's CMF Buds Pro 2 (2026-10-16): with the case open they advertise every ~0.3 s, e.g.
 * `00 70 7b1240505419 5a 11 09 34 64 64 37` → battery field 0x34 (len 3) = left 100, right 100, case 55; a 0xff
 * byte (bud out of the case) reads as unknown. They go quiet when the case closes, so the first advert after a gap is
 * "the case just opened" ([opened]).
 */
object BudsScanner {
    private const val TAG = "NotchBuds"
    private val FAST_PAIR = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")
    /** Closer than about a metre. */
    private const val NEAR_RSSI = -70

    private val _battery = MutableStateFlow<BudsBattery?>(null)
    val battery: StateFlow<BudsBattery?> = _battery

    /** elapsedRealtime of the last "case opened" — the first nearby advert after [GAP_MS] of quiet. */
    private val _opened = MutableStateFlow(0L)
    val opened: StateFlow<Long> = _opened
    private var lastHeard = 0L
    private const val GAP_MS = 8_000L

    private var scanning = false

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (scanning || !allowed(ctx)) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0), ByteArray(0)).build())
        // Low power: the case-open advert repeats for several seconds, so a slow scan still catches it.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(filters, settings, callback) }
            .onSuccess { scanning = true; Log.d(TAG, "scanning for Fast Pair adverts") }
            .onFailure { Log.w(TAG, "couldn't scan", it) }
    }

    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        if (!scanning) return
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(FAST_PAIR) ?: return
            if (result.rssi < NEAR_RSSI) return
            val b = BudsAdvert.parse(data) ?: return
            val now = SystemClock.elapsedRealtime()
            // Keep the last known level when one bud briefly reads "unknown" (lifted out of the case).
            val last = _battery.value
            _battery.value = b.copy(
                left = b.left ?: last?.left, right = b.right ?: last?.right, case = b.case ?: last?.case, at = now,
            )
            if (now - lastHeard > GAP_MS) {
                Log.d(TAG, "case opened: L=${b.left} R=${b.right} case=${b.case}")
                _opened.value = now
            }
            lastHeard = now
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
        }
    }

}

/** Reading a Fast Pair advert. Pure (no Android classes), so it's unit-tested. */
object BudsAdvert {
    /**
     * The battery field of a (not-discoverable) Fast Pair advert, or null when it has none. Layout: a flags byte, then
     * fields that each start with a header byte — length in the high nibble, type in the low (0x3 = battery shown,
     * 0x4 = battery hidden) — followed by that many bytes.
     */
    fun parse(d: ByteArray): BudsBattery? {
        if (d.size <= 3) return null // a bare 3-byte model id: the buds are in pairing mode, no battery
        var i = 1
        while (i < d.size) {
            val header = d[i].toInt() and 0xFF
            val len = header ushr 4
            val kind = header and 0x0F
            if ((kind == 0x3 || kind == 0x4) && len == 3 && i + 3 < d.size) {
                fun level(b: Byte) = (b.toInt() and 0x7F).takeIf { it in 0..100 }
                fun charging(b: Byte) = (b.toInt() and 0x80) != 0
                val l = d[i + 1]; val r = d[i + 2]; val c = d[i + 3]
                return BudsBattery(level(l), level(r), level(c), charging(l), charging(r), charging(c))
            }
            i += 1 + len
        }
        return null
    }

}
