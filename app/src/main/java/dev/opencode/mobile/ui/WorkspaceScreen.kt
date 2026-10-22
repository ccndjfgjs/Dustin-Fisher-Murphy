package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.FileEntry

@Composable
fun WorkspaceScreen(vm: AppViewModel) {
    val ui = vm.ui
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "Файлы", subtitle = ui.workspace) {
            IconBubble(Icons.Default.ArrowBack, onClick = { vm.goUp() })
            IconBubble(Icons.Default.Refresh, onClick = { vm.refreshEntries() })
            IconBubble(Icons.Default.Add, onClick = { showNewFile = true })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("+ файл", onClick = { showNewFile = true })
            Chip("+ папка", onClick = { showNewFolder = true })
            Chip("новая сессия", color = Accent2, onClick = { showNewSession = true })
            Spacer(Modifier.weight(1f))
            Chip("чат →", onClick = { vm.navigate(Screen.CHAT) })
        }

        if (ui.root?.available == true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Success.copy(alpha = 0.08f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = Success, modifier = Modifier.size(15.dp))
                Text(
                    "  root доступен (${ui.root.manager}) — агент может менять системные файлы",
                    fontSize = 11.sp,
                    color = Success
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (ui.entries.isEmpty()) {
                item { Text("Папка пуста", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
            }
            items(ui.entries.size) { index ->
                EntryRow(ui.entries[index], vm)
            }
        }
    }

    if (showNewFile) {
        NameDialog(title = "Новый файл", onDismiss = { showNewFile = false }) { name ->
            vm.createFile(name)
            showNewFile = false
        }
    }
    if (showNewFolder) {
        NameDialog(title = "Новая папка", onDismiss = { showNewFolder = false }) { name ->
            vm.createFolder(name)
            showNewFolder = false
        }
    }
    if (showNewSession) {
        AlertDialog(
            onDismissRequest = { showNewSession = false },
            title = { Text("Новая сессия") },
            text = { Text("Агент будет работать в папке:\n${ui.workspace}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.newSession()
                    vm.navigate(Screen.CHAT)
                    showNewSession = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showNewSession = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun EntryRow(entry: FileEntry, vm: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                if (entry.isDir) vm.openWorkspace(entry.path) else vm.openFile(entry.path)
            }
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (entry.isDir) Accent2 else Muted,
            modifier = Modifier.size(19.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!entry.isDir) {
                Text(humanSize(entry.size), fontSize = 10.sp, color = Muted)
            }
        }
        Text(
            if (entry.isDir) "→" else "edit",
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.clickable {
                if (!entry.isDir) vm.openFile(entry.path)
            }
        )
    }
}

@Composable
fun NameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Имя") },
                placeholder = { Text("например, main.py") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
