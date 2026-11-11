package com.mytravel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.mytravel.data.Chat
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val secondary = Color(0xFF5F6368)
private val accent = Color(0xFF1E5BCD)

private val suggestions = listOf("现在这里天气怎么样？", "帮我整理今天的安排", "附近有什么值得去的景点？", "规划一下明天的路线")

/**
 * Chat with the travel assistant. [items] and [session] live in the map screen, so switching tabs
 * keeps the chat; [onChanged] runs after every turn or decision so the chat can be saved.
 */
@Composable
fun AssistantScreen(
    haze: HazeState,
    modifier: Modifier,
    session: AssistantSession,
    items: SnapshotStateList<ChatItem>,
    chats: List<Chat>,
    currentChatId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Chat) -> Unit,
    onDeleteChat: (Chat) -> Unit,
    onChanged: () -> Unit,
    /** Owned by the map screen, so a turn keeps running when this panel closes (e.g. to show a route). */
    scope: CoroutineScope,
    job: Job?,
    onJob: (Job?) -> Unit,
) {
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read after saving settings so the credit line names the provider in use.
    var cfg by remember { mutableStateOf(AiSettings.config(context)) }
    var input by remember { mutableStateOf("") }
    val currentSession by rememberUpdatedState(session)
    val busy = job?.isActive == true
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, busy) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1 + if (busy) 1 else 0) }
    BackHandler(enabled = showHistory || showSettings) {
        showHistory = false
        showSettings = false
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        items += ChatItem.User(t)
        val s = session
        onJob(scope.launch {
            s.send(t) { if (currentSession === s) items += it }
            onJob(null)
            if (currentSession === s) onChanged()
        })
    }

    // A short tap anywhere on the panel (outside buttons) drops a text selection in the messages:
    // the selection container is rebuilt under a new key. Long presses still start a selection.
    var selectionKey by remember { mutableIntStateOf(0) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    Column(
        modifier.imePadding().glass(haze, RoundedCornerShape(28.dp)).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation()
                if (up != null && up.uptimeMillis - down.uptimeMillis < longPressMs) selectionKey++
            }
        },
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    showHistory -> "对话历史"
                    showSettings -> "AI 设置"
                    else -> "AI 助手"
                },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
            )
            if (showHistory || showSettings) {
                TextButton(onClick = {
                    showHistory = false
                    showSettings = false
                }) { Text("返回") }
            } else {
                TextButton(onClick = { showSettings = true }, enabled = !busy) { Text("设置") }
                TextButton(onClick = { showHistory = true }, enabled = !busy) { Text("历史") }
                TextButton(onClick = onNewChat, enabled = !busy) { Text("新对话") }
            }
        }
        if (showSettings) {
            AiSettingsPanel(Modifier.weight(1f).fillMaxWidth()) {
                cfg = AiSettings.config(context)
                showSettings = false
            }
            return@Column
        }
        if (showHistory) {
            ChatHistory(chats, currentChatId, onOpen = {
                showHistory = false
                onOpenChat(it)
            }, onDelete = onDeleteChat)
            return@Column
        }
        Text(
            "由${cfg.provider.label} ${cfg.model} 回答，天气、路线、地点来自和风天气和高德" +
                (if (AiSettings.webSearchEnabled(context) && AiSettings.searchKey(context).isNotEmpty()) "，可联网搜索" else "") +
                "。修改行程需要你确认。",
            color = secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp),
        )
        key(selectionKey) { SelectionContainer(Modifier.weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("可以这样问：", color = secondary)
                        suggestions.forEach { s ->
                            Text(
                                s, color = accent,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable { send(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            itemsIndexed(items) { index, item ->
                when (item) {
                    // Long-press a message to select and copy its text.
                    is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            item.text, color = Color.White,
                            modifier = Modifier.widthIn(max = 300.dp).background(accent, RoundedCornerShape(16.dp)).padding(12.dp),
                        )
                    }
                    is ChatItem.Assistant -> Column {
                        Text(
                            replyText(item.text, item.links.toSet()),
                            modifier = Modifier.widthIn(max = 320.dp).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(12.dp),
                        )
                        DisableSelection {
                            // Copies the whole reply as plain text, with the bold marks removed.
                            Text(
                                "复制", color = accent, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { copyToClipboard(context, item.text.replace("**", "")) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    is ChatItem.ToolNote -> Text("· ${item.text}", color = secondary, style = MaterialTheme.typography.bodySmall)
                    is ChatItem.Pending -> PendingCard(item) { accept ->
                        val s = session
                        scope.launch {
                            val outcome = s.confirm(item.id, accept)
                            // By id, and only while the same chat is open: the list may have changed meanwhile.
                            if (currentSession !== s) return@launch
                            val i = items.indexOfFirst { it is ChatItem.Pending && it.id == item.id }
                            if (i >= 0) items[i] = item.copy(state = if (accept) PendingState.Applied else PendingState.Rejected, detail = item.detail + "\n" + outcome)
                            onChanged()
                        }
                    }
                }
            }
            if (busy) item { Text("思考中…", color = secondary) }
        }
        } }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("问天气、找地点、排行程…") },
                maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            if (busy) {
                TextButton(onClick = {
                    job?.cancel()
                    onJob(null)
                    items += ChatItem.Assistant("已取消。")
                }) { Text("取消") }
            } else {
                TextButton(onClick = { send(input) }, enabled = input.isNotBlank()) { Text("发送") }
            }
        }
    }
}

@Composable
private fun PendingCard(item: ChatItem.Pending, onDecide: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x221E5BCD), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.title, fontWeight = FontWeight.Bold)
        if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall)
        when (item.state) {
            PendingState.Waiting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onDecide(true) }) { Text("确认") }
                OutlinedButton(onClick = { onDecide(false) }) { Text("不要") }
            }
            PendingState.Applied -> Text("已确认", color = accent)
            PendingState.Rejected -> Text("已拒绝", color = secondary)
            PendingState.Expired -> Text("已失效：来自之前的对话，没有执行。需要的话请让 AI 重新生成。", color = secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val historyTime = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm")

@Composable
private fun ChatHistory(chats: List<Chat>, currentId: Long?, onOpen: (Chat) -> Unit, onDelete: (Chat) -> Unit) {
    var deleting by remember { mutableStateOf<Chat?>(null) }
    if (chats.isEmpty()) {
        Text("还没有保存的对话。", color = secondary, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        itemsIndexed(chats, key = { _, c -> c.id }) { _, c ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (c.id == currentId) FontWeight.Bold else FontWeight.Normal)
                    Text(
                        java.time.Instant.ofEpochMilli(c.updatedAtMillis).atZone(java.time.ZoneId.systemDefault()).format(historyTime) +
                            if (c.id == currentId) " · 当前对话" else "",
                        color = secondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { deleting = c }) { Text("删除", color = Color(0xFFC62828)) }
            }
        }
    }
    deleting?.let { c ->
        ConfirmDialog("删除这段对话？", "只删除对话记录，不影响行程。删除后无法恢复。", onConfirm = {
            onDelete(c)
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

private val linkStyles = TextLinkStyles(SpanStyle(color = Color(0xFF1E5BCD), textDecoration = TextDecoration.Underline))
private val boldPattern = Regex("""\*\*(.+?)\*\*""")

/**
 * A reply with **bold** place names and clickable links. Only [links] (URLs the tools returned or
 * the app built) open the browser; any other URL the model wrote stays plain text.
 */
private fun replyText(text: String, links: Set<String>): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in boldPattern.findAll(text)) {
        appendLinks(text.substring(i, m.range.first), links)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        i = m.range.last + 1
    }
    appendLinks(text.substring(i), links)
}

private fun AnnotatedString.Builder.appendLinks(part: String, links: Set<String>) {
    var i = 0
    for (url in urlsIn(part)) {
        val at = part.indexOf(url, i)
        append(part.substring(i, at))
        if (url in links) {
            // Long search-result URLs are shortened for display; the full URL is opened.
            val bare = url.removePrefix("https://").removePrefix("http://")
            val shown = if (bare.length > 40) bare.take(40) + "…" else bare
            withLink(LinkAnnotation.Url(url, linkStyles)) { append("🔗 $shown") }
        } else {
            append(url)
        }
        i = at + url.length
    }
    append(part.substring(i))
}
