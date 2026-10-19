package com.meetdheeran.notch.face

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meetdheeran.notch.island.FaceScan
import com.meetdheeran.notch.ui.Oos
import com.meetdheeran.notch.ui.pressable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun cameraAllowed(ctx: Context): Boolean = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The vault's face check, with nothing of its own on screen: the front camera looks (unseen) while the island shows
 * its face glyph scanning ([island] is told what to show). No words say what it opens. Your face, really there (not a
 * photo or a screen) → [onUnlocked]. Someone else, a photo, not sure, or nothing within [LIMIT_MS] is a failed try:
 * the island shakes and, with tries left of [tries], it scans again by itself; when the last try fails, [onFailed] —
 * handed a `learn` to run once the phone's own PIN has said yes, so a near miss is learned ([FaceLearner]) when
 * [learn] is on. Back → [onCancel]. The island is left showing OK or FAIL for the caller to clear or keep.
 */
@Composable
fun FaceCheck(
    tries: Int,
    island: (FaceScan) -> Unit,
    onUnlocked: () -> Unit,
    onFailed: (learn: () -> Unit) -> Unit,
    onCancel: () -> Unit,
    learn: Boolean = true,
) {
    val ctx = LocalContext.current
    val (scanner, failed) = rememberScanner()
    val saved = remember { FaceStore.load(ctx) }
    // Each try gets its own judge and its own "done"; reads from a try that's over are ignored.
    var attempt by remember { mutableIntStateOf(1) }
    val judge = remember(attempt) { FaceJudge(saved?.threshold ?: FaceCalibration.DEFAULT) }
    val done = remember(attempt) { AtomicBoolean(false) }
    val ended = remember { AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val show by rememberUpdatedState(island)
    val unlocked by rememberUpdatedState(onUnlocked)
    val failedAll by rememberUpdatedState(onFailed)
    val current by rememberUpdatedState(scanner)
    // The reads so far, for learning: the slow thread adds to it, the PIN prompt's answer reads it.
    val reads = remember { java.util.Collections.synchronizedList(ArrayList<FaceLearner.Read>()) }

    /** After the PIN says yes: keep the closest near miss, if there was one. */
    fun learnNearMiss() {
        if (!learn || saved == null) return
        val seen = synchronized(reads) { reads.toList() }
        val print = FaceLearner.fromNearMiss(seen, saved.threshold) ?: return
        thread(name = "notch-face-learn") { FaceStore.learn(ctx, print) }
        Log.d(TAG, "learned a near miss")
    }

    fun unlock() {
        if (!done.compareAndSet(false, true)) return
        ended.set(true)
        main.post {
            show(FaceScan.OK)
            // Long enough to see the smile on the island, then in.
            main.postDelayed({ unlocked() }, 450)
        }
    }

    /** This try failed: the island shakes, then it's the face again — or, after the last try, the caller's turn. */
    fun fail() {
        if (!done.compareAndSet(false, true)) return
        val last = attempt >= tries
        if (last) ended.set(true)
        main.post {
            show(FaceScan.FAIL)
            main.postDelayed({ if (last) failedAll(::learnNearMiss) else attempt++ }, 800)
        }
    }

    DisposableEffect(attempt) {
        show(FaceScan.SCANNING)
        val timeout = Runnable { fail() }
        main.postDelayed(timeout, LIMIT_MS)
        onDispose { main.removeCallbacks(timeout) }
    }
    DisposableEffect(Unit) {
        onDispose { if (ended.compareAndSet(false, true)) show(FaceScan.OFF) }
    }
    if (failed || saved == null) {
        // No face saved (or the models couldn't load): straight to the phone's own PIN.
        DisposableEffect(Unit) { main.post { if (ended.compareAndSet(false, true)) { show(FaceScan.OFF); failedAll {} } }; onDispose { } }
        return
    }
    BackHandler(onBack = onCancel)
    FaceCamera(preview = false) { frame, time ->
        val s = current ?: return@FaceCamera
        if (done.get()) return@FaceCamera
        s.feed(frame, time) { read ->
            if (done.get()) return@feed
            val print = read.print
            val match = print?.let { FaceMath.bestMatch(it, saved.all) }
            if (print != null && match != null && read.realness != null) reads += FaceLearner.Read(print, match, read.realness, read.faces)
            val verdict = judge.feed(match, read.realness)
            Log.d(TAG, "check $attempt/$tries: match ${pct(match)} (needs ${pct(saved.threshold)}), real ${pct(read.realness)}, ${read.faces} face(s), ${read.ms} ms -> $verdict")
            when (verdict) {
                FaceJudge.State.UNLOCKED -> {
                    if (learn) {
                        FaceLearner.fromUnlock(synchronized(reads) { reads.toList() }, saved.threshold)?.let {
                            FaceStore.learn(ctx, it)
                            Log.d(TAG, "learned a new look")
                        }
                    }
                    unlock()
                }
                FaceJudge.State.NOT_YOU, FaceJudge.State.SPOOF, FaceJudge.State.UNSURE -> fail()
                FaceJudge.State.LOOKING -> Unit
            }
        }
    }
}

private const val TAG = "NotchFace"

private fun pct(v: Float?): String = v?.let { "${(it * 100).roundToInt()}%" } ?: "–"

/**
 * The shared face scanner while this is on screen: null until its models have loaded (about a second the first
 * time, at once if the knock already warmed it up); the second value says it couldn't start on this phone.
 */
@Composable
private fun rememberScanner(): Pair<FaceScanner?, Boolean> {
    val ctx = LocalContext.current
    var scanner by remember { mutableStateOf<FaceScanner?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        var gone = false
        ScannerPool.take(ctx) { s ->
            if (!gone) {
                scanner = s
                failed = s == null
            }
        }
        onDispose {
            gone = true
            ScannerPool.giveBack()
        }
    }
    return scanner to failed
}

/**
 * Keeps one [FaceScanner] loaded for a while after it's used, and loads it ahead of time when asked — on the third
 * tap of the knock, say — so the face check starts at once instead of waiting a second for its models. Main thread only.
 */
object ScannerPool {
    private const val KEEP_MS = 90_000L
    private val main = Handler(Looper.getMainLooper())
    private var scanner: FaceScanner? = null
    private var loading = false
    private var users = 0
    private val waiting = mutableListOf<(FaceScanner?) -> Unit>()
    private val letGo = Runnable {
        if (users == 0) {
            scanner?.close()
            scanner = null
        }
    }

    /** Loads the scanner now if it isn't loaded; it's let go again after a while unused. */
    fun warm(ctx: Context) {
        load(ctx.applicationContext) {
            main.removeCallbacks(letGo)
            if (users == 0) main.postDelayed(letGo, KEEP_MS)
        }
    }

    /** The scanner for a face check or setup, handed to [ready] (null: it can't start here). [giveBack] after. */
    fun take(ctx: Context, ready: (FaceScanner?) -> Unit) {
        users++
        main.removeCallbacks(letGo)
        load(ctx.applicationContext, ready)
    }

    fun giveBack() {
        users = (users - 1).coerceAtLeast(0)
        main.removeCallbacks(letGo)
        if (users == 0) main.postDelayed(letGo, KEEP_MS)
    }

    private fun load(ctx: Context, ready: (FaceScanner?) -> Unit) {
        scanner?.let { ready(it); return }
        waiting += ready
        if (loading) return
        loading = true
        thread(name = "notch-face-load") {
            val s = runCatching { FaceScanner(ctx) }.onFailure { Log.w(TAG, "face unlock couldn't start", it) }.getOrNull()
            main.post {
                loading = false
                scanner = s
                val all = waiting.toList()
                waiting.clear()
                all.forEach { it(s) }
            }
        }
    }
}

/** How long one try looks before it counts as failed. */
private const val LIMIT_MS = 8_000L

/**
 * Setting up face unlock: look straight at the camera, then turn a little to each side, chin up and chin down,
 * while the ring fills ([SetupGuide]). The prints are saved encrypted with a pass mark worked out from how alike
 * they are ([FaceCalibration]).
 */
@Composable
fun FaceSetup(onDone: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val (scanner, failed) = rememberScanner()
    val guide = remember { SetupGuide() }
    val finished = remember { AtomicBoolean(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val lastFaces = remember { intArrayOf(-1) }
    var taken by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(guide.hint) }
    var faces by remember { mutableIntStateOf(1) }
    val done by rememberUpdatedState(onDone)
    val current by rememberUpdatedState(scanner)
    BackHandler(onBack = onCancel)
    if (failed) {
        FaceScreen("Face unlock", "Face unlock couldn’t start on this phone", Ring.Alert, listOf("Close" to onCancel)) {}
        return
    }
    FaceScreen(
        title = "Set up your face",
        status = when {
            taken >= SetupGuide.TOTAL -> "Done"
            scanner == null -> "Getting ready…"
            faces == 0 -> "Look at the camera"
            faces > 1 -> "Just you, please"
            else -> hint
        },
        ring = Ring.Progress(taken, SetupGuide.TOTAL),
        buttons = listOf("Cancel" to onCancel),
    ) {
        FaceCamera { frame, time ->
            val s = current ?: return@FaceCamera
            if (finished.get()) return@FaceCamera
            val look = s.feed(frame, time) { read ->
                if (finished.get()) return@feed
                val said = guide.feed(read.print, read.pose, time)
                Log.d(TAG, "setup: ${guide.step} yaw ${"%.2f".format(read.pose.yaw)} pitch ${"%.2f".format(read.pose.pitch)} real ${pct(read.realness)} ${read.ms} ms, ${guide.prints.size} taken")
                if (guide.done && finished.compareAndSet(false, true)) {
                    val prints = guide.prints.toList()
                    val mark = FaceCalibration.threshold(prints)
                    FaceStore.save(ctx, prints, mark)
                    Log.d(TAG, "setup done: ${prints.size} prints, pass mark ${pct(mark)}")
                    main.post {
                        taken = prints.size
                        main.postDelayed({ done() }, 500)
                    }
                } else {
                    val n = guide.prints.size
                    main.post {
                        taken = n
                        if (said != null) hint = said
                    }
                }
            }
            if (look.faces != lastFaces[0]) {
                lastFaces[0] = look.faces
                main.post { faces = look.faces }
            }
        }
    }
}

/**
 * Trying face unlock without unlocking anything: it says, live, whether it knows the face in front of it and
 * whether that face is really there. Hand the phone to someone else and it says no.
 */
@Composable
fun FaceTest(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val (scanner, failed) = rememberScanner()
    val saved = remember { FaceStore.load(ctx) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val lastFaces = remember { intArrayOf(-1) }
    var verdict by remember { mutableStateOf("Look at the camera") }
    var detail by remember { mutableStateOf<String?>(null) }
    var ring by remember { mutableStateOf<Ring>(Ring.Scanning) }
    val current by rememberUpdatedState(scanner)
    BackHandler(onBack = onDone)
    if (failed || saved == null) {
        val why = if (failed) "Face unlock couldn’t start on this phone" else "Set up your face first"
        FaceScreen("Test face unlock", why, Ring.Alert, listOf("Close" to onDone)) {}
        return
    }
    FaceScreen("Test face unlock", if (scanner == null) "Getting ready…" else verdict, ring, listOf("Done" to onDone), detail) {
        FaceCamera { frame, time ->
            val s = current ?: return@FaceCamera
            val look = s.feed(frame, time) { read ->
                val match = read.print?.let { FaceMath.bestMatch(it, saved.all) }
                val real = read.realness
                val (v, r) = when {
                    match == null -> "Face the camera" to Ring.Scanning
                    real != null && real < FaceJudge.REAL -> "Looks like a photo or a screen" to Ring.Alert
                    match >= saved.threshold -> "It’s you" to Ring.Full
                    else -> "Not you" to Ring.Alert
                }
                val d = "match ${pct(match)}  ·  needs ${pct(saved.threshold)}  ·  real ${pct(real)}"
                Log.d(TAG, "test: $d, ${read.ms} ms")
                main.post {
                    verdict = v
                    ring = r
                    detail = d
                }
            }
            if (look.faces != lastFaces[0]) {
                lastFaces[0] = look.faces
                if (look.faces == 0) main.post { verdict = "Look at the camera"; ring = Ring.Scanning; detail = null }
            }
        }
    }
}

/** How the ring of dots around the camera looks. */
private sealed interface Ring {
    data object Scanning : Ring
    data object Full : Ring
    data object Alert : Ring
    data class Progress(val done: Int, val of: Int) : Ring
}

/** The setup and test screens: black, the phone's font and accent, the camera in a circle ringed with dots. */
@Composable
private fun FaceScreen(title: String, status: String, ring: Ring, buttons: List<Pair<String, () -> Unit>>, detail: String? = null, camera: @Composable () -> Unit) {
    val spin by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = TextStyle(fontFamily = Oos.font, fontSize = 15.sp, color = Oos.secondary))
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(220.dp).clip(CircleShape).background(Color(0xFF111111))) { camera() }
                Canvas(Modifier.size(280.dp)) {
                    val dots = 36
                    val r = size.minDimension / 2 - 8.dp.toPx()
                    val lead = (spin * dots).toInt()
                    for (i in 0 until dots) {
                        val a = (2 * PI * i / dots - PI / 2).toFloat()
                        val lit = when (ring) {
                            Ring.Full -> 1f
                            Ring.Alert -> 0.25f
                            is Ring.Progress -> if (i < dots * ring.done / ring.of) 1f else 0.18f
                            Ring.Scanning -> {
                                val behind = (lead - i + dots) % dots
                                if (behind < 8) 1f - behind / 8f else 0.18f
                            }
                        }
                        val color = when {
                            ring == Ring.Alert && i == 0 -> Oos.bad
                            ring is Ring.Progress && lit == 1f -> Oos.accent
                            ring == Ring.Full -> Oos.good
                            else -> Color.White.copy(alpha = lit.coerceIn(0.18f, 1f))
                        }
                        drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(center.x + r * cos(a), center.y + r * sin(a)))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                status,
                style = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Color.White, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (detail != null) {
                Spacer(Modifier.height(10.dp))
                Text(detail, style = TextStyle(fontFamily = Oos.font, fontSize = 12.sp, color = Oos.secondary))
            }
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                buttons.forEachIndexed { i, (label, action) ->
                    val shape = RoundedCornerShape(50)
                    Box(
                        Modifier
                            .clip(shape)
                            .background(if (i == 0) Oos.accent else Color.Transparent)
                            .then(if (i == 0) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), shape))
                            .pressable(scaleDown = 0.96f, onClick = action)
                            .padding(horizontal = 26.dp, vertical = 12.dp),
                    ) {
                        Text(label, style = TextStyle(fontFamily = Oos.font, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Color.White))
                    }
                }
            }
        }
    }
}

/**
 * The front camera: a live preview for you (or none — [preview] false), and every frame (turned upright, with the
 * time it was taken in ms) handed to [onFrame] on a background thread. The camera stops the moment this leaves the
 * screen.
 */
@Composable
internal fun FaceCamera(preview: Boolean = true, onFrame: (Bitmap, Long) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val frame by rememberUpdatedState(onFrame)
    val view = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val shown = if (preview) Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) } else null
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(worker) { image ->
                val time = image.imageInfo.timestamp / 1_000_000
                val upright = runCatching {
                    val bitmap = image.toBitmap()
                    val turn = image.imageInfo.rotationDegrees
                    if (turn == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
                }.getOrNull()
                image.close()
                if (upright != null) frame(upright, time)
            }
            runCatching {
                p.unbindAll()
                if (shown != null) p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, shown, analysis)
                else p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { provider?.unbindAll() }
            worker.shutdown()
        }
    }
    if (preview) AndroidView({ view }, Modifier.fillMaxSize())
}
