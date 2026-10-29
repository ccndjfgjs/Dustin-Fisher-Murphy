package dev.opencode.mobile.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ModelReasoningCatalog
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ReasoningLevel
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.SessionSummary
import dev.opencode.mobile.data.TodoItem
import dev.opencode.mobile.data.ToolCallRef
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

@Composable
fun ChatScreen(vm: AppViewModel) {
    val ui = vm.ui
    val focusManager = LocalFocusManager.current
    val session = ui.session
    // Empty assistant tool-call envelopes are represented by the tool cards themselves.
    val messages = session?.messages.orEmpty().filterNot {
        it.role == Role.ASSISTANT && !it.streaming && it.content.isBlank() && it.reasoning.isBlank() && it.toolCalls.isNotEmpty()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSessions by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) vm.exportSession(id, uri)
    }
    var showModels by remember { mutableStateOf(false) }
    var followLatest by remember(session?.id) { mutableStateOf(true) }
    var autoScrolling by remember(session?.id) { mutableStateOf(false) }
    var wasBusy by remember(session?.id) { mutableStateOf(ui.busy) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val nearBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 96)
        }
    }

    // Follow streaming output only while the user is at the bottom; don't pull them away from history.
    LaunchedEffect(dragging) {
        if (dragging) followLatest = false else followLatest = nearBottom
    }
    LaunchedEffect(session?.id, listState) {
        var manualScroll = false
        snapshotFlow { listState.isScrollInProgress to autoScrolling }.collect { (scrolling, automatic) ->
            if (scrolling && !automatic) {
                manualScroll = true
                followLatest = false
            } else if (!scrolling && !automatic && manualScroll) {
                manualScroll = false
                followLatest = nearBottom
            }
        }
    }
    LaunchedEffect(session?.id) {
        if (messages.isNotEmpty()) {
            // Restore a saved conversation at its latest message when opening it.
            autoScrolling = true
            try {
                listState.scrollToItem(messages.lastIndex)
                listState.scrollBy(1_000_000f)
            } finally {
                autoScrolling = false
            }
        }
    }
    LaunchedEffect(ui.busy, session?.id) {
        if (wasBusy && !ui.busy && followLatest && !dragging && messages.isNotEmpty()) {
            autoScrolling = true
            try {
                listState.scrollToItem(messages.lastIndex)
                listState.scrollBy(1_000_000f)
            } finally {
                autoScrolling = false
            }
        }
        wasBusy = ui.busy
    }
    LaunchedEffect(session?.id, messages.size, followLatest, dragging, ui.busy) {
        if (ui.busy && messages.isNotEmpty() && followLatest && !dragging) {
            // Observe layout, not just message count: a streamed message can grow beyond the viewport.
            snapshotFlow { listState.layoutInfo }.collect { info ->
                if (info.totalItemsCount == messages.size) {
                    val last = info.visibleItemsInfo.lastOrNull()
                    val overflow = if (last != null) last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding) else 0
                    if (last == null || last.index < messages.lastIndex || overflow > 0) {
                        autoScrolling = true
                        try {
                            if (last == null || last.index < messages.lastIndex) listState.scrollToItem(messages.lastIndex)
                            else listState.scrollBy(overflow.toFloat())
                        } finally {
                            autoScrolling = false
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgColor)) {
        ChatHeader(
            title = session?.title ?: "Новый чат",
            workspace = ui.workspace,
            onWorkspace = { vm.navigate(Screen.WORKSPACE) },
            onHistory = { showSessions = true },
            onNew = { vm.newSession() }
        )

        val rootWarning = ui.settings.allowRoot || (ui.busy && ui.runningRootAllowed)
        val autoWarning = ui.settings.autoApproveTools || (ui.busy && ui.runningAutoApprove)
        if (rootWarning || autoWarning) {
            SafetyBanner(rootWarning, autoWarning, onClick = { vm.navigate(Screen.SETTINGS) })
        }
        if (ui.todos.isNotEmpty()) TodoStrip(ui.todos)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyChat(showSuggestions = ui.chatDraft.isBlank(), onPrompt = vm::updateChatDraft)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chat-messages"),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageItem(message, vm, onExpandTool = { followLatest = false; vm.toggleMessage(message.id) })
                    }
                }
                if (!followLatest) {
                    IconButton(
                        onClick = {
                            followLatest = true
                            scope.launch {
                                autoScrolling = true
                                try {
                                    listState.scrollToItem(messages.lastIndex)
                                    listState.scrollBy(1_000_000f)
                                } finally {
                                    autoScrolling = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            .background(SurfaceHigh, CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowDownward, "К последнему сообщению", tint = Accent)
                    }
                }
            }
        }

        ui.approval?.call?.let { call ->
            InlineApproval(call, vm.currentToolCallArgs(call.name, call.arguments), vm::approve)
        }
        if (ui.busy && ui.status.isNotBlank() && ui.approval == null) {
            Row(
                modifier = Modifier.padding(start = 22.dp, end = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(12.dp), color = Accent, strokeWidth = 1.5.dp)
                Text(ui.status, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        ChatComposer(
            value = ui.chatDraft,
            onValueChange = vm::updateChatDraft,
            provider = ui.settings.activeProvider,
            busy = ui.busy,
            reasoningLevel = ui.settings.reasoningLevel,
            onReasoningLevelChange = { level -> vm.saveSettings { it.copy(reasoningLevel = level) } },
            onModelClick = { focusManager.clearFocus(); showModels = true },
            onSend = {
                if (ui.chatDraft.isNotBlank() && !ui.busy) {
                    followLatest = true
                    vm.sendMessage(ui.chatDraft)
                    vm.updateChatDraft("")
                }
            },
            onStop = vm::stop
        )
    }

    if (showModels) {
        ModelPickerSheet(
            settings = ui.settings,
            busy = ui.busy,
            onDismiss = { showModels = false },
            onSelect = { providerId, model ->
                vm.selectProviderModel(providerId, model)
                showModels = false
            },
            onAddProvider = {
                showModels = false
                vm.openProviderCatalog()
            }
        )
    }
    if (showSessions) {
        SessionsSheet(
            sessions = ui.sessions,
            query = ui.sessionQuery,
            activeId = session?.id,
            busy = ui.busy,
            formatTime = vm::formatTime,
            onQuery = { vm.searchSessions(it) },
            onDismiss = { showSessions = false },
            onNew = { vm.newSession(); showSessions = false },
            onOpen = { vm.openSession(it); showSessions = false },
            onRename = vm::renameSession,
            onExport = { id -> exportId = id; exportLauncher.launch("chat-${id.take(8)}.md") },
            onDelete = vm::deleteSession
        )
    }
}

@Composable
private fun ChatHeader(title: String, workspace: String, onWorkspace: () -> Unit, onHistory: () -> Unit, onNew: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("/>\u2009", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onWorkspace)
                    .semantics { contentDescription = "Выбрать папку проекта" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderOpen, null, tint = Muted, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(File(workspace).name.ifBlank { workspace }, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "История чатов", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onNew) { Icon(Icons.Default.Add, "Новый чат", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EmptyChat(showSuggestions: Boolean, onPrompt: (String) -> Unit) {
    val suggestions = listOf(
        Triple("Изучить проект", "Изучи структуру проекта и кратко расскажи, как он устроен.", Icons.Outlined.FolderOpen),
        Triple("Найти ошибку", "Помоги найти и исправить ошибку в проекте.", Icons.Outlined.BugReport),
        Triple("Добавить функцию", "Помоги добавить новую функцию в проект.", Icons.Outlined.Code)
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(
                Accent
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Code, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("ВАШ ПОМОЩНИК ПО КОДУ", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(8.dp))
        Text("Что будем создавать?", fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(9.dp))
        Text(
            "Опишите задачу — помогу разобраться в проекте, изменить код и проверить результат.",
            color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        if (showSuggestions) Spacer(Modifier.height(24.dp))
        if (showSuggestions) suggestions.forEach { (label, prompt, icon) ->
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(13.dp)).border(1.dp, DesignTokens.outline, RoundedCornerShape(13.dp))
                    .clickable { onPrompt(prompt) }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = Accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Default.ArrowForward, null, tint = Muted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun SafetyBanner(rootAllowed: Boolean, autoApprove: Boolean, onClick: () -> Unit) {
    val labels = listOfNotNull(
        "Root разрешён".takeIf { rootAllowed },
        "Авто-подтверждение".takeIf { autoApprove }
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp)).background(Accent2.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = Accent2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(labels.joinToString(" · "), color = Accent2, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

@Composable
private fun TodoStrip(todos: List<TodoItem>) {
    var expanded by remember { mutableStateOf(false) }
    val completed = todos.count { it.status == "completed" }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("План задачи", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$completed / ${todos.size}", color = Muted, fontSize = 11.sp)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
        if (expanded) todos.forEach { todo ->
            Text(
                (if (todo.status == "completed") "✓ " else if (todo.status == "in_progress") "› " else "○ ") + todo.content,
                color = if (todo.status == "completed") Success else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage, vm: AppViewModel, onExpandTool: () -> Unit) {
    when (message.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(2.dp).height(36.dp).background(Accent.copy(alpha = 0.65f)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Вы", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(message.content, fontSize = 14.sp, lineHeight = 21.sp, color = DesignTokens.textHigh)
                }
            }
        }
        Role.ASSISTANT -> AssistantMessage(message, onSettings = { vm.navigate(Screen.SETTINGS) })
        Role.TOOL -> ToolCard(message, vm, onExpandTool)
    }
}

@Composable
internal fun AssistantMessage(message: ChatMessage, onSettings: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(Accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text("/>", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
            Text("OpenCode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (message.content.isNotBlank() && !message.streaming) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        Toast.makeText(context, "Ответ скопирован", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) { Icon(Icons.Outlined.ContentCopy, "Скопировать ответ", tint = Muted, modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (message.reasoning.isNotBlank()) {
            ReasoningCard(message)
            Spacer(Modifier.height(8.dp))
        }
        if (message.content.isBlank() && message.streaming) {
            Text("Готовлю ответ…", color = Muted, fontSize = 13.sp)
        } else if (message.content.isNotBlank()) {
            SelectionContainer { MarkdownText(message.content) }
        } else if (message.toolCalls.isNotEmpty()) {
            Text("Запускаю инструменты…", color = Muted, fontSize = 12.sp)
        } else {
            Text("Модель не вернула текст.", color = Muted, fontSize = 12.sp)
        }
        if (message.errorStatus == 401) {
            TextButton(onClick = onSettings) { Text("Открыть настройки") }
        }
    }
}

@Composable
private fun ReasoningCard(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(message.streaming) }
    LaunchedEffect(message.streaming) { expanded = message.streaming }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val duration = message.reasoningDurationMs?.let { if (it < 1000) "< 1 с" else "${it / 1000} с" }
    Column(Modifier.fillMaxWidth().border(1.dp, DesignTokens.outline, RoundedCornerShape(10.dp)).testTag("reasoning-card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Рассуждение" + when { message.streaming -> " · выполняется"; duration != null -> " · $duration"; else -> "" },
                    color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(message.reasoning))
                Toast.makeText(context, "Рассуждение скопировано", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, "Скопировать рассуждение", tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
        if (expanded) {
            SelectionContainer {
                Text(message.reasoning, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

// A preview of requested replacement arguments, not a claim that the edit succeeded.
private fun replacementPreview(name: String?, args: String?): String? {
    if (name != "edit_file" || args.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(args)
        val old = json.optString("old_string", "")
        val new = json.optString("new_string", "")
        if (old.isEmpty() && new.isEmpty()) null else {
            fun lines(value: String, prefix: String) = value.take(1000).lines().take(12).joinToString("\n") { "$prefix $it" }
            listOf(lines(old, "-"), lines(new, "+")).joinToString("\n") +
                if (old.length > 1000 || new.length > 1000 || old.lines().size > 12 || new.lines().size > 12) "\n…" else ""
        }
    }.getOrNull()
}

@Composable
internal fun InlineApproval(call: ToolCallRef, summary: String, onDecision: (Boolean) -> Unit) {
    val preview = remember(call.arguments, call.name) { replacementPreview(call.name, call.arguments) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
            .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp).testTag("inline-approval")
    ) {
        Text("Разрешить инструмент?", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Column {
                    Text(call.name, color = DesignTokens.textHigh, fontSize = 11.sp, fontFamily = Mono)
                    if (summary.isNotBlank()) Text(summary, color = Muted, fontSize = 10.sp, fontFamily = Mono)
                    if (preview != null) {
                        Text("Предпросмотр замены (до выполнения)", color = Muted, fontSize = 10.sp)
                        Text(preview, color = DesignTokens.textHigh, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Mono)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onDecision(false) }) { Text("Отклонить") }
            TextButton(onClick = { onDecision(true) }) { Text("Разрешить") }
        }
    }
}

@Composable
private fun ToolCard(message: ChatMessage, vm: AppViewModel, onExpand: () -> Unit) {
    val color = when (message.toolOk) { true -> Success; false -> Danger; null -> Accent }
    val args = vm.currentToolCallArgs(message.toolName.orEmpty(), message.toolArgs)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceColor)
            .border(1.dp, DesignTokens.outline.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(toolTitle(message.toolName), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (args.isNotBlank()) args else when (message.toolOk) { true -> "Готово"; false -> "Ошибка"; null -> "Выполняется" },
                    color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (message.toolOk == null) CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 1.5.dp)
            else Text(if (message.toolOk) "Готово" else "Ошибка", color = color, fontSize = 10.sp)
            Spacer(Modifier.width(4.dp))
            Icon(if (message.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (message.expanded) "Свернуть результат" else "Развернуть результат", tint = Muted, modifier = Modifier.size(20.dp))
        }
        if (message.expanded) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(message.toolName.orEmpty(), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    if (args.isNotBlank()) Text(args, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                    replacementPreview(message.toolName, message.toolArgs)?.let { preview ->
                        Text("Запрошенная замена", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(preview, color = DesignTokens.textHigh, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Mono)
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            message.content, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun toolTitle(name: String?): String = when (name) {
    "list_dir" -> "Просмотр папки"
    "read_file" -> "Чтение файла"
    "write_file" -> "Запись файла"
    "edit_file" -> "Изменение файла"
    "delete_path" -> "Удаление файла или папки"
    "move_path" -> "Перемещение файла или папки"
    "run_command" -> "Команда в терминале"
    "grep", "glob" -> "Поиск в проекте"
    "web_fetch" -> "Загрузка страницы"
    "todo_write" -> "План задачи"
    "device_info" -> "Информация об устройстве"
    else -> name ?: "Инструмент"
}

@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    provider: Provider,
    busy: Boolean,
    onModelClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    onReasoningLevelChange: (ReasoningLevel) -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DesignTokens.chatPanel)
                .border(1.dp, DesignTokens.outline, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 37.dp, max = 150.dp).padding(end = 6.dp)
                    .semantics { contentDescription = "Текст сообщения" }.testTag("chat-input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 21.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text("Спросите о своём проекте…", color = Muted, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            val capability = ModelReasoningCatalog.capability(provider)
            if (capability != null) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Text("Рассуждение: ${reasoningLevel.label}", fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ReasoningLevel.entries.forEach { level ->
                            DropdownMenuItem(text = { Text(level.label) }, onClick = {
                                onReasoningLevelChange(level)
                                menuOpen = false
                            })
                        }
                    }
                }
                if (ModelReasoningCatalog.toolsRestricted(provider, provider.model, reasoningLevel)) {
                    Text("DeepSeek: инструменты отключены, пока включено рассуждение.", color = Muted, fontSize = 10.sp)
                } else if (capability.alwaysOn && reasoningLevel == ReasoningLevel.OFF) {
                    Text("Модель рассуждает всегда; используется уровень по умолчанию.", color = Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("chat-composer-actions")) {
                val maxChipWidth = (maxWidth - 48.dp).coerceAtLeast(0.dp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.widthIn(max = maxChipWidth).heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(12.dp)).background(SurfaceHigh)
                            .clickable(onClick = onModelClick)
                            .semantics { contentDescription = "Выбрать модель" }
                            .testTag("chat-model-picker").padding(start = 11.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f, fill = false)) {
                            Text(provider.model.ifBlank { "Выберите модель" }, color = DesignTokens.textHigh, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (busy) "${provider.name} · следующий запрос" else provider.name, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ExpandMore, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    val enabled = busy || value.isNotBlank()
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (busy) Danger.copy(alpha = 0.2f) else if (enabled) Accent else DesignTokens.disabledControl)
                            .clickable(enabled = enabled, role = SemanticsRole.Button) { if (busy) onStop() else onSend() }
                            .semantics { contentDescription = if (busy) "Остановить ответ" else "Отправить сообщение" }
                            .testTag("chat-send"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = if (busy) Danger else if (enabled) DesignTokens.onAccent else Muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsSheet(
    sessions: List<SessionSummary>,
    query: String,
    activeId: String?,
    busy: Boolean,
    formatTime: (Long) -> String,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var pendingRename by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<SessionSummary?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), containerColor = DesignTokens.chatPanel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text("История чатов", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (busy) "Сначала остановите текущую задачу" else "Ваши диалоги на этом устройстве", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                label = { Text("Поиск по истории") },
                modifier = Modifier.fillMaxWidth().testTag("session-search")
            )
            TextButton(onClick = onNew, enabled = !busy) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text("  Новый чат")
            }
            if (sessions.isEmpty()) {
                Text(if (query.isBlank()) "Пока нет сохранённых чатов" else "Ничего не найдено", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (session.id == activeId) Accent.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable(enabled = !busy) { onOpen(session.id) }.padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(formatTime(session.updatedAt), color = Muted, fontSize = 11.sp)
                            }
                            IconButton(onClick = { pendingRename = session; renameText = session.title }, enabled = !busy) {
                                Icon(Icons.Outlined.Edit, "Переименовать чат ${session.title}", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { pendingExport = session }, enabled = !busy) {
                                Icon(Icons.Outlined.FileUpload, "Экспортировать чат ${session.title}", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { pendingDelete = session }, enabled = !busy) {
                                Icon(Icons.Outlined.DeleteOutline, "Удалить чат ${session.title}", tint = if (busy) Muted else Danger, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    pendingRename?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(120) }, singleLine = true,
                    label = { Text("Название") }, modifier = Modifier.testTag("session-rename"))
            },
            confirmButton = { TextButton(onClick = { onRename(session.id, renameText); pendingRename = null }, enabled = renameText.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Отмена") } }
        )
    }
    pendingExport?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingExport = null },
            title = { Text("Экспортировать чат?") },
            text = { Text("Markdown-файл содержит сообщения, рассуждения и результаты инструментов. В них могут быть секреты. Выберите безопасное место для сохранения.") },
            confirmButton = { TextButton(onClick = { onExport(session.id); pendingExport = null }) { Text("Выбрать файл") } },
            dismissButton = { TextButton(onClick = { pendingExport = null }) { Text("Отмена") } }
        )
    }
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${session.title}» будет удалён с этого устройства. Это действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = { onDelete(session.id); pendingDelete = null }) { Text("Удалить", color = Danger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } }
        )
    }
}
