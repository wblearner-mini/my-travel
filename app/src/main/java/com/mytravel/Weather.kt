package com.mytravel

import com.mytravel.data.WeatherCache
import com.mytravel.data.WeatherDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// ---- Parsed QWeather data (units as documented: °C, m/s, mm; ratios 0..1) ----

data class NowWeather(
    val text: String, val code: String, val temp: Double, val feelsLike: Double,
    val humidity: Double, val windScale: Int, val windDir: String, val precip1h: Double,
    /** When the analysis is valid (observed 2026-11-09 in real responses; not in the docs example). */
    val validTime: OffsetDateTime?,
)

data class HourWeather(val time: OffsetDateTime, val text: String, val code: String, val temp: Double, val pop: Double?, val precip: Double)

data class DayWeather(
    val date: LocalDate, val dayText: String, val dayCode: String, val nightText: String, val nightCode: String,
    val tMax: Double, val tMin: Double, val pop: Double?, val precip: Double, val uvMax: Int?, val humidity: Double?,
)

data class Minutely(val summary: String, val updateTime: OffsetDateTime, val precip: List<Pair<OffsetDateTime, Double>>)

data class WeatherAlert(val headline: String, val description: String, val colorCode: String, val expire: OffsetDateTime?)

/**
 * A value plus where it came from, so the UI can state data age honestly.
 * [attributions] are QWeather's notices that must be shown together with the data.
 */
data class Fetched<T>(val value: T, val fetchedAt: Instant, val fromCache: Boolean, val attributions: List<String>)

private fun attributionsOf(o: JSONObject): List<String> {
    val arr = o.optJSONObject("metadata")?.optJSONArray("attributions") ?: o.optJSONObject("refer")?.optJSONArray("sources")
    return arr?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
}

private fun JSONObject.v(name: String) = getJSONObject(name).getDouble("value")
private fun JSONObject.optProb(): Double? = if (has("probability") && !isNull("probability")) getDouble("probability") else null
private fun time(s: String): OffsetDateTime = OffsetDateTime.parse(s)

private val compass = mapOf(
    "n" to "北", "nne" to "北东北", "ne" to "东北", "ene" to "东东北", "e" to "东", "ese" to "东东南",
    "se" to "东南", "sse" to "南东南", "s" to "南", "ssw" to "南西南", "sw" to "西南", "wsw" to "西西南",
    "w" to "西", "wnw" to "西西北", "nw" to "西北", "nnw" to "北西北", "none" to "无持续风向", "vrb" to "风向不定",
)

private fun parseNow(o: JSONObject): NowWeather {
    val c = o.getJSONObject("condition")
    val wind = o.getJSONObject("wind")
    return NowWeather(
        text = c.getString("text"), code = c.getString("code"), temp = o.v("temperature"), feelsLike = o.v("feelsLike"),
        humidity = o.getDouble("humidity"), windScale = wind.getInt("scale"),
        windDir = compass[wind.getJSONObject("direction").getString("compass")] ?: "",
        precip1h = o.getJSONObject("precipitation").v("amount"),
        validTime = o.optString("validTime").takeIf { it.isNotEmpty() }?.let(::time),
    )
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun parseHours(o: JSONObject) = o.getJSONArray("hours").objects().map { h ->
    val c = h.getJSONObject("condition")
    val p = h.getJSONObject("precipitation")
    HourWeather(time(h.getString("forecastTime")), c.getString("text"), c.getString("code"), h.v("temperature"), p.optProb(), p.v("amount"))
}

private fun parseDays(o: JSONObject) = o.getJSONArray("days").objects().map { d ->
    val day = d.getJSONObject("daytime")
    val night = d.getJSONObject("nighttime")
    val pops = listOfNotNull(day.getJSONObject("precipitation").optProb(), night.getJSONObject("precipitation").optProb())
    DayWeather(
        // With localTime=true the day starts at local midnight.
        date = time(d.getString("forecastStartTime")).toLocalDate(),
        dayText = day.getJSONObject("condition").getString("text"), dayCode = day.getJSONObject("condition").getString("code"),
        nightText = night.getJSONObject("condition").getString("text"), nightCode = night.getJSONObject("condition").getString("code"),
        tMax = d.v("temperatureMax"), tMin = d.v("temperatureMin"),
        pop = pops.maxOrNull(),
        precip = day.getJSONObject("precipitation").v("amount") + night.getJSONObject("precipitation").v("amount"),
        uvMax = if (d.has("uvIndexMax")) d.getInt("uvIndexMax") else null,
        humidity = if (day.has("humidity")) day.getDouble("humidity") else null,
    )
}

private fun parseMinutely(o: JSONObject) = Minutely(
    summary = o.getString("summary"),
    updateTime = time(o.getString("updateTime")),
    precip = o.getJSONArray("minutely").objects().map { time(it.getString("fxTime")) to it.getString("precip").toDouble() },
)

private fun parseAlerts(o: JSONObject) = o.optJSONArray("alerts")?.objects().orEmpty().map { a ->
    WeatherAlert(
        headline = a.getString("headline"), description = a.optString("description"),
        colorCode = a.optJSONObject("color")?.optString("code").orEmpty(),
        expire = a.optString("expireTime").takeIf { it.isNotEmpty() && it != "null" }?.let(::time),
    )
}

/**
 * QWeather data through this app's server (which signs the QWeather request; the phone holds no
 * QWeather credential). Raw-JSON cache per ~1 km cell, cache fallback when the network fails.
 * Every kind is reused for [CACHE_MINUTES], so reopening a trip place's weather costs no quota;
 * [fresh] (used by the AI assistant) skips this cache; the server still reuses results under 5 minutes old.
 */
class WeatherRepo(private val cache: WeatherDao) {

    // Server-side names; the server holds the QWeather paths. Coordinates are GCJ-02.
    private enum class Kind(val api: String) { Now("now"), Hourly("hourly"), Daily("daily"), Minutely("minutely"), Alerts("alerts") }

    suspend fun now(lat: Double, lng: Double, fresh: Boolean = false) = load(Kind.Now, lat, lng, fresh, ::parseNow)
    suspend fun hourly(lat: Double, lng: Double, fresh: Boolean = false) = load(Kind.Hourly, lat, lng, fresh, ::parseHours)
    suspend fun daily(lat: Double, lng: Double, fresh: Boolean = false) = load(Kind.Daily, lat, lng, fresh, ::parseDays)
    suspend fun minutely(lat: Double, lng: Double, fresh: Boolean = false) = load(Kind.Minutely, lat, lng, fresh, ::parseMinutely)
    suspend fun alerts(lat: Double, lng: Double, fresh: Boolean = false) = load(Kind.Alerts, lat, lng, fresh, ::parseAlerts)

    private suspend fun <T> load(kind: Kind, lat: Double, lng: Double, fresh: Boolean, parse: (JSONObject) -> T): Result<Fetched<T>> {
        val la = "%.2f".format(java.util.Locale.ROOT, lat)
        val ln = "%.2f".format(java.util.Locale.ROOT, lng)
        val cell = "$la,$ln"
        val cached = cache.get(kind.name, cell)
        val now = System.currentTimeMillis()
        fun fromJson(json: String, at: Long, fromCache: Boolean) =
            JSONObject(json).let { Fetched(parse(it), Instant.ofEpochMilli(at), fromCache, attributionsOf(it)) }
        if (!fresh && cached != null && now - cached.fetchedAtMillis < CACHE_MINUTES * 60_000) {
            return Result.success(fromJson(cached.json, cached.fetchedAtMillis, fromCache = false))
        }
        return try {
            val (json, at) = ServerApi.weather(kind.api, la.toDouble(), ln.toDouble(), fresh)
            val fresh = fromJson(json, at, fromCache = false) // parse before caching: only valid data is kept
            cache.put(WeatherCache(kind.name, cell, json, at))
            Result.success(fresh)
        } catch (e: Exception) {
            // Network/HTTP failure, server unreachable or logged out, or a response that doesn't match
            // the documented schema: weather degrades, the rest of the app keeps working.
            if (e !is IOException && e !is org.json.JSONException && e !is IllegalArgumentException) throw e
            if (cached != null) Result.success(fromJson(cached.json, cached.fetchedAtMillis, fromCache = true)) else Result.failure(e)
        }
    }

    private companion object {
        // Trip-place weather refreshes at most every 30 minutes.
        const val CACHE_MINUTES = 30L
    }
}

/** Compact glyph for a QWeather condition code (codes: dev.qweather.com/docs/resource/icons/). */
fun weatherGlyph(code: String, night: Boolean = false): String {
    val c = code.toIntOrNull() ?: return "?"
    return when (c) {
        100 -> if (night) "🌙" else "☀️"
        150 -> "🌙"
        in 101..103, in 151..153 -> "⛅"
        104 -> "☁️"
        in 302..304 -> "⛈️"
        in 300..399 -> "🌧️"
        in 400..499 -> "❄️"
        in 500..515 -> "🌫️"
        900 -> "🔥"
        901 -> "🥶"
        else -> "🌡️"
    }
}
