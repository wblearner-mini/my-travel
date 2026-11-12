package com.mytravel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.RouteSearchV2
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class TravelMode(val label: String, val amapT: Int) { Drive("驾车", 0), Walk("步行", 2) }

/** A route stop: a saved place, a hotel, or the user's current position. */
data class Stop(val name: String, val lat: Double, val lng: Double, val placeId: Long?, val isMe: Boolean = false)

/** Where a day's route starts. */
enum class RouteStart(val label: String) { Me("我的位置"), Hotel("酒店"), First("第一个地点") }

/** One real-road leg between consecutive stops, from AMap route planning. */
data class Leg(val distanceM: Float, val durationS: Long, val polyline: List<LatLng>)

private val legCache = mutableMapOf<String, Leg>()

// One route request at a time: the route panel and the assistant planned legs at the same moment and
// AMap answered CUQPS_HAS_EXCEEDED_THE_LIMIT, its per-second limit (seen on a Xiaomi 14).
private val legLock = Mutex()

private fun overQps(r: Result<Leg>) = r.exceptionOrNull()?.message?.contains("QPS") == true

/** Cache key of a leg: tied to both stops' coordinates and the travel mode, so a moved stop never shows a stale leg. */
internal fun legKey(from: Stop, to: Stop, mode: TravelMode) = "${mode.name}:${from.lat},${from.lng}>${to.lat},${to.lng}"

/** Whether AMap can take the whole day in one call: driving only, and at most [AMAP_MAX_VIAS] stops between the ends. */
internal fun canNavigateAll(mode: TravelMode, stopCount: Int) = mode == TravelMode.Drive && stopCount - 2 <= AMAP_MAX_VIAS

/** Plans one leg with AMap route planning 2.0. Never falls back to a straight line. */
suspend fun planLeg(context: Context, from: Stop, to: Stop, mode: TravelMode): Result<Leg> {
    val key = legKey(from, to, mode)
    legCache[key]?.let { return Result.success(it) }
    val r = legLock.withLock {
        legCache[key]?.let { return@withLock Result.success(it) }
        // Over the limit: wait a second and try once more; never more than that.
        requestLeg(context, from, to, mode, key).let { if (overQps(it)) { delay(1_100); requestLeg(context, from, to, mode, key) } else it }
    }
    return if (overQps(r)) Result.failure(Exception("高德路线服务繁忙，请稍后重新打开路线")) else r
}

private suspend fun requestLeg(context: Context, from: Stop, to: Stop, mode: TravelMode, key: String): Result<Leg> =
    withContext(Dispatchers.IO) {
        try {
            val ft = RouteSearchV2.FromAndTo(LatLonPoint(from.lat, from.lng), LatLonPoint(to.lat, to.lng))
            val search = RouteSearchV2(context)
            val leg = when (mode) {
                TravelMode.Drive -> {
                    val q = RouteSearchV2.DriveRouteQuery(ft, RouteSearchV2.DrivingStrategy.DEFAULT, null, null, "").apply {
                        showFields = RouteSearchV2.ShowFields.POLINE or RouteSearchV2.ShowFields.COST
                    }
                    val path = search.calculateDriveRoute(q).paths.firstOrNull() ?: throw AMapException("没有可用的驾车路线")
                    Leg(
                        distanceM = path.distance,
                        // Route planning 2.0 reports driving time in the cost block.
                        durationS = path.cost?.duration?.toLong() ?: path.duration,
                        polyline = path.steps.flatMap { it.polyline.orEmpty() }.map { LatLng(it.latitude, it.longitude) },
                    )
                }
                TravelMode.Walk -> {
                    val q = RouteSearchV2.WalkRouteQuery(ft).apply { showFields = RouteSearchV2.ShowFields.POLINE }
                    val path = search.calculateWalkRoute(q).paths.firstOrNull() ?: throw AMapException("没有可用的步行路线")
                    Leg(
                        distanceM = path.distance,
                        durationS = path.duration,
                        polyline = path.steps.flatMap { it.polyline.orEmpty() }.map { LatLng(it.latitude, it.longitude) },
                    )
                }
            }
            if (leg.polyline.size < 2) throw AMapException("路线没有返回道路坐标")
            legCache[key] = leg
            Result.success(leg)
        } catch (e: AMapException) {
            Result.failure(Exception(e.errorMessage))
        }
    }

fun formatDistance(m: Float) = if (m < 1000) "${m.roundToInt()} 米" else "%.1f 公里".format(m / 1000)
fun formatDuration(s: Long): String {
    val min = ((s + 59) / 60).toInt()
    return if (min < 60) "$min 分钟" else "${min / 60} 小时 ${min % 60} 分钟".replace(" 0 分钟", "")
}

internal const val AMAP_PACKAGE = "com.autonavi.minimap"

/**
 * Most via points we hand to AMap in one route/plan call. The URI doc states no limit; on AMap
 * 16.13 (2026-11-09) 16 vias were all used and a 17th was silently dropped.
 */
const val AMAP_MAX_VIAS = 16

/**
 * Opens AMap's route page (amapuri://route/plan/) from [from] (null = AMap uses the current
 * location) through [vias] to [to]. Coordinates are GCJ-02 already, hence dev=0.
 * Returns false when AMap isn't installed.
 */
fun openAmapRoute(context: Context, from: Stop?, vias: List<Stop>, to: Stop, mode: TravelMode): Boolean {
    fun clean(name: String) = name.replace("|", " ")
    val uri = Uri.Builder().scheme("amapuri").authority("route").appendPath("plan").appendPath("")
        .appendQueryParameter("sourceApplication", "我的旅游")
        .apply {
            if (from != null) {
                appendQueryParameter("slat", from.lat.toString())
                appendQueryParameter("slon", from.lng.toString())
                appendQueryParameter("sname", clean(from.name))
            }
            if (vias.isNotEmpty()) {
                appendQueryParameter("vian", vias.size.toString())
                appendQueryParameter("vialons", vias.joinToString("|") { it.lng.toString() })
                appendQueryParameter("vialats", vias.joinToString("|") { it.lat.toString() })
                appendQueryParameter("vianames", vias.joinToString("|") { clean(it.name) })
            }
        }
        .appendQueryParameter("dlat", to.lat.toString())
        .appendQueryParameter("dlon", to.lng.toString())
        .appendQueryParameter("dname", clean(to.name))
        .appendQueryParameter("dev", "0")
        .appendQueryParameter("t", mode.amapT.toString())
        .build()
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_DEFAULT).setPackage(AMAP_PACKAGE)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

fun openAmapInStore(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$AMAP_PACKAGE"))
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mobile.amap.com/")))
    }
}

private val secondaryText = Color(0xFF5F6368)
private val accent = Color(0xFF1E5BCD)

@Composable
fun RoutePanel(
    haze: HazeState,
    title: String,
    stops: List<Stop>,
    legs: List<Result<Leg>?>,
    mode: TravelMode,
    start: RouteStart,
    availableStarts: List<RouteStart>,
    endAtHotel: Boolean,
    canEndAtHotel: Boolean,
    stayMinutes: List<Int?>,
    selectedLeg: Int?,
    navLeg: Int?,
    onStart: (RouteStart) -> Unit,
    onEndAtHotel: (Boolean) -> Unit,
    onSelectLeg: (Int) -> Unit,
    onNavigateLeg: (Int) -> Unit,
    onNavigateAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glass(haze, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CloseButton(onClose)
        }
        // No drive/walk choice: routes the user opens are driving.
        if (canEndAtHotel) FilterChip(selected = endAtHotel, onClick = { onEndAtHotel(!endAtHotel) }, label = { Text("终点回酒店") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("起点", color = secondaryText, style = MaterialTheme.typography.bodySmall)
            availableStarts.forEach { st -> FilterChip(selected = st == start, onClick = { onStart(st) }, label = { Text(st.label) }) }
        }
        if (stops.size < 2) {
            Text("至少需要两个停靠点才能规划路线；只选了一个地点时可把起点设为“我的位置”。", color = secondaryText)
            return@Column
        }

        val done = legs.mapNotNull { it?.getOrNull() }
        val failed = legs.count { it?.isFailure == true }
        val loading = legs.any { it == null }
        val summary = when {
            loading -> "正在规划路线…"
            failed > 0 -> "$failed 段路线规划失败，未计入合计"
            else -> null
        }
        Text(
            "${mode.label} ${formatDistance(done.sumOf { it.distanceM.toDouble() }.toFloat())} · 约 ${formatDuration(done.sumOf { it.durationS })}（仅路上时间）",
            fontWeight = FontWeight.Medium,
        )
        summary?.let { Text(it, color = secondaryText, style = MaterialTheme.typography.bodySmall) }
        // Stay time only counts for the places being visited (not the starting point).
        val visits = stayMinutes.drop(1)
        val missing = visits.count { it == null }
        Text(
            if (missing == 0) "停留合计 ${formatDuration(visits.sumOf { it ?: 0 } * 60L)}，加上路上时间约 ${formatDuration(done.sumOf { it.durationS } + visits.sumOf { it ?: 0 } * 60L)}"
            else "有 $missing 个地点未设置停留时长，暂不能估算全天用时",
            color = secondaryText, style = MaterialTheme.typography.bodySmall,
        )

        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            itemsIndexed(legs) { i, leg ->
                val sel = i == selectedLeg
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectLeg(i) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${i + 1}. ${stops[i].name} → ${stops[i + 1].name}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (sel || i == navLeg) FontWeight.Bold else FontWeight.Normal,
                        )
                        val sub = when {
                            leg == null -> "规划中…"
                            leg.isFailure -> "规划失败：${leg.exceptionOrNull()?.message}"
                            else -> leg.getOrThrow().let { "${formatDistance(it.distanceM)} · ${formatDuration(it.durationS)}" }
                        }
                        Text(if (i == navLeg) "正在导航 · $sub" else sub, color = if (i == navLeg) accent else secondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onNavigateLeg(i) }) { Text("导航") }
                }
            }
        }
        val vias = stops.size - 2
        if (canNavigateAll(mode, stops.size)) {
            Button(onClick = onNavigateAll) { Text(if (vias > 0) "全程导航（含 $vias 个途经点）" else "导航") }
        } else {
            Text(
                if (mode == TravelMode.Walk) "步行导航按段进行：完成一段后回到这里，点下一段的“导航”。"
                else "途经点超过 $AMAP_MAX_VIAS 个，无法一次交给高德，请按段导航。",
                color = secondaryText, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
