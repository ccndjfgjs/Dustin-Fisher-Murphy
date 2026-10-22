package dev.opencode.mobile.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.agent.AgentEngine
import dev.opencode.mobile.data.AppSettings
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.FileEntry
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.SessionSummary
import dev.opencode.mobile.data.TodoItem
import dev.opencode.mobile.data.ToolCallRef
import dev.opencode.mobile.shell.RootStatus
import dev.opencode.mobile.shell.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { WORKSPACE, CHAT, SETTINGS, TERMINAL, EDITOR }

data class PendingApproval(val call: ToolCallRef)

data class UiState(
    val screen: Screen = Screen.CHAT,
    val workspace: String = "/sdcard",
    val entries: List<FileEntry> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val session: ChatSession? = null,
    val busy: Boolean = false,
    val status: String = "",
    val approval: PendingApproval? = null,
    val todos: List<TodoItem> = emptyList(),
    val root: RootStatus? = null,
    val checkingRoot: Boolean = false,
    val terminalLog: List<String> = emptyList(),
    val terminalRunning: Boolean = false,
    val useRootTerminal: Boolean = true,
    val editorPath: String? = null,
    val editorText: String = "",
    val editorDirty: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    val settings: AppSettings = AppSettings()
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as OpenCodeApp
    private val store = application.sessions
    private val engine = AgentEngine()

    var ui by mutableStateOf(UiState(workspace = application.prefs.settings.value.lastWorkspace))
        private set

    private var job: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var pendingOutput = StringBuilder()
    private var lastChunkFlush = 0L

    init {
        ui = ui.copy(settings = application.prefs.settings.value)
        viewModelScope.launch {
            application.prefs.settings.collect { s ->
                ui = ui.copy(settings = s)
            }
        }
        viewModelScope.launch {
            val ws = withContext(Dispatchers.IO) { ensureWorkspace() }
            ui = ui.copy(workspace = ws)
            refreshEntries()
            refreshSessions()
            openOrCreateSession()
            checkRoot(true)
        }
    }

    // ---------- рабочая папка ----------

    private fun ensureWorkspace(): String {
        val candidates = mutableListOf<String>()
        val saved = application.prefs.settings.value.lastWorkspace
        if (saved.isNotBlank()) candidates.add(saved)
        candidates.add("/sdcard/OpenCode")
        ApplicationPaths.external(getApplication<Application>())?.let { candidates.add("$it/workspace") }
        candidates.add(File(getApplication<Application>().filesDir, "workspace").absolutePath)

        for (path in candidates) {
            val f = File(path)
            if (f.exists() || f.mkdirs()) {
                if (path != saved) application.prefs.update { it.copy(lastWorkspace = path) }
                return path
            }
        }
        return getApplication<Application>().filesDir.absolutePath
    }

    fun openWorkspace(path: String) {
        viewModelScope.launch {
            val f = File(path)
            if (!f.exists()) {
                ui = ui.copy(toast = "Папка не существует: $path")
                return@launch
            }
            if (!f.isDirectory) {
                openFile(path)
                return@launch
            }
            ui = ui.copy(workspace = path, screen = Screen.WORKSPACE)
            application.prefs.update { it.copy(lastWorkspace = path) }
            val session = ui.session
            if (session == null || session.messages.isEmpty()) {
                newSession()
            }
            refreshEntries()
        }
    }

    fun refreshEntries() {
        viewModelScope.launch {
            val dir = File(ui.workspace)
            val list = withContext(Dispatchers.IO) {
                dir.listFiles()?.map {
                    FileEntry(
                        name = it.name,
                        path = it.absolutePath,
                        isDir = it.isDirectory,
                        size = if (it.isFile) it.length() else 0L
                    )
                }?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) ?: emptyList()
            }
            ui = ui.copy(entries = list)
        }
    }

    fun goUp() {
        val parent = File(ui.workspace).parentFile ?: return
        openWorkspace(parent.absolutePath)
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val dir = File(ui.workspace, name)
            val ok = withContext(Dispatchers.IO) { dir.mkdirs() }
            ui = ui.copy(toast = if (ok) "Папка создана: ${dir.absolutePath}" else "Не удалось создать папку")
            refreshEntries()
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            val file = File(ui.workspace, name)
            val ok = withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.exists() || file.createNewFile()
            }
            if (ok) openFile(file.absolutePath)
            refreshEntries()
        }
    }

    // ---------- root ----------

    fun checkRoot(force: Boolean = false) {
        ui = ui.copy(checkingRoot = true)
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { Shell.rootStatus(force) }
            ui = ui.copy(root = status, checkingRoot = false)
        }
    }

    // ---------- сессии ----------

    fun refreshSessions() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            ui = ui.copy(sessions = list)
        }
    }

    private fun openOrCreateSession() {
        val lastId = application.prefs.settings.value.lastSessionId
        val loaded = lastId?.let { store.load(it) }
        if (loaded != null) {
            ui = ui.copy(session = loaded, workspace = loaded.cwd)
            refreshEntries()
        } else {
            newSession()
        }
    }

    fun newSession() {
        val session = ChatSession(cwd = ui.workspace)
        ui = ui.copy(session = session, todos = emptyList(), screen = Screen.CHAT)
        application.prefs.update { it.copy(lastSessionId = session.id) }
        store.save(session)
        refreshSessions()
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            ui = ui.copy(session = session, workspace = session.cwd, screen = Screen.CHAT, todos = emptyList())
            application.prefs.update { it.copy(lastSessionId = id, lastWorkspace = session.cwd) }
            refreshEntries()
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            if (ui.session?.id == id) newSession()
            refreshSessions()
        }
    }

    private fun setSession(session: ChatSession) {
        ui = ui.copy(session = session)
        store.save(session)
        refreshSessions()
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val session = ui.session ?: return
        val updated = session.copy(messages = transform(session.messages), updatedAt = System.currentTimeMillis())
        ui = ui.copy(session = updated)
    }

    // ---------- чат ----------

    fun sendMessage(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || ui.busy) return
        var session = ui.session ?: ChatSession(cwd = ui.workspace)
        if (session.messages.none { it.role == Role.USER }) {
            session = session.copy(title = text.take(48).replace('\n', ' '))
        }
        val userMsg = ChatMessage(role = Role.USER, content = text)
        session = session.copy(messages = session.messages + userMsg, updatedAt = System.currentTimeMillis())
        setSession(session)
        application.prefs.update { it.copy(lastSessionId = session.id) }

        val settings = application.prefs.settings.value
        val provider = settings.activeProvider
        val model = provider.model
        pendingOutput = StringBuilder()
        lastChunkFlush = 0L
        ui = ui.copy(busy = true, status = "Думаю…", screen = Screen.CHAT)

        job = viewModelScope.launch {
            try {
                engine.run(
                    provider = provider,
                    model = model,
                    history = session.messages,
                    cwd = session.cwd,
                    canUseRoot = settings.allowRoot,
                    autoApprove = settings.autoApproveTools,
                    maxIterations = settings.maxIterations,
                    systemAppend = settings.systemAppend,
                    streaming = settings.streaming,
                    listener = agentListener
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                ui = ui.copy(toast = "Остановлено")
            } finally {
                ui = ui.copy(busy = false, status = "", approval = null)
                ui.session?.let { store.save(it) }
                refreshSessions()
            }
        }
    }

    fun stop() {
        engine.cancel()
        approvalDeferred?.complete(false)
        approvalDeferred = null
        job?.cancel()
        job = null
        ui = ui.copy(busy = false, status = "", approval = null)
    }

    fun approve(allow: Boolean) {
        approvalDeferred?.complete(allow)
        approvalDeferred = null
        ui = ui.copy(approval = null)
    }

    fun toggleMessage(id: String) {
        updateMessages { list ->
            list.map { if (it.id == id) it.copy(expanded = !it.expanded) else it }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        updateMessages { it + msg }
    }

    private fun updateToolMessage(callId: String, ok: Boolean?, output: String) {
        updateMessages { list ->
            list.map {
                if (it.role == Role.TOOL && it.toolCallId == callId) it.copy(content = output, toolOk = ok, streaming = false)
                else it
            }
        }
    }

    private val agentListener = object : AgentEngine.Listener {

        override fun onStatus(text: String) {
            ui = ui.copy(status = text)
        }

        override fun onAssistantStart() {
            pendingOutput = StringBuilder()
            appendMessage(ChatMessage(role = Role.ASSISTANT, content = "", streaming = true))
        }

        override fun onAssistantDelta(text: String) {
            updateMessages { list ->
                val idx = list.indexOfLast { it.role == Role.ASSISTANT && it.streaming }
                if (idx < 0) list else list.toMutableList().also {
                    it[idx] = it[idx].copy(content = it[idx].content + text)
                }
            }
        }

        override fun onAssistantDone(message: ChatMessage) {
            updateMessages { list ->
                val idx = list.indexOfLast { it.role == Role.ASSISTANT && it.streaming }
                if (idx < 0) {
                    list + message
                } else {
                    list.toMutableList().also {
                        it[idx] = message.copy(id = it[idx].id, streaming = false, ts = it[idx].ts)
                    }
                }
            }
        }

        override fun onToolStart(call: ToolCallRef) {
            pendingOutput = StringBuilder()
            lastChunkFlush = 0L
            appendMessage(
                ChatMessage(
                    role = Role.TOOL,
                    content = "⏳ выполняется…",
                    toolCallId = call.id,
                    toolName = call.name,
                    toolArgs = call.arguments,
                    toolOk = null
                )
            )
        }

        override fun onToolEnd(call: ToolCallRef, ok: Boolean, output: String) {
            updateToolMessage(call.id, ok, output.ifBlank { "(нет вывода)" })
            pendingOutput = StringBuilder()
        }

        override fun onTodo(items: List<TodoItem>) {
            ui = ui.copy(todos = items)
        }

        override fun onCommandOutputChunk(chunk: String) {
            val now = System.currentTimeMillis()
            pendingOutput.append(chunk)
            if (now - lastChunkFlush < 250) return
            lastChunkFlush = now
            val tail = pendingOutput.toString().takeLast(4000)
            val callId = ui.session?.messages?.lastOrNull { it.role == Role.TOOL && it.toolOk == null }?.toolCallId
            if (callId != null) updateToolMessage(callId, null, tail)
        }

        override suspend fun approve(call: ToolCallRef): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            approvalDeferred = deferred
            withContext(Dispatchers.Main) {
                ui = ui.copy(approval = PendingApproval(call), status = "Жду подтверждения: ${call.name}")
            }
            return try {
                deferred.await()
            } finally {
                approvalDeferred = null
                withContext(Dispatchers.Main) { ui = ui.copy(approval = null, status = "Выполняю…") }
            }
        }
    }

    // ---------- настройки ----------

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        application.prefs.update(transform)
    }

    fun addProvider(provider: Provider) {
        application.prefs.update { it.copy(providers = it.providers + provider, activeProviderId = provider.id) }
    }

    fun updateProvider(provider: Provider) {
        application.prefs.update { s ->
            s.copy(providers = s.providers.map { if (it.id == provider.id) provider else it })
        }
    }

    fun deleteProvider(id: String) {
        application.prefs.update { s ->
            val list = s.providers.filterNot { it.id == id }.ifEmpty { Provider.defaults() }
            s.copy(providers = list, activeProviderId = if (s.activeProviderId == id) list.first().id else s.activeProviderId)
        }
    }

    fun setActiveProvider(id: String) {
        application.prefs.update { it.copy(activeProviderId = id) }
    }

    fun setModel(model: String) {
        application.prefs.update { s ->
            val active = s.activeProvider
            s.copy(providers = s.providers.map { if (it.id == active.id) it.copy(model = model) else it })
        }
    }

    // ---------- терминал ----------

    fun runTerminal(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || ui.terminalRunning) return
        val useRoot = ui.useRootTerminal && (ui.root?.available == true)
        ui = ui.copy(
            terminalLog = ui.terminalLog + "❯ $cmd",
            terminalRunning = true
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Shell.run(cmd, cwd = ui.workspace, useRoot = useRoot, timeoutSec = 300)
            }
            val text = result.output.trimEnd()
            ui = ui.copy(
                terminalLog = (ui.terminalLog + text + (if (result.ok) "" else "\n[exit=${result.exitCode}]"))
                    .takeLast(600),
                terminalRunning = false
            )
        }
    }

    fun clearTerminal() {
        ui = ui.copy(terminalLog = emptyList())
    }

    fun toggleRootTerminal() {
        ui = ui.copy(useRootTerminal = !ui.useRootTerminal)
    }

    // ---------- редактор ----------

    fun openFile(path: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { Shell.readText(path) }
            if (text == null) {
                ui = ui.copy(toast = "Не удалось открыть файл: $path")
                return@launch
            }
            ui = ui.copy(screen = Screen.EDITOR, editorPath = path, editorText = text, editorDirty = false)
        }
    }

    fun updateEditor(text: String) {
        ui = ui.copy(editorText = text, editorDirty = true)
    }

    fun saveEditor() {
        val path = ui.editorPath ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Shell.writeText(path, ui.editorText) }
            ui = ui.copy(editorDirty = false, toast = if (ok) "Сохранено: $path" else "Ошибка сохранения")
            refreshEntries()
        }
    }

    fun closeEditor() {
        ui = ui.copy(screen = Screen.CHAT, editorPath = null, editorText = "", editorDirty = false)
    }

    // ---------- служебное ----------

    fun navigate(screen: Screen) {
        ui = ui.copy(screen = screen)
    }

    fun consumeToast() {
        ui = ui.copy(toast = null)
    }

    fun formatTime(ts: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))

    fun currentToolCallArgs(name: String, argsJson: String?): String {
        if (argsJson.isNullOrBlank()) return ""
        return runCatching {
            val o = JSONObject(argsJson)
            val interesting = listOf("path", "command", "url", "pattern", "from", "to", "cwd")
            interesting.mapNotNull { key -> if (o.has(key)) "$key=${o.optString(key)}" else null }
                .joinToString("  ")
                .ifBlank { argsJson.take(120) }
        }.getOrDefault(argsJson.take(120))
    }
}
