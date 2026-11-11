package com.mytravel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import com.mytravel.data.Hotel
import com.mytravel.data.Place
import com.mytravel.data.Trip
import dev.chrisbanes.haze.HazeState

private val panelShape = RoundedCornerShape(28.dp)
private val secondary = Color(0xFF5F6368)

/** A not-yet-saved location the user picked on the map or from search. */
data class Candidate(
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val poiId: String?,
    /** Only a long-pressed empty spot gets our pin; map POIs and search hits rely on AMap's own label. */
    val showPin: Boolean,
    /** Reverse geocoding still running. */
    val resolving: Boolean = false,
    /** An AMap lodging POI (type 10xxxx): offered as a hotel instead of a trip place. */
    val isHotel: Boolean = false,
    /** AMap's business details, shown on the card only (never saved); null until fetched or when none. */
    val info: PoiInfo? = null,
    val infoLoading: Boolean = false,
)

/** Puts [text] on the clipboard; Android 13+ shows its own confirmation, older versions get a toast. */
fun copyToClipboard(c: Context, text: String) {
    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("复制的内容", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show()
}

/** An address line with a "复制" button at its end. */
@Composable
fun AddressLine(address: String, prefix: String = "") {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(prefix + address, color = secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
        Text(
            "复制", color = Color(0xFF1E5BCD), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable { copyToClipboard(context, address) }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** A card laid out as [header] (name, weather, rating), [body], then [footer] (actions). */
@Composable
private fun InfoCard(
    haze: HazeState,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glass(haze, panelShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        header()
        body()
        footer()
    }
}

/** Current weather next to a card's name, at the name's size; tap for the weather sheet. */
@Composable
private fun HeaderWeather(weather: String?, onWeather: () -> Unit) {
    Text(
        weather ?: "天气", style = MaterialTheme.typography.titleLarge, color = Color(0xFF1E5BCD), maxLines = 1,
        modifier = Modifier.clickable(onClick = onWeather).padding(start = 8.dp),
    )
}

@Composable
fun CandidateCard(
    haze: HazeState,
    candidate: Candidate,
    trips: List<Trip>,
    currentTrip: Trip?,
    onNameChange: (String) -> Unit,
    /** Adds to the given trip; null = create a new trip first. */
    onAdd: (Trip?) -> Unit,
    /** Opens the hotel form for this POI in the given trip. */
    onAddHotel: (Trip) -> Unit,
    /** Opens AMap with a route from my location to this spot. */
    onNavigate: () -> Unit,
    onClose: () -> Unit,
    /** Current weather at this spot, e.g. "☁️ 8° 阴"; null while loading or failed. */
    weather: String?,
    onWeather: () -> Unit,
) {
    var target by remember(currentTrip?.id) { mutableStateOf(currentTrip) }
    var menuOpen by remember { mutableStateOf(false) }
    if (candidate.isHotel) {
        InfoCard(
            haze,
            header = {
                Text("选中的酒店（尚未保存）", color = secondary, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(candidate.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    HeaderWeather(weather, onWeather)
                    CloseButton(onClose)
                }
                RatingStars(candidate.info?.rating)
            },
            footer = {
                if (trips.isEmpty()) {
                    Text("先在“行程”里新建一次旅行，再添加酒店。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("添加到旅行：", color = secondary)
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text((target?.name ?: "请选择") + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                trips.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.name + (tripDates(t)?.let { "（$it）" } ?: "")) },
                                        onClick = {
                                            target = t
                                            menuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose) { Text("取消") }
                    OutlinedButton(onClick = onNavigate) { Text("导航") }
                    Button(onClick = { target?.let(onAddHotel) }, enabled = target != null) {
                        Text(target?.let { "添加到「${it.name}」" } ?: "添加酒店", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        ) {
            candidate.address?.let { AddressLine(it) }
            PoiInfoSection(candidate.info, candidate.infoLoading, candidate.name, candidate.lat, candidate.lng, candidate.poiId)
        }
        return
    }
    // Only this card follows the keyboard, because its name field is edited in place.
    InfoCard(
        haze, Modifier.imePadding(),
        header = {
            Text("选中的位置（尚未保存）", color = secondary, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    candidate.name, onNameChange, label = { Text("名称") }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (candidate.resolving) "正在获取地址…" else "请输入名称") },
                )
                HeaderWeather(weather, onWeather)
                CloseButton(onClose)
            }
            RatingStars(candidate.info?.rating)
        },
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("加入旅行：", color = secondary)
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text((target?.name ?: "新建旅行") + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        trips.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name + (tripDates(t)?.let { "（$it）" } ?: "")) },
                                onClick = {
                                    target = t
                                    menuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("＋ 新建旅行") }, onClick = {
                            target = null
                            menuOpen = false
                        })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose) { Text("取消") }
                OutlinedButton(onClick = onNavigate) { Text("导航") }
                Button(onClick = { onAdd(target) }, enabled = candidate.name.isNotBlank()) {
                    Text(target?.let { "添加到「${it.name}」" } ?: "新建旅行并添加", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) {
        // A long-pressed spot is named by its address already, so the address line would only repeat it.
        val addressLine = when {
            candidate.resolving -> if (candidate.showPin) null else "正在获取地址…"
            candidate.address == null -> "地址获取失败（网络或服务错误）"
            candidate.showPin -> null
            else -> candidate.address
        }
        addressLine?.let {
            if (it == candidate.address) AddressLine(it) else Text(it, color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (candidate.poiId != null) {
            PoiInfoSection(candidate.info, candidate.infoLoading, candidate.name, candidate.lat, candidate.lng, candidate.poiId)
        }
    }
}

@Composable
fun PlaceCard(
    haze: HazeState,
    place: Place,
    weather: String?,
    onWeather: () -> Unit,
    onToggleVisited: () -> Unit,
    onNavigate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val (loading, info) = rememberPoiInfo(place.poiId)
    InfoCard(
        haze,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HeaderWeather(weather, onWeather)
                CloseButton(onClose)
            }
            RatingStars(info?.rating)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNavigate) { Text("导航") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDelete) { Text("删除", color = Color(0xFFC62828)) }
            }
        },
    ) {
        place.address?.let { AddressLine(it) }
        if (!place.poiId.isNullOrEmpty()) PoiInfoSection(info, loading, place.name, place.lat, place.lng, place.poiId)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !place.visited, onClick = { if (place.visited) onToggleVisited() }, label = { Text("计划前往") })
            FilterChip(selected = place.visited, onClick = { if (!place.visited) onToggleVisited() }, label = { Text("已打卡") })
        }
        Text("日期：" + (place.date?.let(::formatDate) ?: "未安排"))
        Text("时间：" + (place.time ?: "未设置") + "　停留：" + (place.stayMinutes?.let { "$it 分钟" } ?: "未设置"))
        place.note?.let { Text("备注：$it") }
    }
}

/** How a hotel relates to one day of the trip. */
enum class HotelDay { CheckOut, Stay, CheckIn }

/** The hotel lines for [date]: checking out in the morning, staying, or checking in. */
fun hotelLines(hotels: List<Hotel>, date: String): List<Pair<Hotel, HotelDay>> = hotels.mapNotNull { h ->
    when {
        h.checkOutDate == date -> h to HotelDay.CheckOut
        h.checkInDate == date -> h to HotelDay.CheckIn
        h.checkInDate < date && date < h.checkOutDate -> h to HotelDay.Stay
        else -> null
    }
}

/**
 * Choose which places and hotels a route visits and in what order: tick them (grouped by day, in
 * time order); each ticked one joins the end of the route, and ↑ ↓ reorder it. Keys are "p<id>"
 * for places and "h<id>" for hotels.
 */
@Composable
fun RoutePickPanel(
    haze: HazeState, places: List<Place>, hotels: List<Hotel>, initial: List<String>,
    onCancel: () -> Unit, onDone: (List<String>) -> Unit,
) {
    class Pick(val key: String, val date: String?, val time: String?, val line: AnnotatedString, val name: String)
    // A hotel is listed on its check-in day (time = check-in time); it can also end a later day's route.
    val all = places.map { Pick("p${it.id}", it.date, it.time, timedLine(null, it.time, it.name), it.name) } +
        hotels.map { Pick("h${it.id}", it.checkInDate, it.checkInTime, timedLine("🏨", it.checkInTime?.let { t -> "$t 后" }, it.name), it.name) }
    var picked by remember { mutableStateOf(initial.filter { k -> all.any { it.key == k } }) }
    fun toggle(k: String) { picked = if (k in picked) picked - k else picked + k }
    val byDate = all.groupBy { it.date }
    val dates = byDate.keys.filterNotNull().sorted() + if (null in byDate) listOf(null) else emptyList()
    Column(Modifier.fillMaxWidth().glass(haze, panelShape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选择路线地点", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消") }
        }
        Text("勾选要去的地点和酒店，按勾选先后排成路线，可在下方调整顺序。", color = secondary, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            dates.forEach { date ->
                item(key = "d$date") {
                    Text(date?.let(::formatDate) ?: "未安排日期", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                }
                byDate[date].orEmpty().sortedBy { it.time ?: "99" }.forEach { p ->
                    item(key = "c${p.key}") {
                        Row(Modifier.fillMaxWidth().clickable { toggle(p.key) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(p.key in picked, { toggle(p.key) })
                            Text(p.line, maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = placeIconInline)
                        }
                    }
                }
            }
            if (picked.isNotEmpty()) {
                item(key = "order") { Text("路线顺序", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp)) }
                picked.forEachIndexed { i, k ->
                    val p = all.first { it.key == k }
                    item(key = "o$k") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. " + (if (k.startsWith("h")) "🏨 " else "") + p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            fun swap(j: Int) { picked = picked.toMutableList().also { it[i] = it[j]; it[j] = k } }
                            TextButton(onClick = { swap(i - 1) }, enabled = i > 0) { Text("↑") }
                            TextButton(onClick = { swap(i + 1) }, enabled = i < picked.lastIndex) { Text("↓") }
                        }
                    }
                }
            }
        }
        Button(onClick = { onDone(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) {
            Text(if (picked.isEmpty()) "规划路线" else "规划路线（${picked.size} 个）")
        }
    }
}

/** One row of a day in the trip panel; [untimedSlot] orders rows that have no time. */
private class DayEntry(val time: String?, val untimedSlot: Int, val place: Place? = null, val hotel: Pair<Hotel, HotelDay>? = null)

/** Icon, then the time (grey "时间未定" when unset), then the text: one layout for place and hotel rows. */
private const val PLACE_ICON = "placeIcon"

/** The trip place icon (Material "place", Apache 2.0) inside a line of text; pass to Text as inlineContent. */
private val placeIconInline = mapOf(
    PLACE_ICON to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
        Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = Color(PinColor.PLACE))
    },
)

/** [icon] is an emoji, or null for the trip place icon (needs [placeIconInline]). */
private fun timedLine(icon: String?, time: String?, text: String) = buildAnnotatedString {
    if (icon == null) appendInlineContent(PLACE_ICON, "📍") else append(icon)
    append(" ")
    if (time != null) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(time) }
    else withStyle(SpanStyle(color = secondary)) { append("时间未定") }
    append("  $text")
}

private fun hotelLineText(h: Hotel, kind: HotelDay) = when (kind) {
    HotelDay.CheckIn -> timedLine("🏨", h.checkInTime?.let { "$it 后" }, "入住 ${h.name}")
    HotelDay.Stay -> buildAnnotatedString { append("🏨 住 ${h.name}") }
    HotelDay.CheckOut -> timedLine("🏨", h.checkOutTime?.let { "$it 前" }, "退房 ${h.name}")
}

@Composable
fun TripPanel(
    haze: HazeState,
    trip: Trip?,
    places: List<Place>,
    hotels: List<Hotel>,
    onOpenTrips: () -> Unit,
    onCreateTrip: () -> Unit,
    onPick: (Place) -> Unit,
    onPickHotel: (Hotel) -> Unit,
    onMove: (Place, Place) -> Unit,
    /** Opens the route picker for choosing places and their order. */
    onRoute: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().glass(haze, panelShape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (trip == null) {
            Text("还没有旅行", style = MaterialTheme.typography.titleMedium)
            Text("长按地图或点击地图上的地点名称选点，也可以搜索后添加。", color = secondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Button(onClick = onCreateTrip) { Text("新建旅行") }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(trip.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(tripDates(trip), "${places.size} 个地点", "${hotels.size} 家酒店".takeIf { hotels.isNotEmpty() }).joinToString(" · "),
                    color = secondary, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (places.isNotEmpty() || hotels.isNotEmpty()) TextButton(onClick = onRoute) { Text("路线") }
            TextButton(onClick = onOpenTrips) { Text("切换") }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
        if (!expanded) return@Column
        if (places.isEmpty() && hotels.isEmpty()) {
            Text("长按地图或点击地图上的地点名称选点，也可以搜索后添加。", color = secondary, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        // Days come from places and from every night of each hotel stay; unscheduled places go last.
        val byDate = places.groupBy { it.date }
        val hotelDates = hotels.flatMap { h ->
            generateSequence(java.time.LocalDate.parse(h.checkInDate)) { it.plusDays(1) }
                .takeWhile { it <= java.time.LocalDate.parse(h.checkOutDate) }.map { it.toString() }.toList()
        }
        val dates: List<String?> = (byDate.keys.filterNotNull() + hotelDates).distinct().sorted() +
            if (null in byDate) listOf(null) else emptyList()
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            dates.forEach { date ->
                val dayPlaces = byDate[date].orEmpty()
                val lines = date?.let { hotelLines(hotels, it) }.orEmpty()
                item(key = "h$date") {
                    Text(
                        date?.let(::formatDate) ?: "未安排日期", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                // Rows with a time go in time order; without one, a check-out stays first and
                // places (in the user's order) and check-ins / stays go last.
                val untimed = dayPlaces.filter { it.time == null }
                val entries = buildList {
                    lines.forEach { (h, k) ->
                        val t = when (k) { HotelDay.CheckIn -> h.checkInTime; HotelDay.CheckOut -> h.checkOutTime; HotelDay.Stay -> null }
                        add(DayEntry(t, if (k == HotelDay.CheckOut) 0 else 3, hotel = h to k))
                    }
                    dayPlaces.forEach { add(DayEntry(it.time, 2, place = it)) }
                }.sortedWith(compareBy({ if (it.time != null) 1 else it.untimedSlot }, { it.time }))
                entries.forEach { e ->
                    e.hotel?.let { (h, k) -> item(key = "${k.name}${h.id}$date") { HotelRow(h, k, onPickHotel) } }
                    e.place?.let { place ->
                        item(key = place.id) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(place) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        timedLine(null, place.time, place.name + if (place.visited) " ✓" else ""),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, inlineContent = placeIconInline,
                                    )
                                    place.stayMinutes?.let { Text("停留 $it 分钟", color = secondary, style = MaterialTheme.typography.bodySmall) }
                                }
                                // A timed place is placed by its time; only untimed ones are reordered by hand.
                                val i = untimed.indexOf(place)
                                if (i >= 0 && untimed.size > 1) {
                                    TextButton(onClick = { onMove(place, untimed[i - 1]) }, enabled = i > 0) { Text("↑") }
                                    TextButton(onClick = { onMove(place, untimed[i + 1]) }, enabled = i < untimed.lastIndex) { Text("↓") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotelRow(h: Hotel, kind: HotelDay, onPick: (Hotel) -> Unit) {
    Text(
        hotelLineText(h, kind), color = Color(0xFF6A1B9A), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 6.dp),
    )
}

@Composable
fun HotelCard(
    haze: HazeState, hotel: Hotel, weather: String?, onWeather: () -> Unit, onNavigate: () -> Unit,
    onToggleCheckedOut: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit,
) {
    val (loading, info) = rememberPoiInfo(hotel.poiId)
    InfoCard(
        haze,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏨 ${hotel.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HeaderWeather(weather, onWeather)
                CloseButton(onClose)
            }
            RatingStars(info?.rating)
        },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNavigate) { Text("导航") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDelete) { Text("删除", color = Color(0xFFC62828)) }
            }
        },
    ) {
        hotel.address?.let { AddressLine(it) }
        if (!hotel.poiId.isNullOrEmpty()) PoiInfoSection(info, loading, hotel.name, hotel.lat, hotel.lng, hotel.poiId)
        Text("入住 ${formatDate(hotel.checkInDate)}" + (hotel.checkInTime?.let { " $it 后" } ?: "（时间未写明）"))
        Text("退房 ${formatDate(hotel.checkOutDate)}" + (hotel.checkOutTime?.let { " $it 前" } ?: "（时间未写明）"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !hotel.checkedOut, onClick = { if (hotel.checkedOut) onToggleCheckedOut() }, label = { Text("待入住") })
            FilterChip(selected = hotel.checkedOut, onClick = { if (!hotel.checkedOut) onToggleCheckedOut() }, label = { Text("已退房") })
        }
        listOfNotNull(
            hotel.roomType?.let { "房型：$it" }, hotel.breakfast?.let { "早餐：$it" },
            hotel.latestArrival?.let { "最晚到店：$it" }, hotel.cancelPolicy?.let { "取消规则：$it" },
            hotel.notes?.let { "注意事项：$it" },
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

enum class MainTab(val label: String, val glyph: String) { Hotels("酒店", "🏨"), Trip("行程", "🗺️"), Ai("AI 助手", "✨") }

/** WeChat-style tab bar pinned to the bottom edge, square-cornered; draws under the navigation bar. */
@Composable
fun MainTabBar(haze: HazeState, selected: MainTab?, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(haze, RectangleShape).navigationBarsPadding().padding(vertical = 6.dp),
    ) {
        MainTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(tab) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(tab.glyph, style = MaterialTheme.typography.titleMedium)
                Text(
                    tab.label, style = MaterialTheme.typography.labelMedium,
                    color = if (on) Color(0xFF1E5BCD) else secondary, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun HotelsPanel(haze: HazeState, trip: Trip?, hotels: List<Hotel>, onAdd: () -> Unit, onPick: (Hotel) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(haze, panelShape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trip?.let { "${it.name} 的酒店" } ?: "酒店", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (trip != null) TextButton(onClick = onAdd) { Text("添加酒店") }
        }
        when {
            trip == null -> Text("先在“行程”里新建一次旅行，再添加酒店。", color = secondary, style = MaterialTheme.typography.bodySmall)
            hotels.isEmpty() -> Text("还没有酒店。可以从订单截图识别，也可以手动填写。", color = secondary, style = MaterialTheme.typography.bodySmall)
            else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(hotels.size) { i ->
                    val h = hotels[i]
                    Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 8.dp)) {
                        Text("🏨 ${h.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatDate(h.checkInDate)}" + (h.checkInTime?.let { " $it" } ?: "") + " 入住 → " +
                                formatDate(h.checkOutDate) + (h.checkOutTime?.let { " $it" } ?: "") + " 退房",
                            color = secondary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
