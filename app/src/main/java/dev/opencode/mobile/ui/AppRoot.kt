package dev.opencode.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.R

@Composable
fun AppRoot(vm: AppViewModel) {
    val ui = vm.ui
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.toast) {
        val msg = ui.toast
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    var storageAsked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val needsStorage = Build.VERSION.SDK_INT >= 30 &&
        !Environment.isExternalStorageManager()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ui.screen != Screen.EDITOR) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    BottomItem("Чат", Icons.Outlined.Chat, ui.screen == Screen.CHAT) { vm.navigate(Screen.CHAT) }
                    BottomItem("Файлы", Icons.Outlined.Folder, ui.screen == Screen.WORKSPACE) { vm.navigate(Screen.WORKSPACE) }
                    BottomItem("Терминал", Icons.Outlined.Terminal, ui.screen == Screen.TERMINAL) { vm.navigate(Screen.TERMINAL) }
                    BottomItem("Настройки", Icons.Outlined.Settings, ui.screen == Screen.SETTINGS) { vm.navigate(Screen.SETTINGS) }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (ui.screen) {
                Screen.CHAT -> ChatScreen(vm)
                Screen.WORKSPACE -> WorkspaceScreen(vm)
                Screen.TERMINAL -> TerminalScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.EDITOR -> EditorScreen(vm)
            }
        }
    }

    if (ui.approval != null) {
        val call = ui.approval.call
        AlertDialog(
            onDismissRequest = { vm.approve(false) },
            title = { Text("Разрешить инструмент?") },
            text = {
                Column {
                    Text(call.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        vm.currentToolCallArgs(call.name, call.arguments),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.approve(true) }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { vm.approve(false) }) { Text("Отклонить") }
            }
        )
    }

    if (needsStorage && !storageAsked) {
        AlertDialog(
            onDismissRequest = { storageAsked = true },
            title = { Text("Доступ к файлам") },
            text = {
                Text(
                    "Чтобы работать с проектами в /sdcard (git, gradle, редактор), приложению нужен доступ ко всем файлам. " +
                        "Выдай разрешение в настройках — или продолжай: рабочая папка будет внутри приложения."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    storageAsked = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { storageAsked = true }) { Text("Позже") }
            }
        )
    }
}

@Composable
private fun BottomItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Accent,
            selectedTextColor = Accent,
            indicatorColor = Accent.copy(alpha = 0.15f),
            unselectedIconColor = Muted,
            unselectedTextColor = Muted
        )
    )
}
