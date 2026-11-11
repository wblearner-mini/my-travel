package com.mytravel

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings

private const val PREFS = "app"
private const val KEY_PRIVACY_AGREED = "privacy_agreed"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val alreadyAgreed = prefs.getBoolean(KEY_PRIVACY_AGREED, false)
        if (alreadyAgreed) applyAmapPrivacy(this)
        ServerApi.init(this)
        setContent {
            MaterialTheme {
                var agreed by remember { mutableStateOf(alreadyAgreed) }
                // Full use needs the managed account. A lost session (expired,
                // revoked, logged out) brings this back; local trips are kept.
                val loggedIn by ServerApi.loggedIn.collectAsState()
                if (agreed && loggedIn) {
                    MapScreen()
                } else if (agreed) {
                    LoginScreen()
                } else {
                    PrivacyScreen(
                        onAgree = {
                            prefs.edit { putBoolean(KEY_PRIVACY_AGREED, true) }
                            applyAmapPrivacy(this)
                            agreed = true
                        },
                        onDecline = { finish() },
                    )
                }
            }
        }
    }
}

// AMap requires these before any MapView / location client / search object is created.
private fun applyAmapPrivacy(context: Context) {
    MapsInitializer.updatePrivacyShow(context, true, true)
    MapsInitializer.updatePrivacyAgree(context, true)
    AMapLocationClient.updatePrivacyShow(context, true, true)
    AMapLocationClient.updatePrivacyAgree(context, true)
    ServiceSettings.updatePrivacyShow(context, true, true)
    ServiceSettings.updatePrivacyAgree(context, true)
}

@androidx.compose.runtime.Composable
private fun PrivacyScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    BrandPage("使用前请阅读以下说明") {
        Text("使用说明与隐私", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
        Text(
            "我的旅游使用高德地图 SDK 显示地图、定位和搜索地点。高德 SDK 会处理位置、设备与网络信息，" +
                "详见高德开放平台隐私政策（lbs.amap.com/pages/privacy）。\n\n" +
                "行程数据只保存在本机。定位权限会在你点击定位按钮时再申请；不授权也可以浏览地图和安排行程。",
            style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3C4043),
        )
        Button(
            onClick = onAgree, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = brandAccent),
        ) { Text("同意", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("不同意并退出", color = brandSecondary) }
    }
}

private val brandAccent = Color(0xFF1E5BCD)
private val brandSecondary = Color(0xFF5F6368)

/**
 * The pages shown before the map (privacy, login): same look as the map's glass cards (white frosted
 * panel, 28dp corners, white rim, blue accent), over a sky gradient taken from the app icon, since there
 * is no map behind them yet.
 */
@androidx.compose.runtime.Composable
private fun BrandPage(subtitle: String, below: String? = null, card: @androidx.compose.runtime.Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFFBFE9F7), Color(0xFFE6F5FC), Color(0xFFF7FBFE))),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(R.mipmap.ic_launcher_foreground), null,
                Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text("我的旅游", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = brandSecondary)
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = card,
            )
            below?.let {
                Spacer(Modifier.height(20.dp))
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = brandSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(ServerApi.username().orEmpty()) }
    var server by remember { mutableStateOf(ServerApi.serverUrl()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Says why the user is here again when the session was lost (expired, revoked); empty after a normal logout.
    var error by remember { mutableStateOf(ServerApi.takeNotice()) }
    val fieldShape = RoundedCornerShape(16.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = brandAccent,
        focusedLabelColor = brandAccent,
        cursorColor = brandAccent,
        unfocusedBorderColor = Color(0x1F000000),
    )
    BrandPage(
        "登录后开始规划行程",
        below = "请使用发放给你的专用账号登录，暂不开放注册。\n这台手机上已保存的行程不会因登录或退出而删除。",
    ) {
        OutlinedTextField(
            username, { username = it.trim() }, singleLine = true, label = { Text("账号") }, modifier = Modifier.fillMaxWidth(),
            shape = fieldShape, colors = fieldColors,
        )
        OutlinedTextField(
            password, { password = it }, singleLine = true, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = fieldShape, colors = fieldColors,
        )
        if (BuildConfig.DEBUG) {
            OutlinedTextField(
                server, { server = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("服务地址（调试版）") },
                supportingText = { Text("USB：https://localhost:18443　其他：https://<主机地址>:18443") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = fieldShape, colors = fieldColors,
            )
        }
        error?.let { Text(it, color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = !busy && username.isNotEmpty() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = brandAccent, disabledContainerColor = brandAccent.copy(alpha = 0.35f), disabledContentColor = Color.White),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        ServerApi.setServerUrl(server)
                        ServerApi.login(username, password)
                    } catch (e: java.io.IOException) {
                        error = e.message
                    } catch (e: org.json.JSONException) {
                        // Something answered at that address, but not this app's server (e.g. a gateway page).
                        error = "服务返回的内容无法识别，请检查服务地址"
                    } finally {
                        busy = false
                        password = ""
                    }
                }
            },
        ) { Text(if (busy) "正在登录…" else "登录", style = MaterialTheme.typography.titleMedium) }
    }
}
