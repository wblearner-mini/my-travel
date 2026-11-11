package com.mytravel

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

private val secondary = Color(0xFF5F6368)

/** The usual "locate me" crosshair: a ring with four ticks and a filled centre. */
@Composable
fun LocateIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF202124)) {
    Canvas(modifier.size(24.dp)) {
        val c = center
        val r = size.minDimension * 0.30f
        val w = size.minDimension * 0.09f
        drawCircle(color, r, c, style = Stroke(w))
        drawCircle(color, r * 0.38f, c)
        val tick = size.minDimension * 0.17f
        listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach { d ->
            drawLine(color, c + d * r, c + d * (r + tick), w)
        }
    }
}

/** A compass needle, red half to north, for a map rotated by [bearing] degrees. */
@Composable
fun CompassIcon(bearing: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) {
        val c = center
        val len = size.minDimension * 0.46f
        val half = size.minDimension * 0.16f
        // The map turns by bearing, so north on screen turns the other way.
        rotate(-bearing, c) {
            drawPath(Path().apply { moveTo(c.x, c.y - len); lineTo(c.x - half, c.y); lineTo(c.x + half, c.y); close() }, Color(0xFFE53935))
            drawPath(Path().apply { moveTo(c.x, c.y + len); lineTo(c.x - half, c.y); lineTo(c.x + half, c.y); close() }, Color(0xFF9E9E9E))
        }
        drawCircle(Color.White, size.minDimension * 0.06f, c)
    }
}

/** What the "my location" card shows besides the avatar and name. */
data class ProfileStats(val trips: Int, val places: Int, val visited: Int)

/** A name-card for "me": avatar, nickname, where I am now and a few travel counts. */
@Composable
fun ProfileCard(
    haze: HazeState,
    nickname: String,
    avatarVersion: Int,
    address: String?,
    stats: ProfileStats,
    /** Current weather at my position, e.g. "☁️ 10° 阴"; null while loading or failed. */
    weather: String?,
    /** Shown instead of the address while there is none: still locating, or why not. */
    locatingText: String,
    onWeather: () -> Unit,
    onRename: (String) -> Unit,
    onChangeAvatar: () -> Unit,
    /** Ends the session on the server; trips stay on the phone. */
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val avatar = remember(avatarVersion) { avatarMarkerBitmap(context, 72).asImageBitmap() }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(nickname) }

    Column(
        Modifier.imePadding().fillMaxWidth().glass(haze, RoundedCornerShape(28.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(avatar, "头像", Modifier.size(72.dp).clickable(onClick = onChangeAvatar))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                if (editing) {
                    OutlinedTextField(draft, { draft = it.take(20) }, singleLine = true, label = { Text("昵称") })
                } else {
                    Text(nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (address != null && address != "无法获取地址") AddressLine(address, prefix = "📍 ")
                    else Text("📍 " + (address ?: locatingText), color = secondary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        (weather ?: "天气") + "　查看天气 ›", color = Color(0xFF1E5BCD), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = onWeather).padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (!editing) {
            Row(Modifier.fillMaxWidth()) {
                listOf("旅行" to stats.trips, "地点" to stats.places, "已去过" to stats.visited).forEach { (label, n) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(label, color = secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                OutlinedButton(onClick = {
                    draft.trim().takeIf { it.isNotEmpty() }?.let(onRename)
                    editing = false
                }) { Text("保存") }
                TextButton(onClick = { editing = false }) { Text("取消") }
            } else {
                OutlinedButton(onClick = onChangeAvatar) { Text("更换头像") }
                OutlinedButton(onClick = { draft = nickname; editing = true }) { Text("修改昵称") }
                TextButton(onClick = onLogout) { Text("退出登录") }
            }
        }
    }
}

/**
 * Full-screen framing of a new avatar: drag to move and pinch to zoom the photo under a fixed circle;
 * [onDone] gets the square of [photo] (in its own pixels) that the circle covers.
 */
@Composable
fun AvatarCropper(photo: Bitmap, onCancel: () -> Unit, onDone: (left: Int, top: Int, side: Int) -> Unit) {
    BackHandler(onBack = onCancel)
    val img = remember(photo) { photo.asImageBitmap() }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // Where the photo's centre sits relative to the circle's centre, in screen pixels.
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun diameter() = minOf(view.width, view.height) * 0.8f
    fun scale(z: Float) = diameter() / minOf(photo.width, photo.height) * z
    // Keeps the circle fully covered by the photo.
    fun clamp(o: Offset, z: Float): Offset {
        val s = scale(z)
        val mx = (photo.width * s - diameter()) / 2
        val my = (photo.height * s - diameter()) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            "拖动移动，双指缩放", color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.statusBarsPadding().padding(20.dp),
        )
        Box(
            // Clipped: a zoomed-in photo is larger than this area and must not spill over the buttons.
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { view = it }.pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 6f)
                    offset = clamp(offset + pan, zoom)
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (view == IntSize.Zero) return@Canvas
                val s = scale(zoom)
                val c = center + offset
                val w = photo.width * s
                val h = photo.height * s
                drawImage(
                    img, dstOffset = IntOffset((c.x - w / 2).toInt(), (c.y - h / 2).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                )
                val r = diameter() / 2
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(center, r))
                }
                drawPath(mask, Color.Black.copy(alpha = 0.6f))
                drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
            Button(onClick = {
                val s = scale(zoom)
                val d = diameter()
                val side = (d / s).toInt().coerceIn(1, minOf(photo.width, photo.height))
                // The circle's top-left corner, measured from the photo's top-left, in photo pixels.
                val left = ((photo.width * s / 2 - offset.x - d / 2) / s).toInt().coerceIn(0, photo.width - side)
                val top = ((photo.height * s / 2 - offset.y - d / 2) / s).toInt().coerceIn(0, photo.height - side)
                onDone(left, top, side)
            }) { Text("使用") }
        }
    }
}
