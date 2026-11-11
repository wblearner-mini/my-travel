package com.mytravel

import android.content.Context
import com.mytravel.data.AppDb
import com.mytravel.data.Place
import com.mytravel.data.Trip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** One stop of the sample trip: day offset from today, arrival time, minutes of stay. */
private class DemoStop(val name: String, val day: Long, val time: String, val stay: Int)

private val demoStops = listOf(
    DemoStop("天安门广场", 0, "08:30", 60),
    DemoStop("故宫博物院", 0, "10:00", 180),
    DemoStop("景山公园", 0, "14:00", 60),
    DemoStop("南锣鼓巷", 0, "16:00", 90),
    DemoStop("天坛公园", 1, "09:00", 120),
    DemoStop("前门大街", 1, "12:30", 90),
    DemoStop("北海公园", 1, "15:30", 90),
    DemoStop("颐和园", 2, "09:30", 240),
)

/**
 * Screen-recording builds only (a [demoPosition] is set): keeps a clearly named sample trip in Beijing,
 * so recordings show no personal trip. Places are real AMap POIs looked up by name, so their cards show
 * real details. Runs at every start and only adds what is missing (a lookup may hit AMap's per-second
 * limit; the next start fills it in).
 */
suspend fun seedDemoTrip(context: Context) {
    val at = demoPosition ?: return
    val dao = AppDb.get(context).dao()
    val today = LocalDate.now()
    val trip = dao.trips().first().find { it.name == DEMO_TRIP }
    val tripId = trip?.id ?: dao.insert(Trip(name = DEMO_TRIP, startDate = today.toString(), endDate = today.plusDays(2).toString()))
    val have = dao.places(tripId).first().map { it.name }.toSet()
    demoStops.forEachIndexed { i, s ->
        if (s.name in have) return@forEachIndexed
        var hits = emptyList<SearchHit>()
        repeat(2) { if (hits.isEmpty()) { delay(600); hits = searchPoi(context, s.name, "北京", at.latitude, at.longitude).getOrNull().orEmpty() } }
        val hit = hits.firstOrNull { it.title == s.name } ?: hits.firstOrNull { s.name in it.title } ?: return@forEachIndexed
        dao.insert(
            Place(
                tripId = tripId, name = s.name, address = hit.address, lat = hit.lat, lng = hit.lng, poiId = hit.poiId,
                date = today.plusDays(s.day).toString(), time = s.time, stayMinutes = s.stay, sortOrder = i + 1,
            ),
        )
    }
}

private const val DEMO_TRIP = "北京三日游（示例）"
