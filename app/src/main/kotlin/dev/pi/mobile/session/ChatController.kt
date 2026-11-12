package dev.pi.mobile.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pi.mobile.AppGraph
import dev.pi.mobile.data.AppSettings
import dev.pi.mobile.data.ProfilesFile
import dev.pi.mobile.data.ServerProfile
import dev.pi.mobile.debug.DebugLaunch
import dev.pi.mobile.net.LocalNetworkPolicy
import dev.pi.mobile.net.PiSocket
import dev.pi.mobile.net.PinRules
import dev.pi.mobile.proto.Commands
import dev.pi.mobile.proto.ModelInfo
import dev.pi.mobile.proto.firstString
import dev.pi.mobile.proto.obj
import dev.pi.mobile.service.PiAlertsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The single chat ViewModel. The UI only sends intents and renders [state]; all
 * protocol work happens here against the process-level [PiConnection].
 */
class ChatController(
    private val graph: AppGraph,
) : ViewModel() {
    private val connection = graph.connection
    private val profiles = graph.profileStore
    private val secrets = graph.secretStore
    private val settings = graph.settingsStore
    private val sessions = SessionsRepository(connection)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    val sessionsState: StateFlow<SessionsState> get() = sessions.state

    private val _commands = MutableStateFlow<List<PiCommand>>(emptyList())
    /** Slash commands, skills and prompt templates reported by the host. */
    val commandsState: StateFlow<List<PiCommand>> = _commands.asStateFlow()
    val profileState: StateFlow<ProfilesFile> get() = profiles.state
    val settingsState: StateFlow<AppSettings> get() = settings.state
    val connectionState: StateFlow<PiConnection.State> get() = connection.state

    /** Address and PIN stay in memory so a session switch can cold-resume. */
    private var targetAddress: String? = null
    private var targetPin: String? = null
    private var targetProfileId: String? = null

    init {
        viewModelScope.launch { profiles.ensureLoaded() }
        viewModelScope.launch {
            connection.records.collect { record ->
                _state.update { current -> ChatReducer.reduce(current, record, System.currentTimeMillis()) }
            }
        }
        viewModelScope.launch {
            connection.state.collect { connectionState -> onConnectionState(connectionState) }
        }
    }

    // ------------------------------------------------------------- connecting

    fun connect(address: String, pin: String) {
        val policy = LocalNetworkPolicy.normalize(address)
        if (!policy.ok) {
            fail(policy.error ?: "That address is not on the LAN.")
            return
        }
        val digits = PinRules.normalize(pin)
        if (digits == null) {
            fail(PinRules.validate(pin) ?: "Enter the 6-digit PIN.")
            return
        }
        connectInternal(policy.baseUrl ?: return, digits, session = null, remember = true)
    }

    /** Connects with the remembered host and its Keystore-sealed PIN. */
    fun autoConnect() {
        viewModelScope.launch {
            val file = profiles.ensureLoaded()
            val profile = file.selected() ?: return@launch
            val pin = secrets.getPin(profile.id) ?: return@launch
            connectInternal(profile.baseUrl, pin, session = null, remember = false)
        }
    }

    fun retry() {
        val address = targetAddress ?: profiles.state.value.selected()?.baseUrl
        val pin = targetPin ?: targetProfileId?.let { secrets.getPin(it) }
        if (address == null || pin == null) {
            autoConnect()
            return
        }
        connectInternal(address, pin, session = null, remember = false)
    }

    /** Cold resume: close the socket, hello with the row's cwd and session path. */
    fun openSession(row: SessionRow) {
        val address = targetAddress ?: profiles.state.value.selected()?.baseUrl
        val pin = targetPin ?: targetProfileId?.let { secrets.getPin(it) }
        if (address == null || pin == null) {
            fail("Connect to the Pi host before opening a session.")
            return
        }
        _state.update {
            it.copy(
                sessionPath = row.path,
                sessionName = row.name,
                cwd = row.cwd ?: it.cwd,
                items = emptyList(),
                activeAssistantId = null,
                streaming = false,
                thinking = false,
                queue = emptyList(),
                dialogs = emptyList(),
                banner = null,
                lastError = null,
                interrupted = false,
            )
        }
        connectInternal(address, pin, session = row, remember = false)
    }

    /**
     * Fresh chat in [folder], or in the last known cwd when the caller does not
     * pick one. The folder is sent as the hello cwd, so Pi runs there.
     */
    fun newChat(folder: String? = null) {
        val address = targetAddress ?: profiles.state.value.selected()?.baseUrl
        val pin = targetPin ?: targetProfileId?.let { secrets.getPin(it) }
        if (address == null || pin == null) {
            fail("Connect to the Pi host before starting a chat.")
            return
        }
        val chosenCwd = folder?.let { NewChatFolder.normalize(it) } ?: _state.value.cwd
        _state.update {
            it.copy(
                sessionPath = null,
                sessionName = null,
                items = emptyList(),
                activeAssistantId = null,
                streaming = false,
                thinking = false,
                queue = emptyList(),
                dialogs = emptyList(),
                banner = null,
                lastError = null,
                interrupted = false,
            )
        }
        connectInternal(address, pin, session = null, remember = false, cwdOverride = chosenCwd)
    }

    /** Reloads the slash-command catalogue (`get_commands`). */
    fun refreshCommands() {
        viewModelScope.launch {
            val reply = runCatching { connection.command("get_commands") }.getOrNull() ?: return@launch
            if (reply.ok) _commands.value = CommandCatalog.parse(reply.data)
        }
    }

    /** Asks the host whether [folder] is an existing directory. Null means yes. */
    suspend fun checkFolder(folder: String): String? {
        NewChatFolder.validate(folder)?.let { return it }
        val reply = runCatching {
            connection.meta(
                Commands.projectCheck(),
                buildJsonObject { put("cwd", NewChatFolder.normalize(folder)) },
            )
        }.getOrNull() ?: return "Could not check the folder."
        return if (reply.ok) null else reply.error ?: "That folder does not exist on the host."
    }

    private fun connectInternal(
        address: String,
        pin: String,
        session: SessionRow?,
        remember: Boolean,
        cwdOverride: String? = null,
    ) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = ConnectionPhase.Connecting,
                    phaseDetail = null,
                    lastError = null,
                    banner = null,
                    interrupted = false,
                    dialogs = emptyList(),
                    statusEntries = emptyMap(),
                    widgets = emptyMap(),
                    editorDraft = null,
                )
            }
            val cwd = session?.cwd ?: cwdOverride ?: _state.value.cwd
            val result = connection.connect(address, pin, cwd = cwd, session = session?.path)
            when (result) {
                is PiConnection.ConnectResult.Ok -> {
                    targetAddress = result.baseUrl
                    targetPin = pin
                    val profileId = result.baseUrl
                    targetProfileId = profileId
                    if (remember && settings.state.value.remember) {
                        val previousCwd = profiles.state.value.profiles.firstOrNull { it.id == profileId }?.lastCwd
                        // Seal the PIN first: the profile must not claim a saved
                        // PIN when the Keystore write failed, or autoconnect would
                        // silently do nothing after a restart (review F12).
                        val pinSaved = secrets.putPin(profileId, pin)
                        profiles.upsert(
                            ServerProfile(
                                id = profileId,
                                name = profileName(result.baseUrl),
                                baseUrl = result.baseUrl,
                                pinSaved = pinSaved,
                                lastCwd = cwd ?: previousCwd,
                                lastUsedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    if (settings.state.value.alertsEnabled) PiAlertsService.start(graph.appContext)
                    refreshCommands()
                }

                is PiConnection.ConnectResult.Failed -> {
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.Failed,
                            phaseDetail = result.message,
                            lastError = result.message,
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ chat intents

    /** Prompts when idle; queues a follow-up while a run is active. */
    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        if (_state.value.streaming) {
            followUp(message)
            return
        }
        if (!connection.isConnected) {
            fail("Connect to Pi first.")
            return
        }
        // The reply is correlated with this row: only the prompt it accepts gets
        // the single check, a later prompt cannot retro-tick an older message.
        val promptId = appendPrompt(message)
        runCommand(Commands.PROMPT, buildJsonObject { put("message", message) }) { data ->
            // The socket consumes the reply, so it never reaches the record
            // collector and its disposition has to be applied here: an
            // extension-handled prompt starts no run, and leaving `streaming` on
            // would keep the working indicator and the slow-model hint forever.
            _state.update { current -> ChatReducer.applyPromptAccepted(current, data, promptId) }
        }
    }

    fun steer(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        runCommand(Commands.STEER, buildJsonObject { put("message", message) })
    }

    fun followUp(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        runCommand(Commands.FOLLOW_UP, buildJsonObject { put("message", message) })
    }

    fun abort() {
        runCommand(Commands.ABORT, null)
    }

    fun answerDialog(dialogId: String, confirmed: Boolean? = null, value: String? = null) {
        val dialog = _state.value.dialogs.firstOrNull { it.id == dialogId } ?: return
        val response = when {
            confirmed != null -> Commands.confirmResponse(dialog.id, confirmed)
            value != null -> Commands.valueResponse(dialog.id, value)
            else -> Commands.cancelledResponse(dialog.id)
        }
        connection.sendRaw(response)
        _state.update { ChatReducer.dismissDialog(it, dialog.id) }
    }

    fun cancelDialog(dialogId: String) {
        answerDialog(dialogId)
    }

    /**
     * The local countdown expired. Pi auto-resolves the dialog with its default,
     * so nothing is sent: the sheet is dropped locally and the transcript notes
     * what happened (review F8).
     */
    fun dialogTimedOut(dialogId: String) {
        _state.update { ChatReducer.dialogTimedOut(it, dialogId) }
    }

    fun setModel(provider: String, modelId: String) {
        runCommand(
            Commands.SET_MODEL,
            buildJsonObject {
                put("provider", provider)
                put("modelId", modelId)
            },
        ) {
            _state.update { current -> current.copy(model = ModelInfo(provider = provider, id = modelId)) }
        }
    }

    fun setThinking(level: String) {
        runCommand(Commands.SET_THINKING_LEVEL, buildJsonObject { put("level", level) }) {
            _state.update { current -> current.copy(thinkingLevel = level) }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch { sessions.refresh() }
    }

    fun renameSession(row: SessionRow, name: String) {
        viewModelScope.launch {
            val reply = sessions.rename(row, name)
            if (!reply.ok) fail(reply.error ?: "Could not rename the session.")
        }
    }

    fun deleteSession(row: SessionRow) {
        viewModelScope.launch {
            val reply = sessions.delete(row)
            if (!reply.ok) fail(reply.error ?: "Could not delete the session.")
        }
    }

    fun disconnect() {
        _commands.value = emptyList()
        connection.close()
        sessions.clear()
        _state.update {
            it.copy(
                phase = ConnectionPhase.Disconnected,
                streaming = false,
                thinking = false,
                queue = emptyList(),
                dialogs = emptyList(),
                banner = null,
                phaseDetail = null,
            )
        }
        PiAlertsService.stop(graph.appContext)
    }

    /** Forget server: profile, sealed PIN, cookie and the foreground service. */
    fun forgetServer() {
        viewModelScope.launch {
            val id = targetProfileId ?: profiles.ensureLoaded().selected()?.id
            connection.close()
            PiAlertsService.stop(graph.appContext)
            if (id != null) profiles.remove(id) else profiles.clear()
            secrets.clear()
            targetAddress = null
            targetPin = null
            targetProfileId = null
            sessions.clear()
            _state.value = ChatState(phase = ConnectionPhase.Disconnected)
        }
    }

    // --------------------------------------------------------------- settings

    fun setAlerts(enabled: Boolean) {
        settings.update { it.copy(alertsEnabled = enabled) }
        if (enabled) {
            if (connection.isConnected) PiAlertsService.start(graph.appContext)
        } else {
            PiAlertsService.stop(graph.appContext)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings.update(transform)
    }

    fun setTheme(theme: String) = updateSettings { it.copy(theme = theme) }

    fun setTextScale(scale: Int) = updateSettings { it.copy(textScale = scale.coerceIn(85, 160)) }

    fun setHideDetails(hidden: Boolean) = updateSettings { it.withHideDetails(hidden) }

    fun setKeepAwake(enabled: Boolean) = updateSettings { it.copy(keepAwake = enabled) }

    fun setHaptics(enabled: Boolean) = updateSettings { it.copy(haptics = enabled) }

    // ------------------------------------------------------------------ debug

    /** Debug-only E2E entry point (intent extras pi_host/pi_pin/pi_debug_*). */
    fun applyDebugLaunch(launch: DebugLaunch) {
        val host = launch.host ?: return
        val pin = launch.pin
        if (launch.autoConnect && !pin.isNullOrBlank()) {
            connect(host, pin)
        }
        val prompt = launch.prompt
        if (!prompt.isNullOrBlank()) {
            viewModelScope.launch {
                withTimeoutOrNull(25_000) {
                    connection.state.filter { it is PiConnection.State.Connected }.first()
                }
                if (connection.isConnected) send(prompt)
            }
        }
    }

    // --------------------------------------------------------------- internals

    /**
     * Appends the optimistic prompt bubble and returns its row id so the prompt
     * reply can be tied to the exact message it accepts. The id is read inside
     * the state update itself, so a concurrent record cannot change it.
     */
    private fun appendPrompt(message: String): String? {
        var promptId: String? = null
        _state.update { current ->
            val next = ChatReducer.userPrompt(current, message)
            promptId = (next.items.lastOrNull() as? ChatItem.UserMessage)?.id
            next
        }
        return promptId
    }

    private fun runCommand(
        name: String,
        payload: JsonObject?,
        onOk: ((kotlinx.serialization.json.JsonElement?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val reply: PiSocket.Reply = connection.command(name, payload)
            if (reply.ok) {
                onOk?.invoke(reply.data)
            } else {
                _state.update { current ->
                    ChatReducer.setError(
                        if (current.streaming && name == Commands.PROMPT) current.copy(streaming = false) else current,
                        reply.error,
                    )
                }
            }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(lastError = message) }
    }

    private suspend fun onConnectionState(next: PiConnection.State) {
        when (next) {
            is PiConnection.State.Idle -> _state.update { it.copy(phase = ConnectionPhase.Idle) }

            is PiConnection.State.Connecting -> _state.update {
                it.copy(phase = ConnectionPhase.Connecting, phaseDetail = null, lastError = null)
            }

            is PiConnection.State.Connected -> {
                _state.update { ChatReducer.connectionRestored(it, next.reconnected) }
                refreshAfterConnect()
            }

            is PiConnection.State.Reconnecting -> _state.update {
                ChatReducer.connectionDropped(it).copy(
                    phaseDetail = "Reconnecting in ${next.delayMs / 1000.0}s",
                    banner = "Reconnecting…",
                )
            }

            is PiConnection.State.Failed -> _state.update {
                it.copy(
                    phase = ConnectionPhase.Failed,
                    phaseDetail = next.message,
                    lastError = next.message,
                    streaming = false,
                    thinking = false,
                )
            }

            is PiConnection.State.Closed -> _state.update {
                it.copy(phase = ConnectionPhase.Disconnected, streaming = false, thinking = false)
            }
        }
    }

    private suspend fun refreshAfterConnect() {
        refreshState()
        refreshModels()
        refreshThinkingLevels()
        refreshStats()
        refreshHistory()
        loadSessions()
        rememberCwd()
    }

    private suspend fun refreshState() {
        val reply = connection.command(Commands.GET_STATE)
        applyReply(Commands.GET_STATE, reply)
    }

    private suspend fun refreshModels() {
        val reply = connection.command(Commands.GET_AVAILABLE_MODELS)
        applyReply(Commands.GET_AVAILABLE_MODELS, reply)
    }

    private suspend fun refreshThinkingLevels() {
        val reply = connection.command(Commands.GET_AVAILABLE_THINKING_LEVELS)
        applyReply(Commands.GET_AVAILABLE_THINKING_LEVELS, reply)
    }

    private suspend fun refreshStats() {
        val reply = connection.command(Commands.GET_SESSION_STATS)
        applyReply(Commands.GET_SESSION_STATS, reply)
    }

    private suspend fun refreshHistory() {
        val reply = connection.command(Commands.GET_MESSAGES)
        applyReply(Commands.GET_MESSAGES, reply)
    }

    private suspend fun loadSessions() {
        sessions.refresh()
    }

    /** The host reports its project dir; the phone cannot know a desktop path. */
    private suspend fun rememberCwd() {
        val reply = connection.meta(Commands.projectInfo())
        if (!reply.ok) return
        val data = reply.data as? JsonObject ?: return
        val cwd = data.firstString("cwd", "projectDir", "workdir")
            ?: data.obj("project")?.firstString("cwd", "path", "dir")
            ?: return
        _state.update { it.copy(cwd = it.cwd ?: cwd) }
        val id = targetProfileId ?: return
        profiles.rememberCwd(id, cwd)
    }

    private fun applyReply(command: String, reply: PiSocket.Reply) {
        if (reply.ok) {
            _state.update {
                ChatReducer.applyCommandResult(it, command, true, reply.data, null)
            }
        } else {
            _state.update { ChatReducer.setError(it, reply.error) }
        }
    }

    private fun profileName(baseUrl: String): String =
        baseUrl.removePrefix("http://").removePrefix("https://").removeSuffix("/")

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatController(graph) as T
    }
}
