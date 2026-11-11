package com.mytravel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val hint = Color(0xFF5F6368)

/** Choose the assistant's model provider and its endpoint; switch DeepSeek web search on or off. */
@Composable
fun AiSettingsPanel(modifier: Modifier, onSaved: () -> Unit) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(AiSettings.provider(context)) }
    // Fields of every provider are kept while switching, so nothing typed is lost before saving.
    val fields = remember { AiProvider.entries.associateWith { AiSettings.stored(context, it).toList().toMutableList() } }
    var base by remember { mutableStateOf(fields.getValue(provider)[0]) }
    var key by remember { mutableStateOf(fields.getValue(provider)[1]) }
    var model by remember { mutableStateOf(fields.getValue(provider)[2]) }
    var deepSeekKey by remember { mutableStateOf(fields.getValue(AiProvider.DeepSeek)[1]) }
    var search by remember { mutableStateOf(AiSettings.webSearchEnabled(context)) }

    fun select(p: AiProvider) {
        fields.getValue(provider).apply { set(0, base); set(1, key); set(2, model) }
        provider = p
        fields.getValue(p).let { base = it[0]; key = it[1]; model = it[2] }
        if (p == AiProvider.DeepSeek) key = deepSeekKey
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("服务厂商", style = MaterialTheme.typography.labelLarge)
        AiProvider.available.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { select(p) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p == provider, onClick = { select(p) })
                Text(p.label)
            }
        }
        // The server needs nothing here: model and key live on the server.
        if (provider != AiProvider.Server) {
        OutlinedTextField(
            base, { base = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("接口地址（Base URL）") },
            // Shown below the field so the default is visible without focusing it.
            supportingText = { Text(provider.defaultBase.takeIf { it.isNotEmpty() }?.let { "留空使用 $it" } ?: "必填，如 https://example.com/v1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            key, {
                key = it
                if (provider == AiProvider.DeepSeek) deepSeekKey = it
            },
            singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") },
            supportingText = { Text("必填") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            model, { model = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("模型") },
            supportingText = { Text(provider.defaultModel.takeIf { it.isNotEmpty() }?.let { "留空使用 $it" } ?: "必填，如 gpt-4o-mini") },
        )
        Text("Key 只保存在这台手机的 App 数据里（调试版功能）。", color = hint, style = MaterialTheme.typography.bodySmall)
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("联网搜索", style = MaterialTheme.typography.labelLarge)
                Text("用 DeepSeek 搜索网页找景点和攻略，每千次约 5 元", color = hint, style = MaterialTheme.typography.bodySmall)
            }
            Switch(search, { search = it })
        }
        if (search && provider == AiProvider.OpenAi) {
            OutlinedTextField(
                deepSeekKey, { deepSeekKey = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepSeek API Key（联网搜索用）") }, visualTransformation = PasswordVisualTransformation(),
            )
        }
        if (search && provider != AiProvider.Server && deepSeekKey.isBlank()) {
            Text("未填写 DeepSeek API Key，联网搜索暂不可用。", color = hint, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                fields.getValue(provider).apply { set(0, base); set(1, key); set(2, model) }
                fields.getValue(AiProvider.DeepSeek)[1] = deepSeekKey
                // Save the DeepSeek key even when another provider is chosen; it powers web search.
                fields.getValue(AiProvider.DeepSeek).let { AiSettings.save(context, AiProvider.DeepSeek, it[0], it[1], it[2]) }
                AiSettings.save(context, provider, base, key, model)
                AiSettings.setWebSearchEnabled(context, search)
                onSaved()
            },
            modifier = Modifier.padding(vertical = 8.dp),
        ) { Text("保存") }
    }
}
