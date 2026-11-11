package com.mytravel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val accent = Color(0xFF1E5BCD)
private val rangeFill = Color(0xFFDCE6FA)
private val secondary = Color(0xFF5F6368)
private val weekHeads = listOf("一", "二", "三", "四", "五", "六", "日")

/** What the sheet returns: dates are ISO yyyy-MM-dd, times "HH:mm"; null = not set. */
data class DateTimeValue(val start: String?, val startTime: String?, val end: String?, val endTime: String?)

/**
 * One panel for picking dates (and optionally times): a scrolling month calendar where the first
 * tap sets the start and the second the end, with hour / minute wheels below.
 *
 * @param range false = a single date (only [DateTimeValue.start] / startTime are used).
 * @param labels names of the two ends, e.g. "开始" / "结束" or "入住" / "退房".
 * @param sameDayEnd whether the end may be the start's day (a one-day trip yes, a hotel stay no).
 */
@Composable
fun DateTimeSheet(
    title: String,
    initial: DateTimeValue,
    range: Boolean,
    withTime: Boolean,
    labels: Pair<String, String> = "开始" to "结束",
    sameDayEnd: Boolean = true,
    /** Month to show when nothing is picked yet, ISO date. */
    anchor: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (DateTimeValue) -> Unit,
) {
    var start by remember { mutableStateOf(initial.start?.let(LocalDate::parse)) }
    var end by remember { mutableStateOf(initial.end?.let(LocalDate::parse)) }
    var startTime by remember { mutableStateOf(initial.startTime) }
    var endTime by remember { mutableStateOf(initial.endTime) }
    val today = remember { LocalDate.now() }
    val focus = start ?: anchor?.let(LocalDate::parse) ?: today
    // Two years around the date in view: enough for past and planned trips.
    val firstMonth = remember { YearMonth.from(focus).minusMonths(12) }
    val months = remember { (0 until 36).map { firstMonth.plusMonths(it.toLong()) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 12)

    fun tap(d: LocalDate) {
        val s = start
        when {
            !range -> start = d
            s == null || end != null -> { start = d; end = null }
            d < s || (d == s && !sameDayEnd) -> start = d
            else -> end = d
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseButton(onDismiss)
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { start = null; end = null; startTime = null; endTime = null }) { Text("清空") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                weekHeads.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = secondary) }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(months.size) { i ->
                    MonthGrid(months[i], start, if (range) end else null, today, labels, range) { tap(it) }
                }
            }
            if (withTime) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    TimeWheel(labels.first + "时间", startTime, Modifier.weight(1f)) { startTime = it }
                    if (range) TimeWheel(labels.second + "时间", endTime, Modifier.weight(1f)) { endTime = it }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(summary(labels.first, start, if (withTime) startTime else null), style = MaterialTheme.typography.bodyMedium)
                    if (range) {
                        Text(summary(labels.second, end, if (withTime) endTime else null), style = MaterialTheme.typography.bodyMedium)
                        val s = start
                        val e = end
                        if (s != null && e != null) {
                            val n = ChronoUnit.DAYS.between(s, e)
                            Text(if (sameDayEnd) "共 ${n + 1} 天" else "共 $n 晚", color = secondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Button(onClick = {
                    onConfirm(DateTimeValue(start?.toString(), startTime, if (range) end?.toString() else null, if (range) endTime else null))
                }) { Text("确认") }
            }
        }
    }
}

private fun summary(label: String, d: LocalDate?, time: String?) =
    "$label：" + (d?.let { formatDate(it.toString()) } ?: "未选择") + (time?.let { " $it" } ?: "")

@Composable
private fun MonthGrid(
    month: YearMonth,
    start: LocalDate?,
    end: LocalDate?,
    today: LocalDate,
    labels: Pair<String, String>,
    range: Boolean,
    onTap: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        for (row in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..month.lengthOfMonth()) {
                            val d = month.atDay(day)
                            val isStart = d == start
                            val isEnd = d == end
                            val inside = start != null && end != null && d > start && d < end
                            val bg = when {
                                isStart || isEnd -> accent
                                inside -> rangeFill
                                else -> Color.Transparent
                            }
                            Column(
                                Modifier.fillMaxSize().padding(vertical = 2.dp).background(bg, RoundedCornerShape(8.dp)).clickable { onTap(d) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val tag = when {
                                    isStart && isEnd -> "${labels.first}/${labels.second.take(1)}"
                                    isStart && range -> labels.first
                                    isEnd -> labels.second
                                    else -> null
                                }
                                if (tag != null) Text(tag, fontSize = 10.sp, color = Color.White)
                                Text(
                                    if (d == today) "今" else "$day",
                                    color = if (isStart || isEnd) Color.White else Color.Black,
                                    fontWeight = if (isStart || isEnd || d == today) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val hours = listOf("--") + (0..23).map { "%02d".format(it) }
private val minutes = (0..55 step 5).map { "%02d".format(it) }

/** Hour and minute wheels; the hour's first entry "--" means no time set. */
@Composable
private fun TimeWheel(label: String, value: String?, modifier: Modifier, onChange: (String?) -> Unit) {
    val (h, m) = value?.split(":")?.map(String::toInt) ?: listOf(-1, 0)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        var hour by remember { mutableStateOf(h + 1) }
        var minute by remember { mutableStateOf((m / 5).coerceIn(0, minutes.lastIndex)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel(hours, hour) { hour = it; onChange(if (it == 0) null else "${hours[it]}:${minutes[minute]}") }
            Text(":", modifier = Modifier.padding(horizontal = 6.dp))
            Wheel(minutes, minute) { minute = it; if (hour != 0) onChange("${hours[hour]}:${minutes[it]}") }
        }
    }
}

private val rowHeight = 36.dp

/** A three-row snapping wheel; the middle row is the selection. */
@Composable
private fun Wheel(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val half = with(LocalDensity.current) { rowHeight.toPx() / 2 }
    // With one row of padding above, the first visible row sits in the middle unless scrolled past half.
    val centre by remember { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > half) 1 else 0 } }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to centre }.collect { (moving, c) -> if (!moving) onSelect(c.coerceIn(0, items.lastIndex)) }
    }
    Box(Modifier.width(56.dp).height(rowHeight * 3), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(rowHeight).background(rangeFill, RoundedCornerShape(8.dp)))
        LazyColumn(
            Modifier.fillMaxSize(), state = state, flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = rowHeight), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size) { i ->
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                    Text(items[i], fontSize = 18.sp, color = if (i == centre) Color.Black else secondary)
                }
            }
        }
    }
}

/**
 * A tappable summary of a date (or date range, optionally with times) that opens [DateTimeSheet].
 */
@Composable
fun DateTimeField(
    title: String,
    value: DateTimeValue,
    range: Boolean,
    withTime: Boolean,
    labels: Pair<String, String> = "开始" to "结束",
    sameDayEnd: Boolean = true,
    anchor: String? = null,
    onChange: (DateTimeValue) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(summary(labels.first, value.start?.let(LocalDate::parse), if (withTime) value.startTime else null))
            if (range) Text(summary(labels.second, value.end?.let(LocalDate::parse), if (withTime) value.endTime else null))
        }
        Text("选择 ›", color = accent)
    }
    if (open) {
        DateTimeSheet(title, value, range, withTime, labels, sameDayEnd, anchor, onDismiss = { open = false }) {
            onChange(it)
            open = false
        }
    }
}
