package com.mytravel

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytravel.data.Place
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val secondaryText = Color(0xFF5F6368)
private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val mdhm = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private class WeatherBundle(
    val now: Result<Fetched<NowWeather>>,
    val hourly: Result<Fetched<List<HourWeather>>>,
    val daily: Result<Fetched<List<DayWeather>>>,
    val minutely: Result<Fetched<Minutely>>,
    val alerts: Result<Fetched<List<WeatherAlert>>>,
)

private fun pct(p: Double?) = p?.let { "${(it * 100).roundToInt()}%" }
private fun deg(t: Double) = "${t.roundToInt()}°"

private fun dataTime(f: Fetched<*>): String {
    val t = f.fetchedAt.atZone(ZoneId.systemDefault()).format(mdhm)
    return if (f.fromCache) "离线缓存，获取于 $t（网络不可用）" else "获取于 $t"
}

@Composable
fun WeatherScreen(haze: HazeState, place: Place, repo: WeatherRepo, onClose: () -> Unit) {
    val context = LocalContext.current
    var bundle by remember(place.id) { mutableStateOf<WeatherBundle?>(null) }
    LaunchedEffect(place.id) {
        bundle = coroutineScope {
            val now = async { repo.now(place.lat, place.lng) }
            val hourly = async { repo.hourly(place.lat, place.lng) }
            val daily = async { repo.daily(place.lat, place.lng) }
            val minutely = async { repo.minutely(place.lat, place.lng) }
            val alerts = async { repo.alerts(place.lat, place.lng) }
            WeatherBundle(now.await(), hourly.await(), daily.await(), minutely.await(), alerts.await())
        }
    }
    val today = LocalDate.now()

    // The name and the planned day's forecast stay at the top; only what's below scrolls.
    // Without a planned day (no date), only the name stays.
    Column(Modifier.fillMaxSize().glass(haze, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleLarge)
                // QWeather's grid is ~1 km and we query at 2 decimals; say so instead of implying a point reading.
                Text("约 1 公里网格的格点预报，不是该地点的实测值", color = secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            CloseButton(onClose)
        }
        bundle?.let { Box(Modifier.padding(top = 10.dp)) { PlannedDay(place, it.daily, today) } }
        LazyColumn(
            Modifier.weight(1f).padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val b = bundle
            if (b == null) {
                item { Text("正在获取天气…", color = secondaryText) }
                return@LazyColumn
            }

            b.alerts.getOrNull()?.let { f ->
                items(f.value) { a ->
                    val tint = when (a.colorCode) {
                        "red" -> Color(0xFFD32F2F); "orange", "amber" -> Color(0xFFF57C00)
                        "yellow" -> Color(0xFFF9A825); "blue" -> Color(0xFF1E5BCD); else -> Color(0xFF616161)
                    }
                    Column(Modifier.fillMaxWidth().background(tint.copy(alpha = 0.15f), RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Text("官方预警：${a.headline}", color = tint, fontWeight = FontWeight.Bold)
                        if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall)
                        a.expire?.let { Text("有效至 ${it.format(mdhm)}", color = secondaryText, style = MaterialTheme.typography.bodySmall) }
                        f.attributions.filterNot { it.startsWith("http") }.forEach {
                            Text(it, color = secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                b.now.fold(
                    onSuccess = { f ->
                        val n = f.value
                        Column {
                            Text("当前天气", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${weatherGlyph(n.code)} ${deg(n.temp)}", fontSize = 44.sp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(n.text, style = MaterialTheme.typography.titleMedium)
                                    Text("体感 ${deg(n.feelsLike)}")
                                }
                            }
                            Text(
                                "湿度 ${pct(n.humidity)} · ${n.windDir}风 ${n.windScale} 级 · 近 1 小时降水 ${n.precip1h} mm",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val valid = n.validTime?.atZoneSameInstant(ZoneId.systemDefault())?.format(hm)
                            Text(listOfNotNull(valid?.let { "实况时间 $it" }, dataTime(f)).joinToString(" · "), color = secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onFailure = { Text("当前天气获取失败：${it.message}", color = secondaryText) },
                )
            }

            item {
                Column {
                    Text("未来 2 小时降水", style = MaterialTheme.typography.labelLarge)
                    b.minutely.fold(
                        onSuccess = { f ->
                            Text(f.value.summary)
                            MinutelyBars(f.value)
                            Text("预报发布于 ${f.value.updateTime.format(hm)} · ${dataTime(f)}", color = secondaryText, style = MaterialTheme.typography.bodySmall)
                        },
                        onFailure = { Text("短时降水获取失败：${it.message}（该数据仅覆盖中国地区）", color = secondaryText) },
                    )
                }
            }

            item {
                Column {
                    Text("逐小时（24 小时）", style = MaterialTheme.typography.labelLarge)
                    b.hourly.fold(
                        onSuccess = { f ->
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(f.value) { h ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${h.time.hour}时", style = MaterialTheme.typography.bodySmall)
                                        // Real responses use code 100 for clear nights too; 18:00–06:00 is a rough night window.
                                        Text(weatherGlyph(h.code, night = h.time.hour !in 6..17), fontSize = 20.sp)
                                        Text(deg(h.temp))
                                        Text(pct(h.pop)?.takeIf { h.pop!! > 0 } ?: " ", color = Color(0xFF1E5BCD), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text(dataTime(f), color = secondaryText, style = MaterialTheme.typography.bodySmall)
                        },
                        onFailure = { Text("逐小时预报获取失败：${it.message}", color = secondaryText) },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.daily.fold(
                        onSuccess = { f ->
                            Text("未来 ${f.value.size} 天", style = MaterialTheme.typography.labelLarge)
                            f.value.forEach { d -> DayRow(d, today, highlight = d.date.toString() == place.date) }
                            Text(dataTime(f), color = secondaryText, style = MaterialTheme.typography.bodySmall)
                        },
                        onFailure = { Text("多日预报获取失败：${it.message}", color = secondaryText) },
                    )
                }
            }

            item {
                HorizontalDivider()
                Text(
                    "天气服务由和风天气驱动",
                    color = Color(0xFF1E5BCD),
                    modifier = Modifier.padding(vertical = 12.dp).clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qweather.com")))
                    },
                )
            }
        }
    }
}

@Composable
private fun PlannedDay(place: Place, daily: Result<Fetched<List<DayWeather>>>, today: LocalDate) {
    val date = place.date?.let(LocalDate::parse) ?: return
    val label = "计划日 ${formatDate(place.date)}"
    val days = daily.getOrNull()?.value
    val day = days?.find { it.date == date }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        when {
            date < today -> Text("计划日期已过，不显示预报")
            day != null -> {
                Text("白天 ${weatherGlyph(day.dayCode)} ${day.dayText} · 夜间 ${weatherGlyph(day.nightCode, night = true)} ${day.nightText}")
                Text("${deg(day.tMin)} ~ ${deg(day.tMax)} · 降水概率 ${pct(day.pop) ?: "—"} · 降水量 ${"%.1f".format(day.precip)} mm")
            }
            days == null -> Text("多日预报暂不可用", color = secondaryText)
            // Never stand in current weather or climate averages for a date the forecast doesn't cover.
            else -> Text("超出预报范围（目前最远到 ${formatDate(days.last().date.toString())}），暂未提供", color = secondaryText)
        }
    }
}

@Composable
private fun DayRow(d: DayWeather, today: LocalDate, highlight: Boolean) {
    val name = when (d.date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> formatDate(d.date.toString())
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (highlight) Color(0x331E5BCD) else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.width(96.dp))
        Text("${weatherGlyph(d.dayCode)}${weatherGlyph(d.nightCode, night = true)}", Modifier.width(56.dp))
        Text(d.dayText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(pct(d.pop)?.takeIf { d.pop!! > 0 } ?: "", color = Color(0xFF1E5BCD), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
        Text("${deg(d.tMin)} / ${deg(d.tMax)}")
    }
}

@Composable
private fun MinutelyBars(m: Minutely) {
    val maxMm = maxOf(m.precip.maxOfOrNull { it.second } ?: 0.0, 0.5)
    Canvas(Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp)) {
        val n = m.precip.size.coerceAtLeast(1)
        val w = size.width / n
        m.precip.forEachIndexed { i, (_, mm) ->
            val h = (mm / maxMm).toFloat() * size.height
            drawRect(Color(0xFF1E5BCD), Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h.coerceAtLeast(1f)))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(m.precip.firstOrNull()?.first?.format(hm) ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(m.precip.lastOrNull()?.first?.format(hm) ?: "", style = MaterialTheme.typography.bodySmall)
    }
}
