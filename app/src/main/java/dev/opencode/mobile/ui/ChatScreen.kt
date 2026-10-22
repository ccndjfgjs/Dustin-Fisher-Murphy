package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.TodoItem

@Composable
fun ChatScreen(vm: AppViewModel) {
    val ui = vm.ui
    val session: ChatSession? = ui.session
    var input by rememberSaveable(session?.id) { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messages = session?.messages ?: emptyList()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = session?.title ?: "OpenCode",
            subtitle = ui.workspace
        ) {
            IconBubble(Icons.Default.Add, onClick = { vm.newSession() })
            IconBubble(Icons.Default.List, onClick = { showSessions = true })
            IconBubble(Icons.Outlined.FolderOpen, onClick = { vm.navigate(Screen.WORKSPACE) })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val provider = ui.settings.activeProvider
            Chip(provider.model.ifBlank { "модель не выбрана" }, onClick = { vm.navigate(Screen.SETTINGS) })
            Chip(
                text = if (ui.root?.available == true) "root" else "shell",
                color = if (ui.root?.available == true) Success else Muted,
                onClick = { vm.navigate(Screen.TERMINAL) }
            )
            if (ui.settings.autoApproveTools) Chip("auto", color = Accent2)
            Spacer(Modifier.weight(1f))
            if (ui.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Accent
                )
            }
        }

        if (ui.todos.isNotEmpty()) {
            TodoCard(ui.todos)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyChatHint() }
            }
            items(messages.size) { index ->
                MessageItem(messages[index], vm)
            }
        }

        if (ui.status.isNotBlank()) {
            Text(
                ui.status,
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            busy = ui.busy,
            onSend = {
                if (input.isNotBlank()) {
                    vm.sendMessage(input)
                    input = ""
                }
            },
            onStop = { vm.stop() }
        )
    }

    if (showSessions) {
        SessionsDialog(vm, onClose = { showSessions = false })
    }
}

@Composable
private fun TodoCard(todos: List<TodoItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("План", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        todos.forEach { todo ->
            val mark = when (todo.status) {
                "completed" -> "✔"
                "in_progress" -> "▶"
                else -> "○"
            }
            val color = when (todo.status) {
                "completed" -> Success
                "in_progress" -> Accent2
                else -> Muted
            }
            Text("$mark ${todo.content}", fontSize = 12.sp, color = color)
        }
    }
}

@Composable
private fun EmptyChatHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("opencode", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text(
            "ИИ-агент для Android с доступом к файлам и shell. Опиши задачу — он исследует проект, " +
                "внесёт правки, соберёт и проверит результат.",
            color = Muted,
            fontSize = 14.sp
        )
        Text(
            "Примеры:\n• Прочитай проект и исправь ошибку сборки\n• Напиши Telegram-бота на Python и запусти его\n• Сделай git commit и покажи diff\n• Установи пакет через apt/pip и настрой окружение",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage, vm: AppViewModel) {
    when (message.role) {
        Role.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Color(0xFF25304D))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(message.content, fontSize = 14.sp)
            }
        }

        Role.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            if (message.content.isBlank() && message.streaming) {
                Text("▍", color = Accent)
            } else {
                MarkdownText(message.content)
            }
            if (message.toolCalls.isNotEmpty()) {
                Text(
                    "→ " + message.toolCalls.joinToString(", ") { it.name },
                    color = Muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Role.TOOL -> ToolCard(message, vm)
    }
}

@Composable
private fun ToolCard(message: ChatMessage, vm: AppViewModel) {
    val ok = message.toolOk
    val accent = when (ok) {
        true -> Success
        false -> Danger
        null -> Accent2
    }
    val running = ok == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .clickable { vm.toggleMessage(message.id) }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp)
            )
            Text(
                " ${message.toolName ?: "tool"}",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
            } else {
                Text(if (ok == true) "ok" else "ошибка", color = accent, fontSize = 10.sp)
            }
        }
        val args = vm.currentToolCallArgs(message.toolName ?: "", message.toolArgs)
        if (args.isNotBlank()) {
            Text(args, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        val output = message.content
        if (output.isNotBlank()) {
            val shown = if (message.expanded) output else output.take(500)
            Text(
                shown,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (!message.expanded && output.length > 500) {
                Text("… показать полностью", color = Accent, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Спроси opencode…", color = Muted, fontSize = 14.sp) },
            maxLines = 6,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = SurfaceColor,
                unfocusedContainerColor = SurfaceColor
            )
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (busy) Danger.copy(alpha = 0.16f) else Accent.copy(alpha = 0.16f))
                .clickable { if (busy) onStop() else onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (busy) Icons.Default.Close else Icons.Default.Send,
                contentDescription = null,
                tint = if (busy) Danger else Accent
            )
        }
    }
}

@Composable
private fun SessionsDialog(vm: AppViewModel, onClose: () -> Unit) {
    val ui = vm.ui
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Сессии") },
        text = {
            Column {
                TextButton(onClick = { vm.newSession(); onClose() }) {
                    Text("+ Новая сессия")
                }
                if (ui.sessions.isEmpty()) {
                    Text("Пока пусто", color = Muted, fontSize = 12.sp)
                }
                ui.sessions.take(30).forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.openSession(s.id); onClose() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.title, fontSize = 13.sp, maxLines = 1)
                            Text(vm.formatTime(s.updatedAt), color = Muted, fontSize = 10.sp)
                        }
                        IconBubble(Icons.Default.Delete, tint = Danger, onClick = { vm.deleteSession(s.id) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}
