package com.mytravel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.math.max

private const val AVATAR_SOURCE_PX = 256
const val AVATAR_MARKER_DP = 44
// Long side of the photo shown for cropping: enough detail on screen, small enough in memory.
private const val CROP_SOURCE_PX = 1600

private fun avatarFile(c: Context) = File(c.filesDir, "avatar.png")

/** Reads the picked photo, scaled down, for the user to frame it in [AvatarCropper]. */
suspend fun loadForCrop(c: Context, uri: Uri): Bitmap = runInterruptible(Dispatchers.IO) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
        val k = CROP_SOURCE_PX.toFloat() / maxOf(info.size.width, info.size.height)
        if (k < 1f) d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/** Saves the square part [left, top, side] of [src] the user framed as the avatar, in app storage. */
suspend fun saveAvatar(c: Context, src: Bitmap, left: Int, top: Int, side: Int) = runInterruptible(Dispatchers.IO) {
    val square = Bitmap.createBitmap(src, left, top, side, side)
    val small = if (side > AVATAR_SOURCE_PX) Bitmap.createScaledBitmap(square, AVATAR_SOURCE_PX, AVATAR_SOURCE_PX, true) else square
    avatarFile(c).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/**
 * The marker for "my location": the user's photo in a white-ringed circle, or a plain blue
 * circle with "我" until one is picked. With [headingDeg] (screen angle, 0 = up, clockwise) a
 * the ring is drawn out into a blue-filled point the way the phone faces, like AMap's own mark.
 */
fun avatarMarkerBitmap(c: Context, sizeDp: Int = AVATAR_MARKER_DP, headingDeg: Float? = null): Bitmap {
    val d = c.resources.displayMetrics.density
    val size = (sizeDp * d).toInt()
    // With a heading the ring is pulled out into a point (one teardrop, as AMap draws it), so the
    // bitmap needs that much room on every side to keep the avatar's centre as the anchor.
    val r = size / 2f - 2 * d
    val tip = r * 1.5f
    val full = if (headingDeg != null) (2 * (tip + 3 * d)).toInt() else size
    val ring = 3 * d
    val out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val cx = full / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (headingDeg != null) {
        canvas.save()
        canvas.rotate(headingDeg, cx, cx)
        paint.pathEffect = CornerPathEffect(4 * d)
        paint.color = Color.WHITE
        paint.setShadowLayer(2 * d, 0f, d, 0x55000000)
        canvas.drawPath(teardrop(cx, cx, r, tip, -90f), paint)
        paint.clearShadowLayer()
        paint.color = 0xFF2F6BFF.toInt()
        canvas.drawPath(teardrop(cx, cx, r - ring, tip - ring * 1.8f, -90f), paint)
        paint.pathEffect = null
        canvas.restore()
    } else {
        paint.color = Color.WHITE
        paint.setShadowLayer(2 * d, 0f, d, 0x55000000)
        canvas.drawCircle(cx, cx, r, paint)
        paint.clearShadowLayer()
    }
    // The photo sits in a white disc a little larger than the blue core, so no blue edge
    // shows around the circle; the blue shows only in the point.
    paint.color = Color.WHITE
    canvas.drawCircle(cx, cx, r - ring + d, paint)
    val inner = r - ring - 1.5f * d
    val photo = avatarPhoto(c)
    if (photo != null) {
        val scale = 2 * inner / max(photo.width, photo.height)
        paint.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(cx - inner, cx - inner) })
        }
        canvas.drawCircle(cx, cx, inner, paint)
        paint.shader = null
    } else {
        paint.color = 0xFF1E5BCD.toInt()
        canvas.drawCircle(cx, cx, inner, paint)
        paint.color = Color.WHITE
        paint.textSize = inner
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("我", cx, cx - (paint.descent() + paint.ascent()) / 2, paint)
    }
    return out
}

// The marker is redrawn as the heading changes, so the photo is decoded once per saved version.
private var photoCache: Pair<Long, Bitmap?>? = null

private fun avatarPhoto(c: Context): Bitmap? {
    val f = avatarFile(c)
    val stamp = if (f.exists()) f.lastModified() else -1L
    photoCache?.takeIf { it.first == stamp }?.let { return it.second }
    return (if (stamp < 0) null else BitmapFactory.decodeFile(f.path)).also { photoCache = stamp to it }
}
