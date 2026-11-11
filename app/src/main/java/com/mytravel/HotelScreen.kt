package com.mytravel

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amap.api.maps.model.LatLng
import com.mytravel.data.Hotel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.time.LocalDate

private val secondary = Color(0xFF5F6368)
private val warn = Color(0xFF8A4B00)

/** What the hotel form starts from: a model extraction (with its screenshot), an existing hotel, or nothing. */
class HotelDraft(
    val image: ByteArray?, val extract: HotelExtract?, val error: String?, val existing: Hotel?,
    /** The store, when the user started from a hotel tapped on the map. */
    val poi: SearchHit? = null,
)

/** A printed date as a full ISO date, only when the screenshot shows year, month and day. */
private fun PrintedDate.iso(): String? {
    val (y, m, d) = Triple(year ?: return null, month ?: return null, day ?: return null)
    return runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
}

@Composable
fun HotelScreen(
    haze: HazeState,
    draft: HotelDraft,
    tripId: Long,
    tripStart: String?,
    tripEnd: String?,
    mapCenter: () -> LatLng,
    onSave: (Hotel) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val x = draft.extract
    val h = draft.existing
    var name by remember { mutableStateOf(h?.name ?: draft.poi?.title ?: listOfNotNull(x?.hotelName, x?.branch).joinToString("")) }
    var address by remember { mutableStateOf(h?.address ?: draft.poi?.address ?: x?.address.orEmpty()) }
    var checkIn by remember { mutableStateOf(h?.checkInDate ?: x?.checkIn?.iso()) }
    var checkOut by remember { mutableStateOf(h?.checkOutDate ?: x?.checkOut?.iso()) }
    var checkInTime by remember { mutableStateOf(h?.checkInTime ?: x?.checkInTime) }
    var checkOutTime by remember { mutableStateOf(h?.checkOutTime ?: x?.checkOutTime) }
    var roomType by remember { mutableStateOf(h?.roomType ?: x?.roomType.orEmpty()) }
    var breakfast by remember { mutableStateOf(h?.breakfast ?: x?.breakfast.orEmpty()) }
    var cancelPolicy by remember { mutableStateOf(h?.cancelPolicy ?: x?.cancelPolicy.orEmpty()) }
    var latestArrival by remember { mutableStateOf(h?.latestArrival ?: x?.latestArrival.orEmpty()) }
    var notes by remember { mutableStateOf(h?.notes ?: x?.otherNotes.orEmpty()) }
    // The store's location: an AMap POI the user picks. A model never supplies coordinates.
    var place by remember { mutableStateOf(h?.let { SearchHit(it.name, it.address, null, it.lat, it.lng, it.poiId ?: "") } ?: draft.poi) }
    var keyword by remember { mutableStateOf(name) }
    var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    val bitmap = remember(draft.image) { draft.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    var autoPicked by remember { mutableStateOf(false) }

    fun search(autoPick: Boolean = false) {
        val k = keyword.trim().ifEmpty { return }
        searchStatus = "搜索中…"
        scope.launch {
            val c = mapCenter()
            val r = searchPoi(context, k, cityCodeAt(context, c), c.latitude, c.longitude)
            hits = r.getOrDefault(emptyList()).take(8)
            searchStatus = r.fold({ if (it.isEmpty()) "没有找到“$k”，可修改关键词再搜" else null }, { "搜索失败：${it.message}" })
            // Preselect the best match so saving needs no extra step; the user can still pick another.
            if (autoPick && place == null) hits.firstOrNull()?.let {
                place = it
                autoPicked = true
            }
        }
    }

    // A new hotel with a name searches for its store right away.
    LaunchedEffect(Unit) { if (h == null && place == null && keyword.isNotBlank()) search(autoPick = true) }

    val invalidRange = checkIn != null && checkOut != null && checkOut!! <= checkIn!!
    val canSave = name.isNotBlank() && checkIn != null && checkOut != null && !invalidRange && place != null

    // Only as tall as its content (up to the space given), so no empty strip shows below the buttons.
    Column(Modifier.fillMaxWidth().imePadding().glass(haze, RoundedCornerShape(28.dp))) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (h != null) "编辑酒店" else "确认酒店信息", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("取消") }
        }
        if (bitmap != null) {
            // The original screenshot stays on screen while checking the fields below.
            Box(Modifier.fillMaxWidth().weight(0.4f).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                Image(bitmap, contentDescription = "订单截图", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(0.6f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Notices(draft, x)

            OutlinedTextField(name, { name = it }, label = { Text("酒店名称（含门店）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("订单上的地址") }, modifier = Modifier.fillMaxWidth())

            Text("入住与退房", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            YearlessHint(x?.checkIn, "入住", tripStart) { checkIn = it }
            YearlessHint(x?.checkOut, "退房", tripStart) { checkOut = it }
            DateTimeField(
                "选择入住和退房", DateTimeValue(checkIn, checkInTime, checkOut, checkOutTime), range = true, withTime = true,
                labels = "入住" to "退房", sameDayEnd = false, anchor = tripStart,
            ) {
                checkIn = it.start
                checkInTime = it.startTime
                checkOut = it.end
                checkOutTime = it.endTime
            }
            if (invalidRange) Text("退房日期必须晚于入住日期", color = MaterialTheme.colorScheme.error)
            // Not blocking: a stay can be planned before the trip's dates are final.
            val outside = listOfNotNull(checkIn, checkOut).any { d -> (tripStart != null && d < tripStart) || (tripEnd != null && d > tripEnd) }
            if (outside) Text("酒店日期不在这次旅行的日期范围内，请确认年份和日期", color = warn)

            OutlinedTextField(roomType, { roomType = it }, label = { Text("房型") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(breakfast, { breakfast = it }, label = { Text("早餐") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cancelPolicy, { cancelPolicy = it }, label = { Text("取消规则") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(latestArrival, { latestArrival = it }, label = { Text("最晚到店") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("入住注意事项") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            Text("门店位置（请在地图搜索结果中选择）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            place?.let { p ->
                Text((if (autoPicked) "已自动匹配：" else "已选：") + p.title + (p.address?.let { " · $it" } ?: ""), fontWeight = FontWeight.Medium)
                if (autoPicked) Text("按酒店名称自动选了第一个搜索结果，请核对门店；不对可在下方改选。", color = warn, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(keyword, { keyword = it }, label = { Text("搜索关键词") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { search() }) { Text("搜索") }
            }
            searchStatus?.let { Text(it, color = secondary) }
            hits.forEach { hit ->
                fun choose() {
                    place = hit
                    autoPicked = false
                }
                Row(Modifier.fillMaxWidth().clickable { choose() }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = place?.poiId == hit.poiId && place?.lat == hit.lat, onClick = { choose() })
                    Column {
                        Text(hit.title)
                        Text(listOfNotNull(hit.city, hit.address).joinToString(" · "), color = secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!canSave) {
                val missing = listOfNotNull(
                    "酒店名称".takeIf { name.isBlank() }, "入住日期".takeIf { checkIn == null },
                    "退房日期".takeIf { checkOut == null }, "门店位置".takeIf { place == null },
                )
                if (missing.isNotEmpty()) Text("保存前还需要：${missing.joinToString("、")}", color = warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(enabled = canSave, onClick = {
                    val p = place!!
                    onSave(
                        Hotel(
                            id = h?.id ?: 0, tripId = tripId, name = name.trim(), address = address.trim().ifEmpty { p.address },
                            lat = p.lat, lng = p.lng, poiId = p.poiId.ifEmpty { null },
                            checkInDate = checkIn!!, checkOutDate = checkOut!!, checkInTime = checkInTime, checkOutTime = checkOutTime,
                            roomType = roomType.trim().ifEmpty { null }, breakfast = breakfast.trim().ifEmpty { null },
                            cancelPolicy = cancelPolicy.trim().ifEmpty { null }, latestArrival = latestArrival.trim().ifEmpty { null },
                            notes = notes.trim().ifEmpty { null }, checkedOut = h?.checkedOut ?: false,
                        ),
                    )
                }) { Text("保存到行程") }
            }
        }
    }
}

/** Status of the extraction: failure, not an order, missing and uncertain fields. */
@Composable
private fun Notices(draft: HotelDraft, x: HotelExtract?) {
    val lines = buildList {
        draft.error?.let { add("识别失败：$it。可以直接手动填写。") }
        if (x != null && !x.isHotelOrder) add("这张图片看起来不是酒店订单，请检查或手动填写。")
        if (x != null && x.isHotelOrder) {
            // A date with month and day but no year is reported separately: it was shown, just incompletely.
            fun noDate(d: PrintedDate?) = d?.month == null || d.day == null
            val missing = listOfNotNull(
                "酒店名称".takeIf { x.hotelName == null }, "地址".takeIf { x.address == null },
                "入住日期".takeIf { noDate(x.checkIn) }, "退房日期".takeIf { noDate(x.checkOut) },
                "入住时间".takeIf { x.checkInTime == null }, "退房时间".takeIf { x.checkOutTime == null },
            )
            if (missing.isNotEmpty()) add("截图中没有明确给出：${missing.joinToString("、")}（已留空，请补充）")
            val yearless = listOfNotNull(
                "入住".takeIf { !noDate(x.checkIn) && x.checkIn?.year == null },
                "退房".takeIf { !noDate(x.checkOut) && x.checkOut?.year == null },
            )
            if (yearless.isNotEmpty()) add("${yearless.joinToString("、")}日期没有写年份，未自动补全，请在下方点选年份")
            if (x.uncertain.isNotEmpty()) add("识别不确定，请对照原图核对：${x.uncertain.joinToString("、")}")
            add("以上内容由 AI 从截图识别，保存前请逐项核对。")
        }
    }
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Color(0x22F57C00), RoundedCornerShape(12.dp)).padding(10.dp)) {
        lines.forEach { Text(it, color = warn, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The order shows month and day but no year: offer explicit years instead of guessing one. */
@Composable
private fun YearlessHint(d: PrintedDate?, label: String, tripStart: String?, onPick: (String) -> Unit) {
    if (d == null || d.year != null) return
    val month = d.month ?: return
    val day = d.day ?: return
    val base = tripStart?.let { LocalDate.parse(it).year } ?: LocalDate.now().year
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("截图${label}日期 ${month}月${day}日，未写年份：", color = warn, style = MaterialTheme.typography.bodySmall)
        listOf(base, base + 1).forEach { y ->
            runCatching { LocalDate.of(y, month, day) }.getOrNull()?.let { date ->
                TextButton(onClick = { onPick(date.toString()) }) { Text("${y}年") }
            }
        }
    }
}
