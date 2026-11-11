@file:OptIn(ExperimentalMaterial3Api::class)

package com.mytravel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mytravel.data.Place
import com.mytravel.data.Trip
import java.time.LocalDate

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** "11月7日 周六" for an ISO date. */
fun formatDate(iso: String): String {
    val d = LocalDate.parse(iso)
    return "${d.monthValue}月${d.dayOfMonth}日 ${weekdays[d.dayOfWeek.value - 1]}"
}

fun tripDates(trip: Trip): String? = when {
    trip.startDate != null && trip.endDate != null -> "${formatDate(trip.startDate)} – ${formatDate(trip.endDate)}"
    trip.startDate != null -> "${formatDate(trip.startDate)} 起"
    else -> null
}

@Composable
fun PlaceEditDialog(place: Place, trip: Trip?, onDismiss: () -> Unit, onSave: (Place) -> Unit) {
    var name by remember { mutableStateOf(place.name) }
    var date by remember { mutableStateOf(place.date) }
    var time by remember { mutableStateOf(place.time) }
    var stay by remember { mutableStateOf(place.stayMinutes?.toString() ?: "") }
    var note by remember { mutableStateOf(place.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑地点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                DateTimeField(
                    "选择日期和到达时间", DateTimeValue(date, time, null, null), range = false, withTime = true,
                    labels = "到达" to "", anchor = trip?.startDate,
                ) { date = it.start; time = it.startTime }
                OutlinedTextField(
                    stay, { v -> stay = v.filter(Char::isDigit).take(4) },
                    label = { Text("停留时长（分钟）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(note, { note = it }, label = { Text("备注") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        place.copy(
                            name = name.trim(), date = date, time = time,
                            stayMinutes = stay.toIntOrNull(), note = note.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TripEditDialog(trip: Trip?, onDismiss: () -> Unit, onSave: (Trip) -> Unit) {
    var name by remember { mutableStateOf(trip?.name ?: "") }
    var start by remember { mutableStateOf(trip?.startDate) }
    var end by remember { mutableStateOf(trip?.endDate) }
    val invalidRange = start != null && end != null && end!! < start!!
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (trip == null) "新建旅行" else "编辑旅行") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("旅行名称") }, singleLine = true)
                DateTimeField("选择旅行日期", DateTimeValue(start, null, end, null), range = true, withTime = false) {
                    start = it.start
                    end = it.end
                }
                if (invalidRange) Text("结束日期早于开始日期", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !invalidRange, onClick = {
                onSave((trip ?: Trip(name = "")).copy(name = name.trim(), startDate = start, endDate = end))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TripPickerDialog(
    trips: List<Trip>,
    currentId: Long?,
    onSelect: (Trip) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的旅行") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(trips, key = { it.id }) { trip ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(trip) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = trip.id == currentId, onClick = { onSelect(trip) })
                            Column {
                                Text(trip.name)
                                tripDates(trip)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row {
                    TextButton(onClick = onCreate) { Text("新建") }
                    if (currentId != null) {
                        TextButton(onClick = onEdit) { Text("编辑当前") }
                        TextButton(onClick = { confirmDelete = true }) { Text("删除当前", color = Color(0xFFC62828)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    if (confirmDelete) {
        ConfirmDialog("删除这次旅行？", "旅行中的所有地点也会一起删除，无法恢复。", onConfirm = {
            confirmDelete = false
            onDelete()
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = Color(0xFFC62828)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
