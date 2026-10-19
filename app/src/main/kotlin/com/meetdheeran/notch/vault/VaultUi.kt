package com.meetdheeran.notch.vault

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.meetdheeran.notch.core.Haptics
import com.meetdheeran.notch.shizuku.ShizukuState
import com.meetdheeran.notch.ui.Motion
import com.meetdheeran.notch.ui.Oos
import com.meetdheeran.notch.ui.OosColors
import com.meetdheeran.notch.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app that can be hidden, from the picker. */
class PickedApp(val pkg: String, val label: String, val icon: Drawable?)

/** Everything there is to hide: launchable, not part of the system, not Notch or Shizuku, not hidden already. */
fun installedApps(ctx: Context): List<PickedApp> {
    val pm = ctx.packageManager
    val hidden = VaultStore.apps.value.map { it.pkg }.toSet()
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .filter { it.packageName !in NEVER && it.packageName !in hidden }
        .distinctBy { it.packageName }
        .map { PickedApp(it.packageName, pm.getApplicationLabel(it).toString(), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private val NEVER = setOf("com.meetdheeran.notch", "moe.shizuku.privileged.api")

/**
 * The hidden apps, as an OxygenOS-style sheet at the bottom of the screen: tap one to bring it back and open it (it's
 * hidden again when you leave it), hold one to unhide it for good, "+" to hide another.
 */
@Composable
fun VaultGrid(
    apps: List<HiddenApp>, busy: String?, error: String?, shizuku: ShizukuState,
    onOpen: (HiddenApp) -> Unit, onAdd: () -> Unit, onUnhide: (HiddenApp) -> Unit, onShizuku: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<HiddenApp?>(null) }
    Sheet(onClose) {
        Title("Hidden apps", if (apps.isEmpty()) "Nothing hidden yet" else "Tap to open · hold to unhide")
        if (shizuku != ShizukuState.READY) Notice("Start Shizuku to open or hide apps ›", Oos.warm, onShizuku)
        if (error != null) Notice(error, Oos.bad, null)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 380.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.pkg }) { app ->
                val icon = remember(app.pkg) { VaultStore.icon(ctx, app.pkg) }
                AppTile(app.label, icon, dim = busy != null && busy != app.pkg, onClick = { onOpen(app) }, onHold = { confirm = app })
            }
            item { AddTile(onAdd) }
        }
        val c = confirm
        if (c != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(Modifier.padding(horizontal = 22.dp), thickness = 0.5.dp, color = Color(0xFF2C2C2E))
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Unhide ${c.label}? It goes back to the launcher.", fontSize = 14.sp, lineHeight = 18.sp, color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Pill("Keep", filled = false) { confirm = null }
                Spacer(Modifier.width(8.dp))
                Pill("Unhide", filled = true) { confirm = null; onUnhide(c) }
            }
        }
    }
}

/** Picking an app to hide: every app that can be, by name. */
@Composable
fun AppPicker(busy: String?, error: String?, onPick: (PickedApp) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<PickedApp>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { installedApps(ctx) } }
    BackHandler(onBack = onBack)
    Sheet(onClose = onBack) {
        Row(Modifier.padding(start = 10.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, color = Color.White, modifier = Modifier.pressable(scaleDown = 0.9f, onClick = onBack).padding(horizontal = 12.dp))
            Column {
                Text("Hide an app", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(
                    "It leaves the launcher and the app drawer and gets no notifications until you open it from here.",
                    fontSize = 13.sp, lineHeight = 17.sp, color = Oos.secondary, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (error != null) Notice(error, Oos.bad, null)
        Spacer(Modifier.height(10.dp))
        val list = apps
        if (list == null) {
            Text("Looking…", fontSize = 14.sp, color = Oos.secondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
        } else if (list.isEmpty()) {
            Text("Nothing left to hide", fontSize = 14.sp, color = Oos.secondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(list, key = { it.pkg }) { app ->
                val bmp = remember(app.pkg) { app.icon?.toBitmap(96, 96) }
                Row(
                    Modifier.fillMaxWidth().alpha(if (busy != null && busy != app.pkg) 0.4f else 1f)
                        .pressable(scaleDown = 0.99f) { onPick(app) }.padding(horizontal = 22.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(bmp, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(app.label, fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------ pieces

/** A sheet at the bottom of the screen over a dimmed everything-else; a tap on the dim closes it. */
@Composable
private fun Sheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = fadeIn(Motion.fade(180)) + slideInVertically(Motion.panel()) { it / 4 }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(Oos.CARD_RADIUS.dp)).background(OosColors.darkCard)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(top = 22.dp, bottom = 16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(horizontal = 22.dp))
    Text(subtitle, fontSize = 13.sp, color = Oos.secondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
}

@Composable
private fun Notice(text: String, color: Color, onClick: (() -> Unit)?) {
    Text(
        text, fontSize = 13.sp, lineHeight = 17.sp, color = color,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp).then(if (onClick != null) Modifier.pressable(scaleDown = 0.98f, onClick = onClick) else Modifier),
    )
}

@Composable
private fun Icon(bitmap: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(Color(0xFF2C2C2E))) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(label: String, icon: Bitmap?, dim: Boolean, onClick: () -> Unit, onHold: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().alpha(if (dim) 0.4f else 1f).clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { Haptics.tick(ctx); onClick() }, onLongClick = { Haptics.click(ctx); onHold() })
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable(scaleDown = 0.95f, onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 28.sp, color = Oos.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text("Hide an app", fontSize = 12.sp, color = Oos.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Pill(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) Oos.accent else Color(0xFF2C2C2E)).pressable(scaleDown = 0.95f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White) }
}
