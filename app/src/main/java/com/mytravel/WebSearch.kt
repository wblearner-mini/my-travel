package com.mytravel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web search through DeepSeek's Search API (【官方文档】api-docs.deepseek.com/zh-cn/guides/search,
 * 2026-11-10: POST /v1/tools/search, 5 元 per 1000 successful requests, empty results not billed).
 * Returns the fields the model needs; logs only timing and result count.
 */
suspend fun webSearch(apiKey: String, query: String, count: Int = 6): JSONObject = runInterruptible(Dispatchers.IO) {
    val started = System.currentTimeMillis()
    val conn = URL("https://api.deepseek.com/v1/tools/search").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        // The API accepts 1–128 characters.
        conn.outputStream.use { it.write(JSONObject().put("query", query.take(128)).put("count", count).toString().toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrDefault("")
            Log.i("AI", "web search failed: HTTP $code")
            throw IOException(
                when (code) {
                    401 -> "DeepSeek API Key 无效"
                    402 -> "DeepSeek 账户余额不足"
                    else -> "联网搜索返回 HTTP $code $msg".trim()
                },
            )
        }
        val results = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("results")
        Log.i("AI", "web search ${System.currentTimeMillis() - started} ms, ${results.length()} results")
        JSONObject().put("source", "DeepSeek 联网搜索，网页内容可能过时或不准确").put(
            "results",
            JSONArray(
                (0 until results.length()).map { i ->
                    val r = results.getJSONObject(i)
                    JSONObject().put("title", r.optString("title")).put("url", r.optString("url"))
                        .put("site", r.optString("site_name").takeIf { it.isNotEmpty() && it != "null" })
                        .put("date", r.optString("date").takeIf { it.isNotEmpty() && it != "null" }?.take(10))
                        .put("summary", r.optString("summary").ifEmpty { r.optString("snippet") })
                },
            ),
        )
    } finally {
        conn.disconnect()
    }
}
