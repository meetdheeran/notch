package com.meetdheeran.notch.island

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import com.meetdheeran.notch.NotchApp
import com.meetdheeran.notch.access.NotchAccessibility
import com.meetdheeran.notch.core.Haptics
import com.meetdheeran.notch.core.Settings
import com.meetdheeran.notch.overlay.BackgroundNotice
import com.meetdheeran.notch.overlay.OverlayHost
import com.meetdheeran.notch.quick.QuickActions
import com.meetdheeran.notch.buds.BudsBattery
import com.meetdheeran.notch.buds.BudsPicture
import com.meetdheeran.notch.buds.BudsScanner
import com.meetdheeran.notch.quick.QuickStates
import com.meetdheeran.notch.quick.QuickTile
import com.meetdheeran.notch.ui.NotchTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How long the buds card stays up after the case opens. */
const val BUDS_MS = 6_000L

data class BatteryInfo(val percent: Int = -1, val charging: Boolean = false)

/** The face check on the island (the vault's): scanning, then it's you (ok) or it isn't (fail). */
enum class FaceScan { OFF, SCANNING, OK, FAIL }

/** A 3-second pop: low battery, Wi-Fi or Bluetooth connected. */
data class IslandEvent(val kind: Kind, val text: String, val at: Long) {
    enum class Kind { BATTERY_LOW, FULL, WIFI, BLUETOOTH, EARBUDS }
}

/** What the island is showing. One "focus" at a time, priority: call > charging bloom > timer/nav > media. */
data class IslandState(
    val media: NowPlaying? = null,
    /** The most important live thing ([activities] first). */
    val activity: LiveActivity? = null,
    /** Everything live right now (calls, navigation, timers, downloads), most important first. */
    val activities: List<LiveActivity> = emptyList(),
    /** Which live thing the person swiped to ("media", "charge" or an activity's key); null: the most important. */
    val focusKey: String? = null,
    /** Plugged in and not yet full: charging is one of the live things. */
    val chargingLive: Boolean = false,
    val battery: BatteryInfo = BatteryInfo(),
    /** elapsedRealtime when the cable went in; the bloom shows for 3 s after. */
    val chargedAt: Long = 0L,
    val expanded: Boolean = false,
    /** Floating pill size in dp (no accessibility switch), derived from the real cutout + the person's fine-tune. */
    val pillWidthDp: Float = 88f,
    val pillHeightDp: Float = 38f,
    val event: IslandEvent? = null,
    /** A notification sliding out of the island, and when it arrived (elapsedRealtime). */
    val peek: ShadeItem? = null,
    val peekAt: Long = 0L,
    /** Two things at once → the island plus a detached bubble. */
    val split: Boolean = true,
    /** 0.6 … 1.6, the person's animation speed. */
    val speed: Float = 1f,
    /** elapsedRealtime of the last unlock; the island plays the Face-ID-style unlock for a moment. */
    val unlockAt: Long = 0L,
    /** The vault's face check, and when its state last changed. */
    val face: FaceScan = FaceScan.OFF,
    val faceAt: Long = 0L,
    /**
     * In the notch: the island lives in Notch's accessibility service, above the status bar, as a black tab growing
     * out of the top edge around the camera — always there (so it can always be tapped), and taps never reach the
     * status bar. Off (no accessibility switch): a floating pill under the status bar.
     */
    val notch: Boolean = false,
    /** The real cutout, for the tab. */
    val cutWidthDp: Float = 64f,
    val cutHeightDp: Float = 28f,
    val landscape: Boolean = false,
    /** Bumped when a timed pop (bloom, event, peek) expires, so the UI re-reads the clock. */
    val tick: Long = 0L,
    /** The quick actions panel is open (tap the idle island), and what its switches show. */
    val quick: Boolean = false,
    /** The earbuds card (case opened): their battery, name and picture, and when it opened (elapsedRealtime). */
    val buds: BudsBattery? = null,
    val budsAt: Long = 0L,
    val budsName: String = "Earbuds",
    val budsPicture: android.graphics.Bitmap? = null,
    val quickStates: QuickStates = QuickStates(),
    val prism: Boolean = false,
) {
    val showChargeBloom: Boolean get() = chargedAt > 0 && SystemClock.elapsedRealtime() - chargedAt < 3_000
    val showEvent: Boolean get() = event != null && SystemClock.elapsedRealtime() - event.at < 3_500
    val showPeek: Boolean get() = peek != null && SystemClock.elapsedRealtime() - peekAt < 3_500
    val showBuds: Boolean get() = buds != null && budsAt > 0 && SystemClock.elapsedRealtime() - budsAt < BUDS_MS
    val showUnlock: Boolean get() = unlockAt > 0 && SystemClock.elapsedRealtime() - unlockAt < 1_700
    /** Scanning shows until the vault says otherwise (12 s at most); ok / fail for a moment. */
    val showFace: Boolean get() = face != FaceScan.OFF &&
        SystemClock.elapsedRealtime() - faceAt < (if (face == FaceScan.SCANNING) 12_000 else 1_400)
    /** Cards that close when you tap anywhere else on the screen. */
    val wantsOutsideTaps: Boolean get() = expanded || quick
    val hasContent: Boolean get() = (notch && !landscape) || quick || showBuds || activities.isNotEmpty() || chargingLive || showFace || showUnlock ||
        media != null || activity != null || showChargeBloom || showEvent || showPeek
}

/** Battery broadcasts as flows. */
class ChargingWatcher(private val ctx: Context) {
    val info = MutableStateFlow(BatteryInfo())
    val connectedAt = MutableStateFlow(0L)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    info.value = BatteryInfo(if (level >= 0) level * 100 / scale else -1, charging)
                }
                Intent.ACTION_POWER_CONNECTED -> connectedAt.value = SystemClock.elapsedRealtime()
                Intent.ACTION_POWER_DISCONNECTED -> connectedAt.value = 0L
            }
        }
    }

    fun start() {
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() { runCatching { ctx.unregisterReceiver(receiver) } }
}

/**
 * Foreground service that owns the island. In the notch (accessibility switch on) the island is always there; as a
 * floating pill it only adds its window while there is something to show.
 */
class IslandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: OverlayHost? = null
    /** In the notch, the island's touch area (its window there lets touches through; see [params]). */
    private var pad: IslandPad? = null
    private var charging: ChargingWatcher? = null
    private val state = MutableStateFlow(IslandState())
    private val events = MutableStateFlow<IslandEvent?>(null)
    private val peeks = MutableStateFlow<Pair<ShadeItem?, Long>>(null to 0L)
    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var btReceiver: BroadcastReceiver? = null
    private var hotspotReceiver: BroadcastReceiver? = null
    private val hotspot = MutableStateFlow(false)
    /** Whether the window currently asks for taps outside it (only while a closable card is up). */
    private var watchingOutside = false
    private var collapseJob: Job? = null
    private var showing = false
    /** The island is in Notch's accessibility service's window, in the notch (see [IslandState.notch]). */
    private var inNotch = false
    private val knock = TapCounter()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BackgroundNotice.start(this)
        running = this
        host = OverlayHost(this)
        charging = ChargingWatcher(this).also { it.start() }
        MediaWatcher.start(this)
        QuickActions.start(this)
        BudsScanner.start(this)
        // The case opened: the buds card drops out of the notch for a few seconds; battery updates keep it current.
        scope.launch {
            BudsScanner.opened.collect { at ->
                if (at == 0L || !lastSettings.showEarbuds) return@collect
                state.value = state.value.copy(
                    buds = BudsScanner.battery.value, budsAt = at, budsName = budsName(), budsPicture = BudsPicture.get(this@IslandService),
                )
                sync()
                Haptics.click(this@IslandService)
                delay(BUDS_MS + 100)
                state.value = state.value.copy(tick = SystemClock.elapsedRealtime())
                sync()
            }
        }
        scope.launch { BudsScanner.battery.collect { b -> if (state.value.showBuds) state.value = state.value.copy(buds = b) } }
        scope.launch { QuickActions.states.collect { q -> state.value = state.value.copy(quickStates = q) } }
        val ch = charging!!
        // Low battery: one pop when crossing 15% while unplugged.
        scope.launch {
            var wasLow = false
            var wasFull = true
            ch.info.collect { b ->
                val low = b.percent in 1..15 && !b.charging
                if (low && !wasLow) events.value = IslandEvent(IslandEvent.Kind.BATTERY_LOW, "Battery low · ${b.percent}%", SystemClock.elapsedRealtime())
                wasLow = low
                // Charging done: once, when it reaches 100% on the cable.
                val full = b.charging && b.percent >= 100
                if (full && !wasFull) events.value = IslandEvent(IslandEvent.Kind.FULL, "Fully charged", SystemClock.elapsedRealtime())
                wasFull = full || !b.charging
            }
        }
        // Wi-Fi joined (ignore the callback that fires for an already-connected network at registration).
        val startedAt = SystemClock.elapsedRealtime()
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        wifiCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (SystemClock.elapsedRealtime() - startedAt > 3_000) events.value = IslandEvent(IslandEvent.Kind.WIFI, "Wi-Fi connected", SystemClock.elapsedRealtime())
            }
        }
        runCatching { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback!!) }
        // Bluetooth device connected.
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val dev = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE) ?: return
                when (i.action) {
                    android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> btConnected(dev)
                    ACTION_BATTERY_LEVEL_CHANGED -> earbudsBattery(dev, i.getIntExtra(EXTRA_BATTERY_LEVEL, -1))
                }
            }
        }
        ContextCompat.registerReceiver(
            this, btReceiver!!,
            IntentFilter().apply { addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED); addAction(ACTION_BATTERY_LEVEL_CHANGED) },
            ContextCompat.RECEIVER_EXPORTED,
        )
        // The hotspot, as a live thing while it's on.
        hotspotReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                hotspot.value = i.getIntExtra("wifi_state", 0) == WIFI_AP_STATE_ENABLED
            }
        }
        ContextCompat.registerReceiver(this, hotspotReceiver!!, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        scope.launch { NotchListener.peek.collect { peeks.value = it to SystemClock.elapsedRealtime() } }
        state.value = state.value.copy(landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        // With Notch's accessibility switch on, the island moves into the notch (above the status bar).
        scope.launch { NotchAccessibility.connected.collect { on -> rehost(on && NotchAccessibility.instance != null) } }
        // Unlocked (face, fingerprint or PIN): the Face-ID-style moment. Apps can't draw over the lock screen itself,
        // so this is the first moment the island is visible again.
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!lastSettings.unlockGlyph) return
                state.value = state.value.copy(unlockAt = SystemClock.elapsedRealtime())
                sync()
                scope.launch { delay(1_800); state.value = state.value.copy(tick = SystemClock.elapsedRealtime()); sync() }
            }
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        scope.launch {
            combine(NotchApp.prefs.settings, MediaWatcher.now, NotchListener.activities.combine(hotspot) { a, on -> if (on) a + HOTSPOT_LIVE else a }, ch.info, ch.connectedAt) { s: Settings, np, acts, bat, at ->
                IslandState(
                    media = if (s.showMedia) np else null,
                    activity = liveOnes(acts, s).firstOrNull(),
                    activities = liveOnes(acts, s),
                    chargingLive = s.showCharging && bat.charging && bat.percent in 0..99,
                    battery = bat,
                    chargedAt = if (s.showCharging) at else 0L,
                    pillWidthDp = cutoutWidthDp() + s.pillExtraWidthDp,
                    pillHeightDp = cutoutHeightDp() + s.pillExtraHeightDp,
                    cutWidthDp = cutoutWidthDp(),
                    cutHeightDp = cutoutHeightDp(),
                    split = s.split,
                    speed = s.speed,
                ) to s
            }.combine(events) { (st, s), ev -> (if (s.showEvents) st.copy(event = ev) else st) to s }
            .combine(peeks) { (st, s), (item, at) -> (if (s.showNotifications) st.copy(peek = item, peekAt = at) else st) to s }
            .collect { (next, s) ->
                lastSettings = s
                Haptics.enabled = s.haptics
                val newOffset = (s.pillOffsetDp * resources.displayMetrics.density).toInt()
                val offsetChanged = newOffset != offsetPx
                offsetPx = newOffset
                state.value = next.copy(
                    expanded = state.value.expanded && next.media != null,
                    unlockAt = state.value.unlockAt,
                    face = state.value.face,
                    faceAt = state.value.faceAt,
                    notch = inNotch,
                    landscape = state.value.landscape,
                    focusKey = state.value.focusKey,
                    quick = state.value.quick,
                    buds = state.value.buds,
                    budsAt = state.value.budsAt,
                    budsName = state.value.budsName,
                    budsPicture = state.value.budsPicture,
                    quickStates = state.value.quickStates,
                    prism = QuickActions.prismInstalled(this@IslandService),
                )
                if (offsetChanged && showing && !inNotch) { host?.update(params(state.value.wantsOutsideTaps)); watchingOutside = state.value.wantsOutsideTaps }
                sync()
                refreshOutsideWatch()
                if (next.showChargeBloom || next.showEvent || next.showPeek) {
                    delay(3_600)
                    state.value = state.value.copy(tick = SystemClock.elapsedRealtime())
                    sync()
                }
            }
        }
    }

    private var lastSettings = Settings()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.hasExtra(EXTRA_STAY_AWAKE) == true && com.meetdheeran.notch.BuildConfig.DEBUG) setStayAwake(intent.getBooleanExtra(EXTRA_STAY_AWAKE, false))
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turned sideways the notch is on the side: the idle tab steps away (things with content stay).
        state.value = state.value.copy(landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        sync()
    }

    /** Moves the island between the notch (accessibility overlay, above the status bar) and the floating pill. */
    private fun rehost(notch: Boolean) {
        if (notch == inNotch && host != null) return
        if (showing) hide()
        host?.destroy()
        pad?.hide()
        val svc = if (notch) NotchAccessibility.instance else null
        inNotch = svc != null
        host = if (svc != null) OverlayHost(svc, accessibility = true) else if (OverlayHost.canDrawOverlays(this)) OverlayHost(this) else null
        pad = svc?.let { IslandPad(it, island = { host?.view }, onOutside = { outsideTap() }) }
        state.value = state.value.copy(notch = inNotch)
        sync()
    }

    /** Every tap on the island counts towards the knock: four quick ones open the secret door. */
    private fun tapped() {
        if (knock.tap(SystemClock.uptimeMillis())) knocked()
    }

    /** Four taps: the secret door. The vault (face check → hidden apps) plugs in here. */
    private fun knocked() {
        Haptics.heavy(this)
        onKnock?.invoke(this)
    }

    /** One tap on the idle island, with no second one after it: the quick actions panel plugs in here. */
    private fun quickTap() {
        onQuickTap?.let { it(this); return }
        setQuick(!state.value.quick)
    }

    private var quickJob: Job? = null

    /** Opens or closes the quick panel; it closes by itself after a few seconds untouched. */
    private fun setQuick(open: Boolean) {
        if (open && state.value.expanded) setExpanded(false)
        state.value = state.value.copy(quick = open, prism = QuickActions.prismInstalled(this))
        sync()
        refreshOutsideWatch()
        quickJob?.cancel()
        if (open) quickJob = scope.launch { delay(QUICK_IDLE_MS); setQuick(false) }
    }

    private fun quickTile(tile: QuickTile) {
        val opened = QuickActions.run(this, tile)
        if (opened) setQuick(false) else setQuick(true)
    }

    private fun quickHold(tile: QuickTile) {
        Haptics.click(this)
        QuickActions.settingsFor(this, tile)
        setQuick(false)
    }

    /** In the notch, where the island takes touches on the screen (px): its pad's bounds. Empty while hidden. */
    private val touchArea = android.graphics.Rect()

    /** The island laid out its touch area ([r], in its window's px): the pad follows. */
    private fun placePad(r: Rect) {
        val v = host?.view ?: return
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        val next = android.graphics.Rect(
            at[0] + kotlin.math.floor(r.left).toInt(), at[1] + kotlin.math.floor(r.top).toInt(),
            at[0] + kotlin.math.ceil(r.right).toInt(), at[1] + kotlin.math.ceil(r.bottom).toInt(),
        )
        if (!showing || !inNotch || next == touchArea) return
        touchArea.set(next)
        pad?.place(next, watchingOutside)
    }

    /** The live things the island may show, by the person's settings, most important first. */
    private fun liveOnes(acts: List<LiveActivity>, s: Settings): List<LiveActivity> = acts.filter { a ->
        when (a.kind) {
            LiveActivity.Kind.CALL -> s.showCalls
            LiveActivity.Kind.TIMER, LiveActivity.Kind.NAVIGATION, LiveActivity.Kind.PROGRESS -> s.showTimers
            LiveActivity.Kind.RECORDING, LiveActivity.Kind.HOTSPOT -> s.showSystem
        }
    }.sortedBy {
        when (it.kind) {
            LiveActivity.Kind.CALL -> 0
            LiveActivity.Kind.RECORDING -> 1
            LiveActivity.Kind.NAVIGATION -> 2
            LiveActivity.Kind.TIMER -> 3
            LiveActivity.Kind.HOTSPOT -> 4
            LiveActivity.Kind.PROGRESS -> 5
        }
    }

    /** Pull the island down: open what it's showing — the music app, the call, the download, the battery page. */
    private fun openLive(key: String) {
        val s = state.value
        runCatching {
            when (key) {
                "media" -> s.media?.packageName?.let { pkg ->
                    packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
                }
                "charge" -> startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                HOTSPOT_LIVE.key -> startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> s.activities.firstOrNull { it.key == key }?.contentIntent?.send()
            }
        }
        if (s.expanded) setExpanded(false)
    }

    private fun sync() {
        val s = state.value
        if (s.hasContent && !showing) show() else if (!s.hasContent && showing) hide()
    }

    private var offsetPx = 0

    /** The waterdrop cutout as Android reports it (falls back to the OnePlus 7 numbers). */
    private fun cutout(): android.graphics.Rect? = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop
    }.getOrNull()?.takeIf { !it.isEmpty }
    private fun cutoutWidthDp(): Float = (cutout()?.width() ?: 180) / resources.displayMetrics.density
    private fun cutoutHeightDp(): Float = (cutout()?.height() ?: 80) / resources.displayMetrics.density

    /**
     * The island's window. In the notch: a screen-wide strip along the top edge that never moves or resizes and lets
     * every touch through ([pad] takes the island's) — Android re-centres a window that changes size a frame apart from
     * its picture, which made the island jump sideways as it opened and closed. A trusted (accessibility) overlay, so
     * touches through it reach the apps underneath. Out of the notch: a pill window wrapping the island.
     */
    private fun params(expanded: Boolean): WindowManager.LayoutParams = if (inNotch) {
        OverlayHost.params(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = ((cutoutHeightDp() + STAGE_DP) * resources.displayMetrics.density).roundToInt(),
            gravity = Gravity.TOP, touchable = false,
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (stayAwake) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
    } else {
        OverlayHost.params(
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, focusable = false, y = offsetPx,
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        ).apply {
            if (expanded) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            if (stayAwake) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
    }

    private fun show() {
        val h = host ?: return
        showing = true
        val wide = inNotch
        h.show(params(false)) {
            val st by state.collectAsState()
            NotchTheme(dark = true) {
                // In the notch the window is a screen-wide strip: the island hangs from the middle of its top.
                Box(if (wide) Modifier.fillMaxWidth() else Modifier, contentAlignment = Alignment.TopCenter) {
                    IslandUi(
                        state = st,
                        onTap = { tapped() },
                        onQuickTap = { quickTap() },
                        onQuickTile = { t -> quickTile(t) },
                        onQuickHold = { t -> quickHold(t) },
                        onFocus = { key -> state.value = state.value.copy(focusKey = key) },
                        onOpen = { key -> openLive(key) },
                        onToggleExpand = { setExpanded(!st.expanded) },
                        onHold = { holdIsland() },
                        onActivityTap = { a -> runCatching { a.contentIntent?.send() } },
                        onPeekTap = { n -> runCatching { n.contentIntent?.send() } },
                        onHitArea = { r -> placePad(r) },
                        onPeekDismiss = { peeks.value = null to 0L },
                    )
                }
            }
        }
        // Out of the notch the window is the island itself, and hears the taps outside it.
        h.view?.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) outsideTap()
            false
        }
    }

    /** A tap anywhere else on the screen closes the card that's open. */
    private fun outsideTap() {
        if (state.value.expanded) setExpanded(false)
        if (state.value.quick) setQuick(false)
        onOutsideTap?.invoke(this)
    }

    /** Ask for outside taps only while a closable card is showing; otherwise the window stays out of the way. */
    private fun refreshOutsideWatch() {
        val want = state.value.wantsOutsideTaps || wantOutside
        if (showing && want != watchingOutside) watchOutside(want)
    }

    /** In the notch the pad hears the taps outside; out of it, the island's own window. */
    private fun watchOutside(want: Boolean) {
        watchingOutside = want
        if (inNotch) { if (!touchArea.isEmpty) pad?.place(touchArea, want) } else host?.update(params(want))
    }

    private fun setExpanded(expanded: Boolean) {
        state.value = state.value.copy(expanded = expanded)
        refreshOutsideWatch()
        collapseJob?.cancel()
        if (expanded) collapseJob = scope.launch { delay(5_000); setExpanded(false) }
    }

    private fun hide() {
        showing = false
        watchingOutside = false
        pad?.hide()
        touchArea.setEmpty()
        host?.hide()
    }

    /** Hold the island: Prism's assistant, when Prism is installed. */
    private fun holdIsland() {
        setExpanded(false)
        Haptics.click(this)
        runCatching {
            startActivity(Intent(PRISM_ASSISTANT).setPackage(PRISM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------ debug: keep the phone awake during adb tests

    private var stayAwake = false
    private var stayAwakeJob: Job? = null

    /**
     * Test builds only: the island's window keeps the screen on (FLAG_KEEP_SCREEN_ON), for 3 hours at most and only while
     * plugged in — so the phone doesn't lock mid-test. A window flag, not a wake lock: OxygenOS force-releases a
     * background app's wake locks, but not a visible window's flag. In the notch the island's window is always there.
     */
    private fun setStayAwake(on: Boolean) {
        stayAwake = on
        stayAwakeJob?.cancel()
        if (on) stayAwakeJob = scope.launch {
            val until = SystemClock.elapsedRealtime() + 3 * 60 * 60 * 1000L
            while (SystemClock.elapsedRealtime() < until) {
                delay(30_000)
                if (charging?.connectedAt?.value == 0L && charging?.info?.value?.charging != true) break
            }
            setStayAwake(false)
        }
        if (showing) host?.update(params(watchingOutside))
    }

    // ------------------------------------------------------------ Bluetooth: earbuds and their battery

    /** The earbuds that just connected, and when — their battery usually arrives a moment after. */
    private var earbuds: Pair<String, Long>? = null

    private fun btConnected(dev: android.bluetooth.BluetoothDevice) {
        val name = runCatching { dev.name }.getOrNull().orEmpty()
        val audio = runCatching { dev.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO }.getOrDefault(false)
        val now = SystemClock.elapsedRealtime()
        if (audio && name.isNotBlank()) lastBudsName = name
        if (audio && lastSettings.showEarbuds) {
            earbuds = dev.address to now
            events.value = IslandEvent(IslandEvent.Kind.EARBUDS, earbudsText(name, batteryOf(dev)), now)
        } else {
            events.value = IslandEvent(IslandEvent.Kind.BLUETOOTH, if (name.isBlank()) "Bluetooth connected" else "Connected · $name", now)
        }
    }

    /** Their battery came in (or changed) within a few seconds of connecting: the pop shows it. */
    private fun earbudsBattery(dev: android.bluetooth.BluetoothDevice, level: Int) {
        val (address, at) = earbuds ?: return
        if (dev.address != address || level < 0 || SystemClock.elapsedRealtime() - at > 8_000) return
        val name = runCatching { dev.name }.getOrNull().orEmpty()
        events.value = IslandEvent(IslandEvent.Kind.EARBUDS, earbudsText(name, level), SystemClock.elapsedRealtime())
    }

    /**
     * Which earbuds the card is about (the advert doesn't carry a name): the paired audio device that's connected right
     * now, else the last earbuds that connected while Notch ran, else "Earbuds". Not just the first paired one — with
     * several paired (CMF and Aukey here) that picked the wrong pair.
     */
    @Suppress("MissingPermission")
    private fun budsName(): String {
        val audio = runCatching {
            getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.bondedDevices
                .filter { it.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO }
        }.getOrNull().orEmpty()
        // BluetoothDevice.isConnected() is hidden (but app-usable) on Android 12.
        val connected = audio.firstOrNull { d -> runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false) }
        return runCatching { connected?.name }.getOrNull() ?: lastBudsName ?: "Earbuds"
    }

    /** The last earbuds that connected (set by [btConnected]). */
    private var lastBudsName: String? = null

    private fun earbudsText(name: String, level: Int) = name.ifBlank { "Earbuds" } + if (level in 0..100) " · $level%" else ""

    /** BluetoothDevice.getBatteryLevel() is hidden (but app-usable) on Android 12; -1 when it isn't known yet. */
    private fun batteryOf(dev: android.bluetooth.BluetoothDevice): Int =
        runCatching { dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int }.getOrDefault(-1)

    // ------------------------------------------------------------ for the vault and the quick actions

    /** Another part of Notch wants taps outside the island reported (a panel is open). */
    private var wantOutside = false

    fun watchOutsideTaps(want: Boolean) {
        wantOutside = want
        refreshOutsideWatch()
    }

    /** Shows the face check on the island; it stays until set to [FaceScan.OFF] (or 12 s while scanning). */
    fun setFace(face: FaceScan) {
        val at = SystemClock.elapsedRealtime()
        state.value = state.value.copy(face = face, faceAt = at)
        sync()
        if (face == FaceScan.OFF) return
        scope.launch {
            delay(if (face == FaceScan.SCANNING) 12_100 else 1_500)
            if (state.value.faceAt == at) {
                state.value = state.value.copy(tick = SystemClock.elapsedRealtime())
                sync()
            }
        }
    }

    val island: StateFlow<IslandState> get() = state

    override fun onDestroy() {
        if (running === this) running = null
        hide()
        host?.destroy()
        charging?.stop()
        BudsScanner.stop(this)
        wifiCallback?.let { runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
        hotspotReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        BackgroundNotice.stop(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        /** Passed on by MainActivity: `adb shell am start -n com.meetdheeran.notch/.MainActivity --ez stayAwake true` */
        const val EXTRA_STAY_AWAKE = "stayAwake"
        private const val PRISM = "com.meetdheeran.prism"
        private const val PRISM_ASSISTANT = "com.meetdheeran.prism.OPEN_ASSISTANT"
        /** In the notch, how far down the island's window reaches below the camera: its tallest card, with room to spare. */
        private const val STAGE_DP = 320f
        private const val QUICK_IDLE_MS = 6_000L

        // Hidden-but-sent broadcasts: the hotspot turning on/off, and a Bluetooth device's battery.
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        /** The hotspot as a live thing (it has no notification of its own to read). */
        private val HOTSPOT_LIVE = LiveActivity(
            key = "hotspot", packageName = "android", kind = LiveActivity.Kind.HOTSPOT, title = "Hotspot",
            text = "", whenMs = 0L, chronometerBase = 0L, countDown = false, contentIntent = null,
        )

        /** The running service, for the vault and the quick actions. */
        @Volatile var running: IslandService? = null
            private set

        /** Hooks other parts of Notch fill in: four taps, a single tap on the idle island, a tap outside. */
        var onKnock: ((IslandService) -> Unit)? = null
        var onQuickTap: ((IslandService) -> Unit)? = null
        var onOutsideTap: ((IslandService) -> Unit)? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, IslandService::class.java))

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, IslandService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
