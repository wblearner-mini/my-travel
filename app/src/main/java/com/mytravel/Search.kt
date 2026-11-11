package com.mytravel

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.poisearch.PoiSearchV2
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SearchHit(
    val title: String, val address: String?, val city: String?, val lat: Double, val lng: Double, val poiId: String,
    /** AMap category, e.g. "风景名胜;风景名胜;国家级景点"; only from keyword search. */
    val type: String? = null,
)

/** A search result row: a real POI, or a plain keyword suggestion that still needs a POI search. */
private sealed interface ResultItem {
    data class Poi(val hit: SearchHit) : ResultItem
    data class Keyword(val text: String, val district: String?) : ResultItem
}

private val secondary = Color(0xFF5F6368)
private const val HISTORY_KEY = "search_history"
private const val HISTORY_MAX = 20
// Each keystroke-triggered suggestion request counts against AMap's monthly search quota
// (shared with keyword search), so wait for a short pause in typing first.
private const val TYPING_PAUSE_MS = 400L

/**
 * The always-visible search pill. Typing shows AMap input suggestions live; the IME search key runs
 * a full keyword search. The pill keeps its size; results open in a card beneath it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchBar(
    haze: HazeState,
    center: () -> LatLng,
    onPick: (SearchHit) -> Unit,
    onActiveChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var history by remember { mutableStateOf(loadHistory(prefs.getString(HISTORY_KEY, null))) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<ResultItem>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var fullSearch by remember { mutableStateOf<Job?>(null) }
    // Keyword whose full search results are showing; live tips must not replace them.
    var fullSearchKeyword by remember { mutableStateOf<String?>(null) }

    fun close() {
        fullSearch?.cancel()
        query = ""
        rows = emptyList()
        status = null
        focusManager.clearFocus()
    }

    fun pick(hit: SearchHit) {
        history = (listOf(hit) + history.filterNot { it.poiId == hit.poiId }).take(HISTORY_MAX)
        prefs.edit { putString(HISTORY_KEY, saveHistory(history)) }
        close()
        onPick(hit)
    }

    fun runFullSearch(keyword: String) {
        fullSearch?.cancel()
        fullSearchKeyword = keyword
        status = "搜索中…"
        val c = center()
        fullSearch = scope.launch {
            val result = searchPoi(context, keyword, cityCodeAt(context, c), c.latitude, c.longitude)
            rows = result.getOrDefault(emptyList()).map { ResultItem.Poi(it) }
            status = result.fold(
                onSuccess = { if (it.isEmpty()) "没有找到“$keyword”" else null },
                onFailure = { "搜索失败：${it.message}" },
            )
        }
    }

    BackHandler(enabled = focused || query.isNotEmpty()) { close() }

    // Lets the screen hide its bottom panels while the search is open, so the two never overlap.
    val active = focused || query.isNotEmpty()
    LaunchedEffect(active) { onActiveChange(active) }

    // Collapsing the keyboard (e.g. its own hide key) while typing here also closes the search,
    // unless results are showing: then the user is likely hiding it to see more of the list.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) {
            imeWasVisible = false
            if (focused && rows.isEmpty()) close()
        }
    }

    // Live suggestions; restarting the effect on each keystroke cancels the pending request.
    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            rows = emptyList()
            status = null
            return@LaunchedEffect
        }
        if (keyword == fullSearchKeyword) return@LaunchedEffect
        fullSearchKeyword = null
        delay(TYPING_PAUSE_MS)
        val c = center()
        val result = inputTips(context, keyword, cityCodeAt(context, c), c.latitude, c.longitude)
        rows = result.getOrDefault(emptyList())
        status = result.exceptionOrNull()?.let { "搜索失败：${it.message}" }
            ?: if (rows.isEmpty()) "没有找到“$keyword”，可按键盘上的搜索键再试" else null
    }

    // Fills the screen area so a tap anywhere outside the bar and its results closes the search.
    // When search is idle nothing here handles touches, so they reach the map and panels.
    Box(Modifier.fillMaxSize()) {
    if (focused || query.isNotEmpty()) {
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                close()
            }
        })
    }
    Column(Modifier.align(Alignment.TopCenter).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(haze, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF202124)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { query.trim().takeIf { it.isNotEmpty() }?.let(::runFullSearch) }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("搜索地点", color = Color(0xFF555555), fontSize = 16.sp)
                    inner()
                },
            )
            if (focused || query.isNotEmpty()) {
                Text("取消", color = Color(0xFF1E5BCD), modifier = Modifier.clickable { close() }.padding(start = 12.dp))
            }
        }

        val showHistory = focused && query.isBlank() && history.isNotEmpty()
        if (showHistory || rows.isNotEmpty() || status != null) {
            Column(Modifier.fillMaxWidth().glass(haze, RoundedCornerShape(24.dp)).padding(vertical = 8.dp)) {
                if (showHistory) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text("搜索记录", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("清除", color = Color(0xFF1E5BCD), modifier = Modifier.clickable {
                            history = emptyList()
                            prefs.edit { remove(HISTORY_KEY) }
                        })
                    }
                }
                status?.let { Text(it, color = secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                val list = if (showHistory) history.map { ResultItem.Poi(it) } else rows
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(list) { _, row ->
                        when (row) {
                            is ResultItem.Poi -> ResultRow(row.hit.title, listOfNotNull(row.hit.city, row.hit.address).joinToString(" · ")) { pick(row.hit) }
                            is ResultItem.Keyword -> ResultRow("🔍 ${row.text}", row.district.orEmpty()) {
                                query = row.text
                                runFullSearch(row.text)
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
private fun ResultRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        if (sub.isNotEmpty()) Text(sub, color = secondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun loadHistory(json: String?): List<SearchHit> = json?.let {
    val a = JSONArray(it)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        SearchHit(o.getString("t"), o.optString("a").ifEmpty { null }, o.optString("c").ifEmpty { null }, o.getDouble("lat"), o.getDouble("lng"), o.getString("id"))
    }
}.orEmpty()

private fun saveHistory(list: List<SearchHit>) = JSONArray(
    list.map { JSONObject().put("t", it.title).put("a", it.address ?: "").put("c", it.city ?: "").put("lat", it.lat).put("lng", it.lng).put("id", it.poiId) },
).toString()

// City code per ~10 km cell of the map centre; looked up at search time so it follows the map.
private val cityCodes = mutableMapOf<String, String>()

/** AMap city code at the map centre, used to rank results locally (not to limit them); null on failure. */
internal suspend fun cityCodeAt(context: Context, c: LatLng): String? {
    val cell = "%.1f,%.1f".format(java.util.Locale.ROOT, c.latitude, c.longitude)
    cityCodes[cell]?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            GeocodeSearch(context).getFromLocation(RegeocodeQuery(LatLonPoint(c.latitude, c.longitude), 200f, GeocodeSearch.AMAP))
                .cityCode?.takeIf { it.isNotEmpty() }
        } catch (e: AMapException) {
            null
        }
    }?.also { cityCodes[cell] = it }
}

/**
 * AMap input tips, biased to the current city and map centre but not limited to them.
 * Per the AMap docs: no poiId and no point = keyword only; poiId without point = bus line (skipped).
 */
private suspend fun inputTips(context: Context, keyword: String, city: String?, lat: Double, lng: Double): Result<List<ResultItem>> =
    withContext(Dispatchers.IO) {
        try {
            val q = InputtipsQuery(keyword, city.orEmpty()).apply {
                cityLimit = false
                location = LatLonPoint(lat, lng)
            }
            val tips = Inputtips(context, q).requestInputtips().orEmpty()
            Result.success(
                tips.mapNotNull { t ->
                    when {
                        t.point != null && !t.poiID.isNullOrEmpty() -> ResultItem.Poi(
                            SearchHit(t.name, t.address?.takeIf { it.isNotBlank() }, t.district, t.point.latitude, t.point.longitude, t.poiID),
                        )
                        t.poiID.isNullOrEmpty() -> ResultItem.Keyword(t.name, t.district)
                        else -> null
                    }
                },
            )
        } catch (e: AMapException) {
            Result.failure(Exception(e.errorMessage))
        }
    }

/**
 * Keyword search ranked around the current city. When none of the local results is named like the
 * keyword (the place is in another city), search nationwide without a location: on device, a
 * city-biased or location-biased query for a famous sight in another province returned only unrelated local
 * sights, while the nationwide query without location found it.
 */
/** AMap type code (e.g. "100100" for 宾馆酒店) and street address of a POI; null when the lookup fails. */
internal suspend fun searchPoi(context: Context, keyword: String, city: String?, lat: Double, lng: Double): Result<List<SearchHit>> =
    withContext(Dispatchers.IO) {
        fun query(c: String, nearby: Boolean): List<SearchHit> {
            val q = PoiSearchV2.Query(keyword, "", c).apply {
                cityLimit = false
                pageSize = 20
                if (nearby) location = LatLonPoint(lat, lng)
            }
            val res = PoiSearchV2(context, q).searchPOI()
            return res.pois.orEmpty().filter { it.latLonPoint != null }.map {
                SearchHit(it.title, it.snippet, it.cityName, it.latLonPoint.latitude, it.latLonPoint.longitude, it.poiId, it.typeDes)
            }
        }
        try {
            val local = if (city.isNullOrEmpty()) emptyList() else query(city, nearby = true)
            val k = keyword.lowercase().replace(" ", "")
            val matches = local.any { it.title.lowercase().replace(" ", "").contains(k) }
            Result.success(if (matches) local else query("", nearby = false).ifEmpty { local })
        } catch (e: AMapException) {
            Result.failure(Exception(e.errorMessage))
        }
    }
