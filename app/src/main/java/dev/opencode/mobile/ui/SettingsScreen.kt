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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ai.ProviderRequestPolicy
import dev.opencode.mobile.ai.ProviderUrlPolicy
import dev.opencode.mobile.data.McpServer
import dev.opencode.mobile.data.ModelPricing
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ProviderPreset
import dev.opencode.mobile.shell.Shell

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ui = vm.ui
    val settings = ui.settings
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Provider?>(null) }
    var creating by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<ProviderPreset?>(null) }
    var newProvider by remember { mutableStateOf<Provider?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServer?>(null) }
    var creatingMcp by remember { mutableStateOf(false) }
    var deletingMcp by remember { mutableStateOf<McpServer?>(null) }

    LaunchedEffect(ui.openProviderCatalog) {
        if (ui.openProviderCatalog) {
            showCatalog = true
            vm.consumeProviderCatalogRequest()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader(title = "Настройки", subtitle = "OpenCode Mobile 0.3.1")
        ui.storageError?.let { error ->
            Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
        }

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
            TextButton(onClick = { showCatalog = true }) {
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
            subtitle = "Не спрашивать перед файловыми изменениями; shell, Git-коммит и MCP всегда требуют подтверждения",
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

        Section("MCP · Streamable HTTP")
        settings.mcpServers.forEach { server ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(12.dp)).background(SurfaceColor).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, fontSize = 13.sp)
                    Text(if (server.enabled) server.url else "Выключен · ${server.url}", color = Muted, fontSize = 10.sp, maxLines = 1)
                }
                Switch(checked = server.enabled, onCheckedChange = { enabled ->
                    vm.saveSettings { it.copy(mcpServers = it.mcpServers.map { item -> if (item.id == server.id) item.copy(enabled = enabled) else item }) }
                })
                IconButton(onClick = { editingMcp = server }) { Icon(Icons.Default.Edit, "Изменить MCP ${server.name}") }
                IconButton(onClick = { deletingMcp = server }) {
                    Icon(Icons.Default.Delete, "Удалить MCP ${server.name}")
                }
            }
        }
        TextButton(onClick = { creatingMcp = true }, modifier = Modifier.padding(horizontal = 12.dp), enabled = settings.mcpServers.size < 5) {
            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text(" Добавить MCP-сервер")
        }
        Text("Подключайте только доверенные серверы. Вызовы MCP и все изменяющие действия при активном MCP требуют подтверждения. В режиме плана подключение заблокировано.",
            color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))

        Section("Git")
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            OutlinedTextField(value = settings.gitExecutable, onValueChange = { value -> vm.saveSettings { it.copy(gitExecutable = value) } },
                label = { Text("Исполняемый файл Git") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("git-executable"))
            Text("Git должен быть отдельно установлен и доступен приложению. Коммит только из индекса, без hooks и подписи, с отдельным подтверждением.",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }

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
                "Каталог содержит Alibaba Cloud, Dahl, Space Bunny, AgentRouter и другие совместимые API. " +
                    "Ключи хранятся только на устройстве.",
                fontSize = 12.sp,
                color = Muted
            )
        }
        Spacer(Modifier.height(80.dp))
    }

    if (showCatalog) {
        ProviderCatalogSheet(
            onDismiss = { showCatalog = false },
            onPreset = { preset ->
                selectedPreset = preset
                newProvider = preset.createProvider()
                showCatalog = false
                creating = true
            },
            onCustom = {
                selectedPreset = null
                newProvider = null
                showCatalog = false
                creating = true
            }
        )
    }
    if (creating) {
        ProviderDialog(provider = newProvider, isNew = true, note = selectedPreset?.note.orEmpty(), onDismiss = { creating = false }) { provider ->
            vm.addProvider(provider)
            creating = false
        }
    }
    editing?.let { provider ->
        ProviderDialog(provider = provider, isNew = false, onDismiss = { editing = null }) { updated ->
            vm.updateProvider(updated)
            editing = null
        }
    }
    if (creatingMcp || editingMcp != null) {
        McpDialog(server = editingMcp, onDismiss = { creatingMcp = false; editingMcp = null }) { server ->
            vm.saveSettings { settings ->
                val others = settings.mcpServers.filterNot { it.id == server.id }
                settings.copy(mcpServers = (others + server).take(5))
            }
            creatingMcp = false
            editingMcp = null
        }
    }
    deletingMcp?.let { server ->
        AlertDialog(onDismissRequest = { deletingMcp = null }, title = { Text("Удалить MCP-сервер?") },
            text = { Text("Настройки и сохранённый токен «${server.name}» будут удалены.") },
            confirmButton = { TextButton(onClick = {
                vm.saveSettings { it.copy(mcpServers = it.mcpServers.filterNot { item -> item.id == server.id }) }
                deletingMcp = null
            }) { Text("Удалить", color = Danger) } },
            dismissButton = { TextButton(onClick = { deletingMcp = null }) { Text("Отмена") } })
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
internal fun McpDialog(server: McpServer?, onDismiss: () -> Unit, onSave: (McpServer) -> Unit) {
    var name by remember(server?.id) { mutableStateOf(server?.name.orEmpty()) }
    var url by remember(server?.id) { mutableStateOf(server?.url ?: "https://") }
    var token by remember(server?.id) { mutableStateOf(server?.bearerToken.orEmpty()) }
    var visible by remember(server?.id) { mutableStateOf(false) }
    val error = if (url.isBlank()) null else ProviderUrlPolicy.error(url, token)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (server == null) "Добавить MCP-сервер" else "Настроить MCP-сервер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Streamable HTTP, версия 2025-03-26. Сервер получает аргументы разрешённых вами инструментов.", fontSize = 11.sp, color = Muted)
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("mcp-name"))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL endpoint") }, singleLine = true,
                    isError = error != null, modifier = Modifier.fillMaxWidth().testTag("mcp-url"))
                if (error != null) Text(error, color = Danger, fontSize = 11.sp)
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Bearer-токен (необязательно)") }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password, autoCorrect = false),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "Скрыть токен MCP" else "Показать токен MCP")
                    } }, modifier = Modifier.fillMaxWidth().testTag("mcp-token"))
                Text("Токен хранится в зашифрованных настройках и не отправляется по HTTP. OAuth, stdio, ресурсы и prompts пока не поддерживаются.", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && url.isNotBlank() && error == null,
            onClick = { onSave((server ?: McpServer(name = name.trim(), url = url.trim())).copy(name = name.trim(), url = url.trim(), bearerToken = token.trim())) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
internal fun ProviderDialog(
    provider: Provider?,
    isNew: Boolean,
    note: String = "",
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit
) {
    var name by remember(provider?.id) { mutableStateOf(provider?.name ?: "") }
    var baseUrl by remember(provider?.id) { mutableStateOf(provider?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember(provider?.id) { mutableStateOf(provider?.apiKey ?: "") }
    var keyVisible by remember(provider?.id) { mutableStateOf(false) }
    var clipboardHint by remember(provider?.id) { mutableStateOf<String?>(null) }
    var model by remember(provider?.id) { mutableStateOf(provider?.model ?: "") }
    var kind by remember(provider?.id) { mutableStateOf(provider?.kind ?: "openai") }
    var streamUsage by remember(provider?.id) {
        mutableStateOf(provider?.streamUsage ?: ProviderRequestPolicy.defaultStreamUsage(provider?.baseUrl ?: baseUrl))
    }
    val currentPricing = provider?.pricing?.firstOrNull {
        it.model == model.trim() && provider?.baseUrl.orEmpty().trimEnd('/') == baseUrl.trim().trimEnd('/') && provider?.kind == kind
    }
    var inputRate by remember(provider?.id, model, baseUrl, kind) { mutableStateOf(currentPricing?.input?.toString().orEmpty()) }
    var outputRate by remember(provider?.id, model, baseUrl, kind) { mutableStateOf(currentPricing?.output?.toString().orEmpty()) }
    var cachedRate by remember(provider?.id, model, baseUrl, kind) { mutableStateOf(currentPricing?.cachedInput?.toString().orEmpty()) }
    var writeRate by remember(provider?.id, model, baseUrl, kind) { mutableStateOf(currentPricing?.cacheWrite?.toString().orEmpty()) }
    fun rate(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val ratesBlank = listOf(inputRate, outputRate, cachedRate, writeRate).all { it.isBlank() }
    val ratesValid = ratesBlank || (rate(inputRate) != null && rate(outputRate) != null &&
        (cachedRate.isBlank() || rate(cachedRate) != null) && (writeRate.isBlank() || rate(writeRate) != null))
    val clipboard = LocalClipboardManager.current
    val urlError = if (baseUrl.isBlank()) null else ProviderUrlPolicy.error(baseUrl, apiKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новый провайдер" else "Настроить провайдера") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (note.isNotBlank()) Text(note, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth().testTag("provider-name"), label = { Text("Название") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("OpenAI-совместимый", color = if (kind == "openai") Accent else Muted, onClick = { kind = "openai" })
                    Chip("Anthropic", color = if (kind == "anthropic") Accent else Muted, onClick = { kind = "anthropic" })
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth().testTag("provider-url"),
                    label = { Text("Base URL") }, singleLine = true, isError = urlError != null
                )
                if (urlError != null) Text(urlError, color = Danger, fontSize = 11.sp, lineHeight = 15.sp)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; clipboardHint = null },
                    modifier = Modifier.fillMaxWidth().testTag("provider-key"),
                    label = { Text("API-ключ") },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (keyVisible) KeyboardType.Text else KeyboardType.Password,
                        autoCorrect = false
                    ),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) "Скрыть API-ключ" else "Показать API-ключ"
                            )
                        }
                    },
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        // Clipboard access is explicit and limited to this foreground action.
                        val text = runCatching { clipboard.getText()?.text?.trim() }.getOrNull()
                        if (text.isNullOrEmpty()) {
                            clipboardHint = "Не удалось вставить ключ: скопируйте текст и попробуйте снова. В эмуляторе проверьте синхронизацию буфера обмена."
                        } else {
                            apiKey = text
                            clipboardHint = null
                        }
                    },
                    modifier = Modifier.testTag("paste-provider-key")
                ) { Text("Вставить из буфера") }
                clipboardHint?.let { Text(it, color = Danger, fontSize = 11.sp, lineHeight = 15.sp) }
                OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth().testTag("provider-model"), label = { Text("Модель") }, singleLine = true)
                if (kind == "openai") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Запрашивать токены в потоке", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = streamUsage, onCheckedChange = { streamUsage = it })
                    }
                    Text("Для несовместимых серверов отключите stream_options или используйте ответ без потока.", color = Muted, fontSize = 10.sp)
                }
                Text("Тариф этой модели · USD за 1 млн токенов (необязательно)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("Введите актуальные цены провайдера. Расчёт — оценка, не счёт; подписки, налоги и специальные тарифы не учитываются.", color = Muted, fontSize = 10.sp)
                OutlinedTextField(value = inputRate, onValueChange = { inputRate = it }, singleLine = true,
                    label = { Text("Вход, $ / 1 млн") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("price-input"))
                OutlinedTextField(value = outputRate, onValueChange = { outputRate = it }, singleLine = true,
                    label = { Text("Выход, $ / 1 млн") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("price-output"))
                OutlinedTextField(value = cachedRate, onValueChange = { cachedRate = it }, singleLine = true,
                    label = { Text("Чтение кэша, $ / 1 млн") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = writeRate, onValueChange = { writeRate = it }, singleLine = true,
                    label = { Text("Запись кэша, $ / 1 млн") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (!ratesValid) Text("Заполните цены входа и выхода неотрицательными числами.", color = Danger, fontSize = 11.sp)
                Text(
                    "Адрес и ID модели можно изменить. HTTP разрешён только для локального сервера и без API-ключа; данные при этом не шифруются.",
                    fontSize = 10.sp, color = Muted, lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && urlError == null && ratesValid,
                onClick = {
                    val previousPrices = if (provider != null && provider.baseUrl.trimEnd('/') == baseUrl.trim().trimEnd('/') && provider.kind == kind)
                        provider.pricing else emptyList()
                    val prices = previousPrices.filterNot { it.model == model.trim() }.toMutableList()
                    if (!ratesBlank) prices.add(ModelPricing(model.trim(), rate(inputRate)!!, rate(outputRate)!!, rate(cachedRate), rate(writeRate)))
                    val result = (provider ?: Provider(name = name.trim(), baseUrl = baseUrl.trim(), model = model.trim()))
                        .withModel(model).copy(name = name.trim(), baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), kind = kind,
                            pricing = prices, streamUsage = streamUsage)
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
