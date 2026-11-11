package com.mytravel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amap.api.services.core.AMapException
import com.amap.api.services.poisearch.PoiSearchV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What AMap tells about one POI, for showing on a card. Never stored: AMap's terms forbid storing or
 * caching its data (【官方文档】2026-11-11, lbs.amap.com/pages/terms/ §3.5, §4.12.3), so it is fetched
 * each time a card opens and lives only as long as the card.
 */
data class PoiInfo(
    val type: String?,
    val typeCode: String?,
    val address: String?,
    val tel: List<String>,
    val openToday: String?,
    val openWeek: String?,
    val rating: String?,
    val tags: List<String>,
    val photos: List<String>,
)

private val secondary = Color(0xFF5F6368)

private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() && it != "[]" && it != "null" }

/** One AMap ID search with business and photo fields; null when AMap can't be reached or knows no such POI. */
internal suspend fun poiInfo(context: Context, poiId: String): PoiInfo? = withContext(Dispatchers.IO) {
    try {
        // A null query makes the SDK fail with error 1900, so an empty one is passed.
        val q = PoiSearchV2.Query("", "").apply {
            showFields = PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.BUSINESS or PoiSearchV2.ShowFields.PHOTOS)
        }
        val p = PoiSearchV2(context, q).searchPOIId(poiId) ?: return@withContext null
        val b = p.business
        PoiInfo(
            type = p.typeDes.clean()?.split(';')?.lastOrNull { it.isNotBlank() },
            typeCode = p.typeCode.clean(),
            address = p.snippet.clean(),
            tel = b?.tel.clean()?.split(';', '；', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            openToday = b?.opentimeToday.clean(),
            openWeek = b?.opentimeWeek.clean(),
            rating = b?.getmRating().clean(),
            tags = b?.tag.clean()?.split(',', ';', '，')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4).orEmpty(),
            photos = p.photos.orEmpty().mapNotNull { it.url.clean() }.take(3),
        )
    } catch (e: AMapException) {
        null
    }
}

/** Opens this POI's own page in the AMap app (reviews, photos, deals). 【官方文档】2026-11-11, lbs.amap.com/api/amap-mobile/guide/android/search */
fun openAmapPoi(context: Context, name: String, lat: Double, lng: Double, poiId: String) {
    val uri = Uri.Builder().scheme("amapuri").authority("poi").appendPath("detail")
        .appendQueryParameter("sourceApplication", "我的旅游")
        .appendQueryParameter("poiname", name).appendQueryParameter("lat", lat.toString())
        .appendQueryParameter("lon", lng.toString()).appendQueryParameter("poiid", poiId).build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_DEFAULT).setPackage(AMAP_PACKAGE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有安装高德地图", Toast.LENGTH_SHORT).show()
    }
}

/** A photo for display only, downsized to about [maxWidth] px; nothing is written to disk. */
private suspend fun loadPhoto(url: String, maxWidth: Int): Bitmap? = runInterruptible(Dispatchers.IO) {
    val conn = URL(url.replaceFirst("http://", "https://")).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: IOException) {
        null
    } finally {
        conn.disconnect()
    }
}

private val link = Color(0xFF1E5BCD)

/**
 * The business part of a card: category and tags; today's and the week's hours; phone (opens the
 * dialer, nothing is called); up to three photos (tap to view full screen); and a link to the place's AMap page. Rows AMap has
 * no data for are left out.
 */
@Composable
fun PoiInfoSection(info: PoiInfo?, loading: Boolean, name: String, lat: Double, lng: Double, poiId: String?) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The rating sits at the top of the card (RatingStars), so this line has category and tags only.
        info?.let { i ->
            listOfNotNull(i.type).plus(i.tags).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), color = link, style = MaterialTheme.typography.bodyMedium)
            }
        }
        when {
            info == null && loading -> Text("正在获取商家信息…", color = secondary, style = MaterialTheme.typography.bodySmall)
            info != null -> {
                info.openToday?.let { today ->
                    Text("🕒 今日 $today", style = MaterialTheme.typography.bodyMedium)
                    info.openWeek?.takeIf { it != today }?.let { Text("　 $it", color = secondary, style = MaterialTheme.typography.bodySmall) }
                } ?: info.openWeek?.let { Text("🕒 $it", style = MaterialTheme.typography.bodyMedium) }
                if (info.tel.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📞", style = MaterialTheme.typography.bodyMedium)
                    info.tel.forEach { t ->
                        // ACTION_DIAL only fills in the number; the user presses call (no phone permission).
                        Text(t, color = link, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + t.filter { it.isDigit() || it == '-' || it == '+' })))
                        })
                    }
                }
                if (info.photos.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    info.photos.forEachIndexed { index, url ->
                        val bmp by produceState<Bitmap?>(null, url) { value = loadPhoto(url, 320) }
                        bmp?.let {
                            Image(
                                it.asImageBitmap(), "地点照片", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).clickable { viewing = index },
                            )
                        }
                    }
                }
            }
        }
        if (poiId != null && !loading) {
            Text("更多详情，请跳转至高德地图 ↗", color = link, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { openAmapPoi(context, name, lat, lng, poiId) }.padding(top = 2.dp, bottom = 2.dp))
        }
    }
    val photos = info?.photos.orEmpty()
    viewing?.let { start -> PhotoViewer(photos, start) { viewing = null } }
}

/** Full-screen photos: swipe between them, tap or 关闭 to go back. Loaded larger, still only in memory. */
@Composable
private fun PhotoViewer(photos: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = start) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val bmp by produceState<Bitmap?>(null, photos[page]) { value = loadPhoto(photos[page], 1600) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it.asImageBitmap(), "地点照片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        ?: Text("正在加载…", color = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, modifier = Modifier.weight(1f))
                CloseButton(onClose, tint = Color.White)
            }
        }
    }
}

/** Fetches a saved place's or hotel's POI info when its card opens: (loading, info). Never stored. */
@Composable
fun rememberPoiInfo(poiId: String?): Pair<Boolean, PoiInfo?> {
    if (poiId.isNullOrEmpty()) return false to null
    val context = LocalContext.current
    val state by produceState<Pair<Boolean, PoiInfo?>>(true to null, poiId) { value = false to poiInfo(context, poiId) }
    return state
}

/**
 * AMap's rating as five stars, each filled by its share of the score (4.7 → four full, one 70 %),
 * then the number. Nothing is shown when AMap has no rating or it isn't a number.
 */
@Composable
fun RatingStars(rating: String?) {
    val score = rating?.toFloatOrNull()?.coerceIn(0f, 5f) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val fill = (score - i).coerceIn(0f, 1f)
            Box {
                Text("★", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.titleMedium)
                Text(
                    "★", color = Color(0xFFFFB300), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.drawWithContent { clipRect(right = size.width * fill) { this@drawWithContent.drawContent() } },
                )
            }
        }
        Text(" ${"%.1f".format(score)}", color = Color(0xFFE65100), style = MaterialTheme.typography.bodyMedium)
    }
}
