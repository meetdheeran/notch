package com.meetdheeran.notch

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meetdheeran.notch.access.NotchAccessibility
import com.meetdheeran.notch.core.Settings
import com.meetdheeran.notch.island.IslandService
import com.meetdheeran.notch.island.MediaWatcher
import com.meetdheeran.notch.overlay.BackgroundNotice
import com.meetdheeran.notch.overlay.OverlayHost
import com.meetdheeran.notch.shizuku.ShizukuBridge
import com.meetdheeran.notch.shizuku.ShizukuState
import com.meetdheeran.notch.ui.NotchTheme
import com.meetdheeran.notch.ui.Oos
import com.meetdheeran.notch.ui.pressable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Notch's one screen: what still needs switching on, then everything you can change about the island. */
class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        passStayAwake(intent)
        setContent { NotchTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val s by NotchApp.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
        // Re-checked every time you come back from a system settings page.
        var resumes by remember { mutableIntStateOf(0) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
            owner.lifecycle.addObserver(o)
            onDispose { owner.lifecycle.removeObserver(o) }
        }
        val overlay = remember(resumes) { OverlayHost.canDrawOverlays(this) }
        val listener = remember(resumes) { MediaWatcher.hasAccess(this) }
        val access = remember(resumes) { accessibilityOn() }
        val shizuku = remember(resumes) { ShizukuBridge.refresh() }
        val bluetooth = remember(resumes) { checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val audio = remember(resumes) { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        LaunchedEffect(s.enabled, overlay, access) { if (s.enabled && (overlay || access)) IslandService.start(this@MainActivity) }

        fun set(change: (Settings) -> Settings) { NotchApp.scope.launch { NotchApp.prefs.update(change) } }

        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(36.dp))
            Text("Notch", fontSize = 34.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 8.dp))
            Text(
                "The island in your camera notch", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))

            if (!overlay || !listener || !access || shizuku != ShizukuState.READY) {
                Header("Set up")
                Card {
                    if (!access) Step(
                        "Put the island in the notch",
                        "Accessibility → Downloaded apps → Notch island → On. Without it the island floats under the status bar.",
                    ) { open(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    if (!listener) Step("See music, calls and timers", "Notification access → Notch → Allow") {
                        open(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    if (shizuku != ShizukuState.READY) Step(
                        "Allow Notch in Shizuku",
                        when (shizuku) {
                            ShizukuState.NOT_INSTALLED -> "Install Shizuku first. Needed for the Wi-Fi tile and hidden apps."
                            ShizukuState.NOT_RUNNING -> "Start Shizuku (it stops after every reboot). Needed for the Wi-Fi tile and hidden apps."
                            else -> "Tap, then Allow. Needed for the Wi-Fi tile and hidden apps."
                        },
                    ) { ShizukuBridge.requestPermission() }
                    if (!overlay) Step("Display over other apps", "Needed for the floating island when it isn't in the notch") {
                        open(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Card {
                Toggle("Island", "Show the island in the notch", s.enabled) { on ->
                    set { it.copy(enabled = on) }
                    if (on) IslandService.start(this@MainActivity) else IslandService.stop(this@MainActivity)
                }
            }
            Spacer(Modifier.height(20.dp))

            Header("On the island")
            Card {
                Toggle("Music", "What's playing, with a card when tapped", s.showMedia) { v -> set { it.copy(showMedia = v) } }
                Divider()
                Toggle("Charging", "Plugging in, and charging while it's below 100%", s.showCharging) { v -> set { it.copy(showCharging = v) } }
                Divider()
                Toggle("Calls", "The call time beside the camera", s.showCalls) { v -> set { it.copy(showCalls = v) } }
                Divider()
                Toggle("Timers, navigation and downloads", null, s.showTimers) { v -> set { it.copy(showTimers = v) } }
                Divider()
                Toggle("Notifications", "New ones drip out of the notch for a moment", s.showNotifications) { v -> set { it.copy(showNotifications = v) } }
                Divider()
                Toggle("Wi-Fi, Bluetooth and battery", "A three-second pop: connected, low battery, fully charged", s.showEvents) { v -> set { it.copy(showEvents = v) } }
                Divider()
                Toggle("Earbuds", "When they connect, with their battery", s.showEarbuds && bluetooth) { v ->
                    if (v && !bluetooth) askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    set { it.copy(showEarbuds = v) }
                }
                Divider()
                Toggle("Screen recording and hotspot", "A red dot and the time while recording; the hotspot while it's on", s.showSystem) { v -> set { it.copy(showSystem = v) } }
            }
            Spacer(Modifier.height(20.dp))

            Header("Feel")
            Card {
                Toggle("Two at once", "A second live thing waits in a bubble beside the island", s.split) { v -> set { it.copy(split = v) } }
                Divider()
                Toggle("Unlock animation", "A face that turns into an opening padlock", s.unlockGlyph) { v -> set { it.copy(unlockGlyph = v) } }
                Divider()
                Toggle("Haptics", null, s.haptics) { v -> set { it.copy(haptics = v) } }
                Divider()
                Toggle(
                    "Bars follow the music", "Uses Android's audio visualiser (asks for the microphone permission; the mic is never opened)",
                    audio,
                ) { v -> if (v) askAudio.launch(Manifest.permission.RECORD_AUDIO) else open(appDetails()) }
                Divider()
                SpeedRow(s.speed) { v -> set { it.copy(speed = v) } }
            }
            Spacer(Modifier.height(20.dp))

            Card {
                Row(
                    Modifier.fillMaxWidth().pressable(scaleDown = 0.98f) { BackgroundNotice.openChannelSettings(this@MainActivity) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column {
                        Text("Hide the \"Notch is running\" notification", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("Turn its channel off; the island keeps working", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        passStayAwake(intent)
    }

    /** Test builds: `adb shell am start -n com.meetdheeran.notch/.MainActivity --ez stayAwake true` (false to stop). */
    private fun passStayAwake(i: Intent?) {
        if (i?.hasExtra(IslandService.EXTRA_STAY_AWAKE) != true || !BuildConfig.DEBUG) return
        val on = i.getBooleanExtra(IslandService.EXTRA_STAY_AWAKE, false)
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java).putExtra(IslandService.EXTRA_STAY_AWAKE, on))
    }

    private fun accessibilityOn(): Boolean {
        val me = ComponentName(this, NotchAccessibility::class.java).flattenToString()
        val on = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return on.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun appDetails() = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun open(i: Intent) { runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

@Composable
private fun Header(text: String) {
    Text(
        text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, bottom = 8.dp),
    )
}

/** An OxygenOS-style group: one rounded card, rows inside. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Oos.CARD_RADIUS.dp)).background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 18.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)

@Composable
private fun Toggle(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.99f) { onChange(!on) }.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Oos.accent, checkedThumbColor = Color.White, checkedBorderColor = Oos.accent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/** A setup step: tap to go to the system page that switches it on. */
@Composable
private fun Step(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Oos.accent))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpeedRow(speed: Float, onChange: (Float) -> Unit) {
    var v by remember(speed) { mutableFloatStateOf(speed) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Animation speed", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                when { v < 0.85f -> "Calm"; v > 1.2f -> "Snappy"; else -> "Normal" } + " · ${(v * 100).roundToInt()}%",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v) }, valueRange = 0.6f..1.6f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Oos.accent, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
