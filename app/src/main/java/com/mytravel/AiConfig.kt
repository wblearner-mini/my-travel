package com.mytravel

import android.content.Context
import androidx.core.content.edit

/**
 * Model providers the AI assistant can use. [Server] is this app's server (Bailian behind it, after
 * login); it is the only one in release builds. The others are called
 * directly with a key the user typed, and exist in debug builds only. Defaults per 【官方文档】
 * (2026-11-10): DeepSeek base https://api.deepseek.com, model deepseek-flash (api-docs.deepseek.com).
 */
enum class AiProvider(val label: String, val defaultBase: String, val defaultModel: String) {
    Server("我的旅游服务（阿里云百炼）", "", ""),
    DeepSeek("DeepSeek（自填 Key）", "https://api.deepseek.com", "deepseek-flash"),
    OpenAi("OpenAI 兼容协议（自填 Key）", "", ""),
    ;

    companion object {
        val available get() = if (BuildConfig.DEBUG) entries else listOf(Server)
    }
}

data class AiConfig(val provider: AiProvider, val baseUrl: String, val apiKey: String, val model: String)

/**
 * The assistant's provider choice, kept in this app's private storage on the phone (never in the repo).
 * Hotel order parsing always goes through the server (Bailian, the provider verified with images).
 */
object AiSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun provider(c: Context): AiProvider =
        prefs(c).getString("provider", null)?.let { n -> AiProvider.available.find { it.name == n } } ?: AiProvider.Server

    /** What the user typed for [p]; empty means "use the default". */
    fun stored(c: Context, p: AiProvider) = Triple(
        prefs(c).getString("${p.name}_base", "").orEmpty(),
        prefs(c).getString("${p.name}_key", "").orEmpty(),
        prefs(c).getString("${p.name}_model", "").orEmpty(),
    )

    fun config(c: Context, p: AiProvider = provider(c)): AiConfig {
        val (base, key, model) = stored(c, p)
        return AiConfig(
            p, base.ifBlank { p.defaultBase }.trim(),
            key.trim(),
            model.ifBlank { p.defaultModel }.trim(),
        )
    }

    fun save(c: Context, p: AiProvider, base: String, key: String, model: String) = prefs(c).edit {
        putString("provider", p.name).putString("${p.name}_base", base.trim())
            .putString("${p.name}_key", key.trim()).putString("${p.name}_model", model.trim())
    }

    /** Web search: through the server for [AiProvider.Server]; otherwise DeepSeek's Search API with the typed DeepSeek key. */
    fun webSearchEnabled(c: Context) = prefs(c).getBoolean("web_search", true)
    fun setWebSearchEnabled(c: Context, on: Boolean) = prefs(c).edit { putBoolean("web_search", on) }
    fun searchKey(c: Context) = config(c, AiProvider.DeepSeek).apiKey
}
