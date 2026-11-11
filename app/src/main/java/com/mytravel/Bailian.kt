package com.mytravel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One OpenAI-compatible chat completion with thinking off, straight to a provider the user set up
 * with their own key (debug builds only; release builds use [ServerApi]). Returns the response JSON. Logs only
 * the purpose, provider, timing and token counts — never request or reply content.
 */
suspend fun aiChat(cfg: AiConfig, purpose: String, body: JSONObject): JSONObject = runInterruptible(Dispatchers.IO) {
    if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
        throw IOException("请先在 AI 设置里填写${cfg.provider.label}的接口地址、API Key 和模型")
    }
    body.put("model", cfg.model)
    // Thinking is on by default for both; tool-call rounds with thinking on would also require sending
    // reasoning_content back. 【官方文档】Bailian `enable_thinking`; DeepSeek `thinking.type` (2026-11-10).
    when (cfg.provider) {
        AiProvider.DeepSeek -> body.put("thinking", JSONObject().put("type", "disabled"))
        else -> Unit
    }
    val started = System.currentTimeMillis()
    val conn = URL("${cfg.baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrDefault("")
            Log.i("AI", "$purpose (${cfg.provider}) failed: HTTP $code after ${System.currentTimeMillis() - started} ms")
            throw IOException("${cfg.provider.label}返回 HTTP $code $msg".trim())
        }
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val usage = resp.optJSONObject("usage")
        Log.i(
            "AI",
            "$purpose (${cfg.provider}) ${System.currentTimeMillis() - started} ms, prompt=${usage?.optInt("prompt_tokens")} completion=${usage?.optInt("completion_tokens")}",
        )
        resp
    } finally {
        conn.disconnect()
    }
}
