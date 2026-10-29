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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalScreen(vm: AppViewModel) {
    val ui = vm.ui
    val clipboard = LocalClipboardManager.current
    var command by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.terminalLog.size) {
        if (ui.terminalLog.isNotEmpty()) listState.animateScrollToItem(ui.terminalLog.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Терминал", subtitle = ui.workspace) {
            IconButton(
                enabled = ui.terminalLog.isNotEmpty(),
                onClick = { clipboard.setText(AnnotatedString(ui.terminalLog.joinToString("\n") { stripAnsi(it) })) }
            ) {
                Icon(Icons.Outlined.ContentCopy, "Скопировать вывод терминала", tint = Muted, modifier = Modifier.size(19.dp))
            }
            IconBubble(Icons.Default.Delete, tint = Danger, onClick = { vm.clearTerminal() })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (ui.root?.available == true) "su (root)" else "shell",
                color = if (ui.useRootTerminal && ui.root?.available == true) Success else Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.size(8.dp))
            Switch(
                checked = ui.useRootTerminal,
                onCheckedChange = { vm.toggleRootTerminal() },
                enabled = ui.root?.available == true,
                modifier = Modifier.size(width = 44.dp, height = 26.dp)
            )
            Spacer(Modifier.weight(1f))
            SelectionContainer {
                Text(
                    "cd ${ui.workspace}",
                    color = Muted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color0B0D12)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (ui.terminalLog.isEmpty()) {
                    item {
                        SelectionContainer {
                            Text(
                                "Локальный shell на устройстве.\n\n" +
                                    "• с root: любые команды от uid=0 (su)\n" +
                                    "• без root: только права приложения\n\n" +
                                    "Примеры:\n  id\n  ls -la /sdcard\n  magisk -v\n  pm list packages | head",
                                color = Muted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                items(ui.terminalLog.size) { index ->
                    val line = stripAnsi(ui.terminalLog[index])
                    SelectionContainer {
                        Text(
                            line,
                            color = if (line.startsWith("❯")) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("команда…", color = Muted, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceColor,
                    unfocusedContainerColor = SurfaceColor,
                    focusedBorderColor = Accent.copy(alpha = 0.5f)
                )
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent.copy(alpha = 0.16f))
                    .clickable {
                        if (command.isNotBlank() && !ui.terminalRunning) {
                            vm.runTerminal(command)
                            command = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (ui.terminalRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                } else {
                    Icon(Icons.Default.Send, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private val Color0B0D12 = androidx.compose.ui.graphics.Color(0xFF0B0D12)
