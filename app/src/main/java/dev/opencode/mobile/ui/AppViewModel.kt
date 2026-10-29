package dev.opencode.mobile.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
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
    val chatDraft: String = "",
    val busy: Boolean = false,
    val runningRootAllowed: Boolean = false,
    val runningAutoApprove: Boolean = false,
    val status: String = "",
    val approval: PendingApproval? = null,
    val todos: List<TodoItem> = emptyList(),
    val root: RootStatus? = null,
    val checkingRoot: Boolean = false,
    val terminalLog: List<String> = emptyList(),
    val terminalRunning: Boolean = false,
    val useRootTerminal: Boolean = false,
    val editorPath: String? = null,
    val editorText: String = "",
    val editorDirty: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    val storageError: String? = null,
    val openProviderCatalog: Boolean = false,
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var agentRunId = 0L
    // Drafts survive navigation and session changes while the app process remains alive.
    private val chatDrafts = mutableMapOf<String, String>()

    init {
        ui = ui.copy(settings = application.prefs.settings.value)
        viewModelScope.launch {
            application.prefs.settings.collect { s ->
                ui = ui.copy(settings = s)
            }
        }
        viewModelScope.launch {
            application.prefs.storageError.collect { error -> ui = ui.copy(storageError = error) }
        }
        viewModelScope.launch {
            val ws = withContext(Dispatchers.IO) { ensureWorkspace() }
            ui = ui.copy(workspace = ws)
            refreshEntries()
            refreshSessions()
            openOrCreateSession()
            if (application.prefs.settings.value.allowRoot) checkRoot(true)
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
            if ((f.isDirectory || f.mkdirs()) && f.isDirectory && f.canRead() && f.canWrite()) {
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
            val workspace = ui.workspace
            val dir = File(workspace)
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
            if (ui.workspace == workspace) ui = ui.copy(entries = list)
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

    private suspend fun openOrCreateSession() {
        val lastId = application.prefs.settings.value.lastSessionId
        val loaded = withContext(Dispatchers.IO) { lastId?.let { store.load(it) } }
        if (ui.session != null) return
        if (loaded != null) {
            val session = accessibleSession(loaded)
            if (ui.chatDraft.isNotEmpty()) chatDrafts[session.id] = ui.chatDraft
            ui = ui.copy(session = session, workspace = session.cwd)
            refreshEntries()
        } else {
            newSession()
        }
    }

    private fun accessibleSession(session: ChatSession): ChatSession {
        val dir = File(session.cwd)
        return if (dir.isDirectory && dir.canRead() && dir.canWrite()) session else session.copy(cwd = ui.workspace)
    }

    private fun sessionChangeBlocked(): Boolean {
        if (!ui.busy) return false
        ui = ui.copy(toast = "Сначала остановите текущую задачу")
        return true
    }

    fun newSession() {
        if (sessionChangeBlocked()) return
        val session = ChatSession(cwd = ui.workspace)
        val draft = if (ui.session == null) ui.chatDraft else ""
        if (draft.isNotEmpty()) chatDrafts[session.id] = draft
        ui = ui.copy(session = session, chatDraft = draft, todos = emptyList(), screen = Screen.CHAT)
        application.prefs.update { it.copy(lastSessionId = session.id) }
        if (!store.save(session)) ui = ui.copy(toast = "Не удалось сохранить сессию")
        refreshSessions()
    }

    fun openSession(id: String) {
        if (sessionChangeBlocked()) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            if (sessionChangeBlocked()) return@launch
            val session = accessibleSession(loaded)
            ui = ui.copy(session = session, chatDraft = chatDrafts[session.id].orEmpty(), workspace = session.cwd, screen = Screen.CHAT, todos = emptyList())
            application.prefs.update { it.copy(lastSessionId = id, lastWorkspace = session.cwd) }
            refreshEntries()
        }
    }

    fun deleteSession(id: String) {
        if (sessionChangeBlocked()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            chatDrafts.remove(id)
            if (ui.session?.id == id) newSession()
            refreshSessions()
        }
    }

    private fun setSession(session: ChatSession) {
        ui = ui.copy(session = session)
        if (!store.save(session)) ui = ui.copy(toast = "Не удалось сохранить сессию")
        refreshSessions()
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val session = ui.session ?: return
        val updated = session.copy(messages = transform(session.messages), updatedAt = System.currentTimeMillis())
        ui = ui.copy(session = updated)
    }

    // ---------- чат ----------

    fun updateChatDraft(value: String) {
        ui.session?.id?.let { chatDrafts[it] = value }
        ui = ui.copy(chatDraft = value)
    }

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
        ui = ui.copy(
            busy = true,
            runningRootAllowed = settings.allowRoot,
            runningAutoApprove = settings.autoApproveTools,
            status = "Думаю…",
            screen = Screen.CHAT
        )
        val runId = ++agentRunId

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
                    listener = agentListener(runId, session.id)
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                ui = ui.copy(toast = "Остановлено")
            } finally {
                updateMessages { messages ->
                    messages.map { message ->
                        when {
                            message.streaming -> message.copy(streaming = false, content = message.content.ifBlank { "Остановлено." })
                            message.role == Role.TOOL && message.toolOk == null -> message.copy(
                                toolOk = false,
                                content = "Выполнение прервано. Проверьте состояние файлов перед повтором."
                            )
                            else -> message
                        }
                    }
                }
                ui = ui.copy(busy = false, runningRootAllowed = false, runningAutoApprove = false, status = "", approval = null)
                ui.session?.let { if (!store.save(it)) ui = ui.copy(toast = "Не удалось сохранить сессию") }
                refreshSessions()
            }
        }
    }

    fun stop() {
        if (!ui.busy) return
        engine.cancel()
        approvalDeferred?.complete(false)
        approvalDeferred = null
        job?.cancel()
        // Keep the UI busy until the coroutine's cleanup has finished.
        ui = ui.copy(status = "Останавливаю…", approval = null)
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

    private fun isActiveRun(runId: Long, sessionId: String): Boolean =
        runId == agentRunId && ui.busy && ui.session?.id == sessionId

    private fun dispatchAgentEvent(runId: Long, sessionId: String, action: () -> Unit) {
        val guarded = { if (isActiveRun(runId, sessionId)) action() }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded() else mainHandler.post { guarded() }
    }

    private fun agentListener(runId: Long, sessionId: String) = object : AgentEngine.Listener {
        override fun onStatus(text: String) = dispatchAgentEvent(runId, sessionId) {
            ui = ui.copy(status = text)
        }

        override fun onAssistantStart() = dispatchAgentEvent(runId, sessionId) {
            pendingOutput = StringBuilder()
            appendMessage(ChatMessage(role = Role.ASSISTANT, content = "", streaming = true))
        }

        override fun onAssistantDelta(text: String) = dispatchAgentEvent(runId, sessionId) {
            updateMessages { list ->
                val idx = list.indexOfLast { it.role == Role.ASSISTANT && it.streaming }
                if (idx < 0) list else list.toMutableList().also {
                    it[idx] = it[idx].copy(content = it[idx].content + text)
                }
            }
        }

        override fun onAssistantDone(message: ChatMessage) = dispatchAgentEvent(runId, sessionId) {
            updateMessages { list ->
                val idx = list.indexOfLast { it.role == Role.ASSISTANT && it.streaming }
                if (idx < 0) list + message else list.toMutableList().also {
                    it[idx] = message.copy(id = it[idx].id, streaming = false, ts = it[idx].ts)
                }
            }
        }

        override fun onToolStart(call: ToolCallRef) = dispatchAgentEvent(runId, sessionId) {
            pendingOutput = StringBuilder()
            lastChunkFlush = 0L
            appendMessage(ChatMessage(
                role = Role.TOOL,
                content = "⏳ выполняется…",
                toolCallId = call.id,
                toolName = call.name,
                toolArgs = call.arguments,
                toolOk = null
            ))
        }

        override fun onToolEnd(call: ToolCallRef, ok: Boolean, output: String) = dispatchAgentEvent(runId, sessionId) {
            updateToolMessage(call.id, ok, output.ifBlank { "(нет вывода)" })
            pendingOutput = StringBuilder()
        }

        override fun onTodo(items: List<TodoItem>) = dispatchAgentEvent(runId, sessionId) {
            ui = ui.copy(todos = items)
        }

        override fun onCommandOutputChunk(chunk: String) = dispatchAgentEvent(runId, sessionId) {
            val callId = ui.session?.messages?.lastOrNull { it.role == Role.TOOL && it.toolOk == null }?.toolCallId
            if (callId != null) {
                pendingOutput.append(chunk)
                val now = System.currentTimeMillis()
                if (now - lastChunkFlush >= 250) {
                    lastChunkFlush = now
                    updateToolMessage(callId, null, pendingOutput.toString().takeLast(4000))
                }
            }
        }

        override suspend fun approve(call: ToolCallRef): Boolean {
            if (!isActiveRun(runId, sessionId)) return false
            val deferred = CompletableDeferred<Boolean>()
            approvalDeferred = deferred
            ui = ui.copy(approval = PendingApproval(call), status = "Жду подтверждения: ${call.name}")
            return try {
                deferred.await()
            } finally {
                if (approvalDeferred === deferred) approvalDeferred = null
                if (isActiveRun(runId, sessionId)) ui = ui.copy(approval = null, status = "Выполняю…")
            }
        }
    }

    // ---------- настройки ----------

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        val wasRootAllowed = application.prefs.settings.value.allowRoot
        application.prefs.update(transform)
        val rootAllowed = application.prefs.settings.value.allowRoot
        if (!wasRootAllowed && rootAllowed) checkRoot(true)
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
        application.prefs.update { s ->
            if (s.providers.any { it.id == id }) s.copy(activeProviderId = id) else s
        }
    }

    fun selectProviderModel(providerId: String, model: String) {
        application.prefs.update { it.withSelectedModel(providerId, model) }
    }

    fun setModel(model: String) {
        application.prefs.update { s -> s.withSelectedModel(s.activeProvider.id, model) }
    }

    fun openProviderCatalog() {
        ui = ui.copy(screen = Screen.SETTINGS, openProviderCatalog = true)
    }

    fun consumeProviderCatalogRequest() {
        ui = ui.copy(openProviderCatalog = false)
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
            val allowRoot = ui.settings.allowRoot
            val text = withContext(Dispatchers.IO) { Shell.readText(path, allowRoot = allowRoot) }
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
        val text = ui.editorText
        val allowRoot = ui.settings.allowRoot
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Shell.writeText(path, text, allowRoot = allowRoot) }
            val clean = ok && ui.editorPath == path && ui.editorText == text
            ui = ui.copy(editorDirty = if (clean) false else ui.editorDirty, toast = if (ok) "Сохранено: $path" else "Ошибка сохранения")
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
