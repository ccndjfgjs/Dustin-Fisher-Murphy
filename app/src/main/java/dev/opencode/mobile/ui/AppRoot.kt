package dev.opencode.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val ui = vm.ui
    val snackbar = remember { SnackbarHostState() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(ui.toast) {
        val msg = ui.toast
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    var storageAsked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val needsStorage = if (Build.VERSION.SDK_INT >= 30) {
        !Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ui.screen != Screen.EDITOR && !(ui.screen == Screen.CHAT && keyboardVisible)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BottomItem("Чат", Icons.Outlined.Chat, ui.screen == Screen.CHAT) { vm.navigate(Screen.CHAT) }
                    BottomItem("Файлы", Icons.Outlined.Folder, ui.screen == Screen.WORKSPACE) { vm.navigate(Screen.WORKSPACE) }
                    BottomItem("Терминал", Icons.Outlined.Terminal, ui.screen == Screen.TERMINAL) { vm.navigate(Screen.TERMINAL) }
                    BottomItem("Настройки", Icons.Outlined.Settings, ui.screen == Screen.SETTINGS) { vm.navigate(Screen.SETTINGS) }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (ui.screen) {
                Screen.CHAT -> ChatScreen(vm)
                Screen.WORKSPACE -> WorkspaceScreen(vm)
                Screen.TERMINAL -> TerminalScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.EDITOR -> EditorScreen(vm)
            }
        }
    }

    val storageError = ui.storageError
    if (storageError != null) {
        var dismissed by rememberSaveable(storageError) { mutableStateOf(false) }
        if (!dismissed) {
            AlertDialog(
                onDismissRequest = { dismissed = true },
                title = { Text("Ошибка защиты настроек") },
                text = { Text(storageError) },
                confirmButton = { TextButton(onClick = { dismissed = true }) { Text("Понятно") } }
            )
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
                    if (Build.VERSION.SDK_INT >= 30) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        if (runCatching { context.startActivity(intent) }.isFailure) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        }
                    } else {
                        storagePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { storageAsked = true }) { Text("Позже") }
            }
        )
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) Accent else Muted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}
