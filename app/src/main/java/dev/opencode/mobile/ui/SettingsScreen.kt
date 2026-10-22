package dev.opencode.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.shell.Shell

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ui = vm.ui
    val settings = ui.settings
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Provider?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(title = "Настройки", subtitle = "OpenCode Mobile 0.1.0")

        Section("Провайдер и модель")
        settings.providers.forEach { provider ->
            ProviderRow(
                provider = provider,
                active = provider.id == settings.activeProviderId,
                onSelect = { vm.setActiveProvider(provider.id) },
                onEdit = { editing = provider },
                onDelete = { vm.deleteProvider(provider.id) }
            )
        }
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" Добавить провайдера", fontSize = 12.sp)
            }
        }
        ClickRow(
            title = "Активная модель",
            value = settings.activeProvider.model,
            onClick = { showModels = true }
        )

        Section("Агент")
        SwitchRow(
            title = "Root-доступ (su)",
            subtitle = "Выполнять команды и правки от uid=0 через Magisk/KernelSU",
            checked = settings.allowRoot,
            onChange = { value -> vm.saveSettings { it.copy(allowRoot = value) } }
        )
        SwitchRow(
            title = "Авто-подтверждение",
            subtitle = "Не спрашивать перед изменением файлов и командами",
            checked = settings.autoApproveTools,
            onChange = { value -> vm.saveSettings { it.copy(autoApproveTools = value) } }
        )
        SwitchRow(
            title = "Потоковый ответ",
            subtitle = "Показывать текст по мере генерации",
            checked = settings.streaming,
            onChange = { value -> vm.saveSettings { it.copy(streaming = value) } }
        )
        NumberRow(
            title = "Лимит шагов агента",
            value = settings.maxIterations,
            onChange = { value -> vm.saveSettings { it.copy(maxIterations = value) } }
        )
        TextBlock(
            title = "Дополнительные инструкции",
            value = settings.systemAppend,
            placeholder = "Например: используй Python 3, пиши комментарии по-русски…",
            onChange = { value -> vm.saveSettings { it.copy(systemAppend = value) } }
        )

        Section("Устройство")
        RootSettingsCard(vm)
        Spacer(Modifier.height(8.dp))
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = Environment.isExternalStorageManager()
            ClickRow(
                title = "Доступ ко всем файлам",
                value = if (manager) "разрешён" else "нажми, чтобы выдать",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(intent) }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        ClickRow(
            title = "Рабочая папка",
            value = ui.workspace,
            onClick = { vm.navigate(Screen.WORKSPACE) }
        )

        Section("О приложении")
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                "OpenCode Mobile — ИИ-агент для Android в духе opencode: читает и правит файлы, " +
                    "запускает команды, собирает проекты. Root-функции работают через su (Magisk/KernelSU).",
                fontSize = 12.sp,
                color = Muted
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Провайдеры: OpenAI, Anthropic, OpenRouter, Ollama/LM Studio (локальные модели). " +
                    "Ключи хранятся только на устройстве.",
                fontSize = 12.sp,
                color = Muted
            )
        }
        Spacer(Modifier.height(80.dp))
    }

    if (creating) {
        ProviderDialog(provider = null, onDismiss = { creating = false }) { provider ->
            vm.addProvider(provider)
            creating = false
        }
    }
    editing?.let { provider ->
        ProviderDialog(provider = provider, onDismiss = { editing = null }) { updated ->
            vm.updateProvider(updated)
            editing = null
        }
    }
    if (showModels) {
        ModelDialog(
            provider = settings.activeProvider,
            onDismiss = { showModels = false },
            onPick = { model ->
                vm.setModel(model)
                showModels = false
            }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun ProviderRow(
    provider: Provider,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Accent.copy(alpha = 0.12f) else SurfaceColor)
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(provider.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${provider.kind} · ${provider.model}",
                fontSize = 10.sp,
                color = Muted,
                maxLines = 1
            )
        }
        if (active) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
        }
        IconBubble(Icons.Default.Edit, tint = Muted, onClick = onEdit)
        Spacer(Modifier.size(6.dp))
        IconBubble(Icons.Default.Delete, tint = Danger, onClick = onDelete)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(subtitle, fontSize = 10.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconBubble(Icons.Default.Remove, tint = Muted, onClick = { onChange((value - 5).coerceAtLeast(1)) })
        Text("$value", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
        IconBubble(Icons.Default.Add, tint = Muted, onClick = { onChange((value + 5).coerceAtMost(100)) })
    }
}

@Composable
private fun TextBlock(title: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(title, fontSize = 12.sp) },
            placeholder = { Text(placeholder, fontSize = 11.sp, color = Muted) },
            minLines = 2,
            maxLines = 6
        )
    }
}

@Composable
private fun ClickRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = Accent, maxLines = 1)
    }
}

@Composable
private fun RootSettingsCard(vm: AppViewModel) {
    val root = vm.ui.root
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Root", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    vm.ui.checkingRoot -> "проверка…"
                    root?.available == true -> "доступен · ${root.manager}"
                    root == null -> "не проверено"
                    else -> "нет доступа"
                },
                fontSize = 11.sp,
                color = when {
                    root?.available == true -> Success
                    root == null -> Muted
                    else -> Danger
                }
            )
        }
        if (root?.available == true) {
            Text("su ${root.version} · SELinux ${root.selinux}", fontSize = 10.sp, color = Muted)
        } else {
            Text(
                "Установи Magisk/KernelSU и выдай приложению su — тогда агент сможет менять системные файлы, " +
                    "ставить пакеты и управлять сервисами.",
                fontSize = 10.sp,
                color = Muted
            )
        }
        TextButton(onClick = { vm.checkRoot(true) }) {
            Text("Проверить снова", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProviderDialog(provider: Provider?, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    var name by remember { mutableStateOf(provider?.name ?: "") }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var model by remember { mutableStateOf(provider?.model ?: "") }
    var kind by remember { mutableStateOf(provider?.kind ?: "openai") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) "Новый провайдер" else "Провайдер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        "OpenAI-совместимый",
                        color = if (kind == "openai") Accent else Muted,
                        onClick = { kind = "openai" }
                    )
                    Chip(
                        "Anthropic",
                        color = if (kind == "anthropic") Accent else Muted,
                        onClick = { kind = "anthropic" }
                    )
                }
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API-ключ") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Модель") }, singleLine = true)
                Text(
                    "Локальная модель: Ollama → http://127.0.0.1:11434/v1, LM Studio → http://IP:1234/v1",
                    fontSize = 10.sp,
                    color = Muted
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                onClick = {
                    val result = (provider ?: Provider(
                        name = name,
                        baseUrl = baseUrl,
                        model = model
                    )).copy(
                        name = name.trim(),
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        kind = kind
                    )
                    onSave(result)
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ModelDialog(provider: Provider, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf(provider.model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Модель · ${provider.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                provider.models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(model) }
                            .padding(vertical = 9.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(model, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (model == provider.model) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Своя модель") },
                    singleLine = true
                )
                TextButton(onClick = { if (custom.isNotBlank()) onPick(custom.trim()) }) {
                    Text("Использовать «$custom»", fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
