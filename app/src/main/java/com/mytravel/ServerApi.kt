package com.mytravel

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server said the session is gone (expired, revoked, logged out elsewhere). */
class SessionExpired : IOException("登录已失效，请重新登录")

/**
 * This app's own server (see `server/`): login, then AI, web search, weather and order parsing.
 * The phone holds only a session token; upstream credentials stay on the server.
 * The token lives in its own prefs file, which is excluded from system backup.
 */
object ServerApi {
    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _loggedIn = MutableStateFlow(false)

    /** Whether there is a token that has not reached its expiry on this phone's clock. */
    val loggedIn: StateFlow<Boolean> get() = _loggedIn

    /** Why the session was lost, for the login page to show once; null after a normal logout. */
    @Volatile private var notice: String? = null

    fun takeNotice(): String? = notice.also { notice = null }

    fun init(context: Context) {
        app = context.applicationContext
        val p = prefs()
        // Offline the local expiry is all we know: past it, log in again.
        if (p.getLong("expires_at", 0) * 1000 <= System.currentTimeMillis()) {
            if (p.getString("token", null) != null) notice = "登录已过期，请重新登录"
            clear()
        }
        _loggedIn.value = p.getString("token", null) != null
    }

    fun username(): String? = prefs().getString("username", null)

    /**
     * The server address. Debug builds can change it on the login page (USB, same Wi-Fi or Tailscale)
     * without rebuilding; release builds always use the one built in. Kept apart from the session,
     * so logging out doesn't forget it.
     */
    fun serverUrl(): String =
        if (BuildConfig.DEBUG) app.getSharedPreferences("server", Context.MODE_PRIVATE).getString("url", null) ?: BuildConfig.SERVER_URL
        else BuildConfig.SERVER_URL

    fun setServerUrl(url: String) {
        if (BuildConfig.DEBUG) app.getSharedPreferences("server", Context.MODE_PRIVATE).edit { putString("url", url.trim().trimEnd('/')) }
    }

    // Keeps the username, so the login page after a lost session or a logout is already filled in.
    private fun clear() {
        prefs().edit { remove("token").remove("expires_at") }
        _loggedIn.value = false
    }

    suspend fun login(username: String, password: String) {
        notice = null
        val r = call("/v1/auth/login", JSONObject().put("username", username).put("password", password).toString().toByteArray(), "application/json", auth = false)
        prefs().edit {
            putString("token", r.getString("token")).putLong("expires_at", r.getLong("expires_at")).putString("username", username)
        }
        _loggedIn.value = true
    }

    /** Revokes the session on the server when reachable; the local token is dropped either way. */
    suspend fun logout() {
        try {
            call("/v1/auth/logout", ByteArray(0), "application/json")
        } catch (_: IOException) {
        }
        clear()
        // The user chose to leave; a 401 from an already revoked session is no news to them.
        notice = null
    }

    suspend fun post(path: String, body: JSONObject): JSONObject = call(path, body.toString().toByteArray(), "application/json")

    /** Raw weather JSON and when the server fetched it from QWeather (it may come from the server's cache). */
    suspend fun weather(kind: String, lat: Double, lng: Double, fresh: Boolean): Pair<String, Long> =
        callText("/v1/weather", JSONObject().put("kind", kind).put("lat", lat).put("lng", lng).put("fresh", fresh).toString().toByteArray(), "application/json")
            .let { (text, at) -> text to (at ?: System.currentTimeMillis()) }

    suspend fun postImage(path: String, jpeg: ByteArray): JSONObject = call(path, jpeg, "image/jpeg")

    private suspend fun call(path: String, body: ByteArray, type: String, auth: Boolean = true) = JSONObject(callText(path, body, type, auth).first)

    /**
     * One request, no retry. A 401 on a protected call clears the session, so no further
     * protected requests go out until the user logs in again; local trips are untouched.
     */
    private suspend fun callText(path: String, body: ByteArray, type: String, auth: Boolean = true): Pair<String, Long?> = runInterruptible(Dispatchers.IO) {
        val base = serverUrl()
        if (base.isEmpty()) throw IOException("这个安装包没有配置服务地址")
        val token = prefs().getString("token", null)
        if (auth && token == null) throw SessionExpired()
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            // A model turn can take a while upstream (the server allows 90 s).
            conn.readTimeout = 100_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type)
            if (auth) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body) }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw IOException("无法连接服务（主机可能已关机或不在同一网络）")
            }
            if (code == 200) return@runInterruptible conn.inputStream.bufferedReader().use { it.readText() } to conn.getHeaderField("X-Fetched-At")?.toLongOrNull()
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val msg = runCatching { JSONObject(err).getString("message") }.getOrDefault("服务返回 HTTP $code")
            if (code == 401 && auth) {
                val e = SessionExpired()
                notice = e.message
                clear()
                throw e
            }
            throw IOException(msg)
        } finally {
            conn.disconnect()
        }
    }
}
