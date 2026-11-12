package com.mytravel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.sqrt

/** A date as printed on the order. Any part the screenshot doesn't show stays null (never guessed). */
data class PrintedDate(val year: Int?, val month: Int?, val day: Int?)

/** What the model read off a hotel order screenshot. Every field is null unless explicitly shown. */
data class HotelExtract(
    val isHotelOrder: Boolean,
    val hotelName: String?,
    val branch: String?,
    val address: String?,
    val checkIn: PrintedDate?,
    val checkOut: PrintedDate?,
    val checkInTime: String?,
    val checkOutTime: String?,
    val roomType: String?,
    val breakfast: String?,
    val cancelPolicy: String?,
    val latestArrival: String?,
    val otherNotes: String?,
    /** Field names the model marked as unclear or ambiguous. */
    val uncertain: List<String>,
)

// QWen 3.8 counts one image token per 32x32 pixels; the default cap is 2,621,440 pixels. Downscale
// to that cap ourselves so the upload is smaller and the token count predictable (~2,560 tokens).
private const val MAX_PIXELS = 2_621_440

/** Decodes the picked image, downsizes it to the model's default pixel cap and returns JPEG bytes. */
suspend fun prepareImage(context: Context, uri: Uri): ByteArray = runInterruptible(Dispatchers.IO) {
    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        val px = info.size.width.toLong() * info.size.height
        if (px > MAX_PIXELS) {
            val k = sqrt(MAX_PIXELS.toDouble() / px)
            d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        }
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }
}

/**
 * Sends one screenshot to this app's server, which asks qwen3.8-flash with a fixed prompt and a
 * strict JSON schema (server/mytravel_server/spec.py) and returns the model's JSON.
 */
suspend fun parseHotelOrder(jpeg: ByteArray): Result<HotelExtract> = try {
    val content = ServerApi.postImage("/v1/hotel/parse", jpeg).getString("content")
    Result.success(toExtract(JSONObject(content)))
} catch (e: Exception) {
    // Network failure or a reply that doesn't follow the schema: the user fills the form by hand.
    if (e !is IOException && e !is org.json.JSONException) throw e
    Result.failure(e)
}

private fun JSONObject.str(name: String) = if (isNull(name)) null else getString(name).trim().ifEmpty { null }
private fun JSONObject.int(name: String) = if (isNull(name)) null else getInt(name)

internal fun toExtract(o: JSONObject): HotelExtract {
    fun date(name: String) = if (o.isNull(name)) null else o.getJSONObject(name).let { PrintedDate(it.int("year"), it.int("month"), it.int("day")) }
    // Times are only kept when well-formed; anything else is treated as "not stated".
    fun time(name: String) = o.str(name)?.takeIf { Regex("""([01]\d|2[0-3]):[0-5]\d""").matches(it) }
    val unc = o.getJSONArray("uncertain")
    return HotelExtract(
        isHotelOrder = o.getBoolean("isHotelOrder"),
        hotelName = o.str("hotelName"), branch = o.str("branch"), address = o.str("address"),
        checkIn = date("checkIn"), checkOut = date("checkOut"),
        checkInTime = time("checkInTime"), checkOutTime = time("checkOutTime"),
        roomType = o.str("roomType"), breakfast = o.str("breakfast"), cancelPolicy = o.str("cancelPolicy"),
        latestArrival = o.str("latestArrival"), otherNotes = o.str("otherNotes"),
        uncertain = (0 until unc.length()).map { unc.getString(it) },
    )
}
