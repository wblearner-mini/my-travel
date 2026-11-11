package com.mytravel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/** Pin colours, ARGB. */
object PinColor {
    const val PLACE = 0xFF1E5BCD.toInt()
    const val HOTEL = 0xFF7B1FA2.toInt()
    const val SELECTED = 0xFFEF6C00.toInt()
    /** Visited places and checked-out hotels: done, so they step back. */
    const val DONE = 0xFF9E9E9E.toInt()
}

/** A drawn trip pin: [pinHeight] is the head-and-tail part above the anchor; the name label hangs below. */
class TripPin(val bitmap: Bitmap, val pinHeight: Int) {
    /** Vertical anchor at the tail's tip, as a fraction of the bitmap height. */
    val anchorY get() = pinHeight.toFloat() / bitmap.height
}

/**
 * A large teardrop pin in [color] with a white ring and the white vector [icon] (a Material icon,
 * Apache 2.0) on it, plus the [name] in a white rounded label under the tip, so trip places stand
 * out from AMap's own POIs.
 */
fun tripPin(c: Context, color: Int, @DrawableRes icon: Int, name: String, selected: Boolean = false): TripPin {
    val d = c.resources.displayMetrics.density
    val head = (if (selected) 46 else 40) * d
    val tail = 10 * d
    val pinH = head + tail
    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * d
        this.color = color
        isFakeBoldText = true
    }
    val text = TextUtils.ellipsize(name, label, 120 * d, TextUtils.TruncateAt.END).toString()
    val labelPadX = 7 * d
    val labelH = 20 * d
    val labelW = if (text.isEmpty()) 0f else label.measureText(text) + labelPadX * 2
    val shadow = 3 * d
    val w = maxOf(head + shadow * 2, labelW + shadow * 2)
    val h = pinH + (if (text.isEmpty()) 0f else labelH + 3 * d) + shadow
    val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val cx = w / 2
    val r = head / 2

    // One continuous teardrop: the sides leave the circle along its tangents and meet in a rounded tip.
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = CornerPathEffect(3 * d) }
    p.color = android.graphics.Color.WHITE
    p.setShadowLayer(shadow, 0f, d, 0x66000000)
    cv.drawPath(teardrop(cx, r, r, pinH - r, 90f), p)
    p.clearShadowLayer()
    // Coloured body inside a white outline of even width.
    val inset = 2.5f * d
    p.color = color
    cv.drawPath(teardrop(cx, r, r - inset, pinH - r - inset * 1.8f, 90f), p)
    p.pathEffect = null
    val half = r * 0.58f
    ContextCompat.getDrawable(c, icon)?.mutate()?.apply {
        setTint(android.graphics.Color.WHITE)
        setBounds((cx - half).toInt(), (r - half).toInt(), (cx + half).toInt(), (r + half).toInt())
        draw(cv)
    }

    if (text.isNotEmpty()) {
        val top = pinH + 3 * d
        val rect = RectF(cx - labelW / 2, top, cx + labelW / 2, top + labelH)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            setShadowLayer(2 * d, 0f, d / 2, 0x44000000)
        }
        cv.drawRoundRect(rect, labelH / 2, labelH / 2, bg)
        cv.drawText(text, rect.left + labelPadX, rect.centerY() - (label.descent() + label.ascent()) / 2, label)
    }
    return TripPin(bmp, pinH.toInt())
}

/**
 * A circle of radius [r] at ([cx], [cy]) drawn out into a point [tipDist] from its centre in the
 * direction [angleDeg] (0 = right, 90 = down, as in Canvas arcs). The straight sides are tangent
 * to the circle, so circle and point read as one shape; pair with a CornerPathEffect to round the tip.
 */
internal fun teardrop(cx: Float, cy: Float, r: Float, tipDist: Float, angleDeg: Float): Path {
    val t = Math.toDegrees(kotlin.math.acos((r / tipDist).toDouble())).toFloat()
    val a = Math.toRadians(angleDeg.toDouble())
    return Path().apply {
        arcTo(RectF(cx - r, cy - r, cx + r, cy + r), angleDeg + t, 360f - 2 * t, true)
        lineTo(cx + tipDist * kotlin.math.cos(a).toFloat(), cy + tipDist * kotlin.math.sin(a).toFloat())
        close()
    }
}
