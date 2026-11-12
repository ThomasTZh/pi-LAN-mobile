package dev.pi.mobile.session

import dev.pi.mobile.proto.AssistantStreamEvent
import dev.pi.mobile.proto.ContentBlock
import dev.pi.mobile.proto.Messages
import dev.pi.mobile.proto.ModelInfo
import dev.pi.mobile.proto.PiMessage
import dev.pi.mobile.proto.PiRecord
import dev.pi.mobile.proto.Records
import dev.pi.mobile.proto.SessionStats
import dev.pi.mobile.proto.arr
import dev.pi.mobile.proto.boolOr
import dev.pi.mobile.proto.doubleOr
import dev.pi.mobile.proto.firstString
import dev.pi.mobile.proto.intOr
import dev.pi.mobile.proto.obj
import dev.pi.mobile.proto.plainText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure record -> state function. Every branch is side-effect free and the
 * unknown-record branch returns the same instance, so a newer harness can add
 * events without the app losing state (PLAN.md 5.3).
 */
object ChatReducer {
    /** Display cap for streamed tool output; the full text stays in the host. */
    const val MAX_TOOL_OUTPUT = 20_000

    /** Render window: the newest N items are kept in memory. */
    const val MAX_ITEMS = 400

    private val COMPACTION_REASONS = setOf("manual", "threshold", "overflow")

    fun reduceLines(state: ChatState, lines: List<String>, nowMs: Long = 0L): ChatState {
        var next = state
        for (line in lines) {
            if (line.isBlank()) continue
            val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) }.getOrNull()
                ?: continue
            next = reduce(next, Records.parse(element), nowMs)
        }
        return next
    }

    fun reduceJson(state: ChatState, json: String, nowMs: Long = 0L): ChatState {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()
            ?: return state
        return reduce(state, Records.parse(element), nowMs)
    }

    fun reduce(state: ChatState, record: JsonObject, nowMs: Long = 0L): ChatState =
        reduce(state, Records.parse(record), nowMs)

    fun reduce(state: ChatState, record: PiRecord, nowMs: Long = 0L): ChatState = when (record) {
        is PiRecord.AgentStart -> state.copy(streaming = true, thinking = false, banner = null, lastError = null)

        is PiRecord.TurnStart -> state.copy(streaming = true)

        is PiRecord.MessageStart -> when (record.role) {
            dev.pi.mobile.proto.MessageRole.Assistant -> startAssistant(state, record.message)
            dev.pi.mobile.proto.MessageRole.User -> startUser(state, record.message)
            else -> state
        }

        is PiRecord.MessageUpdate -> applyStreamEvent(state, record.contentIndex, record.event)

        is PiRecord.MessageEnd -> finishMessage(state, record.message)

        is PiRecord.TurnEnd -> state

        is PiRecord.ToolExecutionStart -> upsertToolCard(state, record)

        is PiRecord.ToolExecutionUpdate -> updateToolOutput(state, record)

        is PiRecord.ToolExecutionEnd -> endToolCard(state, record)

        is PiRecord.QueueUpdate -> state.copy(queue = record.queue)

        // One low-level run ended, but Pi may still retry, steer or run a queued
        // follow-up (PLAN.md 5.3). Keep `streaming` true so the composer offers
        // Queue/Stop rather than Send until agent_settled.
        is PiRecord.AgentEnd -> state.copy(thinking = false)

        is PiRecord.AgentSettled -> state.copy(
            streaming = false,
            thinking = false,
            activeAssistantId = null,
            queue = emptyList(),
            banner = if (record.aborted) "Run aborted" else null,
            items = if (record.aborted) markRunningToolsInterrupted(state.items) else state.items,
        )

        is PiRecord.Compaction -> addNotice(
            state,
            text = compactionText(record),
            kind = NoticeKind.Info,
        )

        is PiRecord.SessionInfoChanged -> state.copy(sessionName = record.name)

        is PiRecord.ThinkingLevelChanged -> state.copy(thinkingLevel = record.level ?: state.thinkingLevel)

        is PiRecord.ExtensionUiRequest -> applyUiRequest(state, record, nowMs)

        is PiRecord.Child -> applyChildEvent(state, record)

        is PiRecord.Response -> applyCommandResult(
            state,
            command = record.command,
            success = record.success,
            data = record.data,
            error = record.error,
        )

        is PiRecord.Hello, is PiRecord.Meta, is PiRecord.Unknown -> state
    }

    // ---------------------------------------------------------------- prompts

    /** Optimistic user row plus the run flag, before the prompt is accepted. */
    fun userPrompt(state: ChatState, text: String): ChatState {
        val next = state.sequence + 1
        return state.copy(
            items = (state.items + ChatItem.UserMessage("i$next", text)).takeLast(MAX_ITEMS),
            sequence = next,
            streaming = true,
            thinking = false,
            lastError = null,
            banner = null,
        )
    }

    fun setError(state: ChatState, message: String?): ChatState = state.copy(lastError = message)

    fun setBanner(state: ChatState, message: String?): ChatState = state.copy(banner = message)

    /**
     * A socket is up again. A drop aborts the host-side run, so a run that was
     * active before the reconnect is reported as interrupted (PLAN.md 6). The
     * drop itself moves to [ConnectionPhase.Reconnecting] and flags
     * [ChatState.interrupted] while clearing `streaming`, so both the live flag
     * and the marker count here.
     */
    fun connectionRestored(state: ChatState, reconnected: Boolean): ChatState {
        val interrupted = reconnected && (state.streaming || state.interrupted)
        return state.copy(
            phase = ConnectionPhase.Connected,
            phaseDetail = null,
            lastError = null,
            interrupted = interrupted,
            banner = when {
                interrupted -> "Run interrupted"
                reconnected -> null
                else -> state.banner
            },
            streaming = if (interrupted) false else state.streaming,
            thinking = if (interrupted) false else state.thinking,
        )
    }

    /**
     * The socket dropped. Remember that a run was active so the reconnect can
     * report "Run interrupted" instead of silently losing it (PLAN.md 6).
     */
    fun connectionDropped(state: ChatState): ChatState = state.copy(
        phase = ConnectionPhase.Reconnecting,
        interrupted = state.streaming || state.interrupted,
        streaming = false,
        thinking = false,
    )

    /**
     * The local countdown for a blocking dialog expired. Pi resolves the dialog
     * itself with its default, so the client drops the sheet without answering
     * and leaves a note in the transcript (PLAN.md 6).
     */
    fun dialogTimedOut(state: ChatState, dialogId: String): ChatState {
        val next = dismissDialog(state, dialogId)
        return addNotice(next, "Pi resolved this with the default.", NoticeKind.Info)
    }

    // ---------------------------------------------------------- assistant text

    private fun startAssistant(state: ChatState, message: PiMessage?): ChatState {
        val blocks = message?.content?.filterRenderable().orEmpty()
        val next = state.sequence + 1
        val id = "i$next"
        val withCalls = message?.content?.filterIsInstance<ContentBlock.ToolCall>().orEmpty()
        // A new assistant message means the previous streamed one is complete.
        val closed = state.items.map { item ->
            if (item is ChatItem.AssistantMessage && item.id == state.activeAssistantId) {
                item.copy(streaming = false)
            } else {
                item
            }
        }
        var nextState = state.copy(
            items = (closed + ChatItem.AssistantMessage(id = id, blocks = blocks, streaming = true))
                .takeLast(MAX_ITEMS),
            sequence = next,
            activeAssistantId = id,
            streaming = true,
        )
        for (call in withCalls) {
            nextState = ensureToolCard(nextState, call.id.ifBlank { "i$next-${call.name}" }, call.name, call.arguments)
        }
        return nextState
    }

    private fun startUser(state: ChatState, message: PiMessage?): ChatState {
        val text = message?.content?.plainText().orEmpty()
        if (text.isBlank()) return state.copy(streaming = true)
        // The controller appends the prompt optimistically; the host echoes the
        // same user message back, so replace that row instead of duplicating it.
        // The echo can be separated from the optimistic row by a notice (pi
        // sends the system prompt as a blank system message), so look at every
        // item after the last assistant message rather than only the last one.
        val lastAssistant = state.items.indexOfLast { it is ChatItem.AssistantMessage }
        val alreadyShown = state.items
            .drop(lastAssistant + 1)
            .any { it is ChatItem.UserMessage && it.text == text }
        if (alreadyShown) {
            return state.copy(streaming = true, thinking = false)
        }
        return userPrompt(state, text)
    }

    private fun endBlock(state: ChatState, content: String?, thinking: Boolean): ChatState {
        if (content == null) return state.copy(thinking = false)
        val ensured = ensureAssistant(state)
        val id = ensured.activeAssistantId ?: return ensured
        val items = ensured.items.map { item ->
            if (item !is ChatItem.AssistantMessage || item.id != id) return@map item
            val blocks = item.blocks.toMutableList()
            val lastIndex = blocks.indexOfLast {
                if (thinking) it is ContentBlock.Thinking else it is ContentBlock.Text
            }
            if (lastIndex >= 0) {
                blocks[lastIndex] = if (thinking) ContentBlock.Thinking(content) else ContentBlock.Text(content)
            } else {
                blocks.add(if (thinking) ContentBlock.Thinking(content) else ContentBlock.Text(content))
            }
            item.copy(blocks = blocks)
        }
        return ensured.copy(items = items, thinking = false)
    }

    private fun applyStreamEvent(state: ChatState, contentIndex: Int?, event: AssistantStreamEvent): ChatState =
        when (event) {
            is AssistantStreamEvent.TextDelta -> appendBlockText(state, contentIndex, event.text, thinking = false)
            is AssistantStreamEvent.TextEnd -> endBlock(state, event.text, thinking = false)
            is AssistantStreamEvent.ThinkingDelta -> appendBlockText(state, contentIndex, event.text, thinking = true)
            is AssistantStreamEvent.ThinkingEnd -> endBlock(state, event.text, thinking = true)
            is AssistantStreamEvent.ToolCallStart -> {
                val cardId = event.toolCallId?.takeIf { it.isNotBlank() }
                if (cardId == null) state else ensureToolCard(state, cardId, event.toolName ?: "tool", null)
            }
            is AssistantStreamEvent.ToolCallDelta -> appendToolArguments(state, event.toolCallId, event.text)
            is AssistantStreamEvent.ToolCallEnd -> setToolArguments(state, event.toolCallId, event.arguments)
            is AssistantStreamEvent.Unknown -> state
        }

    private fun appendBlockText(state: ChatState, contentIndex: Int?, text: String, thinking: Boolean): ChatState {
        if (text.isEmpty()) return state
        val ensured = ensureAssistant(state)
        val id = ensured.activeAssistantId ?: return ensured
        val items = ensured.items.map { item ->
            if (item !is ChatItem.AssistantMessage || item.id != id) return@map item
            val blocks = item.blocks.toMutableList()
            val index = contentIndex
            if (index != null && index in blocks.indices) {
                val current = blocks[index]
                if (thinking && current is ContentBlock.Thinking) {
                    blocks[index] = current.copy(text = current.text + text)
                    return@map item.copy(blocks = blocks)
                }
                if (!thinking && current is ContentBlock.Text) {
                    blocks[index] = current.copy(text = current.text + text)
                    return@map item.copy(blocks = blocks)
                }
            }
            val lastIndex = blocks.indexOfLast { if (thinking) it is ContentBlock.Thinking else it is ContentBlock.Text }
            if (lastIndex >= 0) {
                blocks[lastIndex] = if (thinking) {
                    (blocks[lastIndex] as ContentBlock.Thinking).copy(text = (blocks[lastIndex] as ContentBlock.Thinking).text + text)
                } else {
                    (blocks[lastIndex] as ContentBlock.Text).copy(text = (blocks[lastIndex] as ContentBlock.Text).text + text)
                }
            } else {
                blocks.add(if (thinking) ContentBlock.Thinking(text) else ContentBlock.Text(text))
            }
            item.copy(blocks = blocks)
        }
        return ensured.copy(items = items, thinking = thinking)
    }

    private fun ensureAssistant(state: ChatState): ChatState {
        val active = state.activeAssistantId
        if (active != null && state.items.any { it.id == active && it is ChatItem.AssistantMessage }) return state
        val next = state.sequence + 1
        val id = "i$next"
        return state.copy(
            items = (state.items + ChatItem.AssistantMessage(id = id, streaming = true)).takeLast(MAX_ITEMS),
            sequence = next,
            activeAssistantId = id,
        )
    }

    @Suppress("ComplexMethod")
    private fun finishMessage(state: ChatState, message: PiMessage): ChatState = when (message.role) {
        dev.pi.mobile.proto.MessageRole.Assistant -> {
            val renderable = message.content.filterRenderable()
            val calls = message.content.filterIsInstance<ContentBlock.ToolCall>()
            var nextState = state
            val active = state.activeAssistantId
            val index = active?.let { id -> state.items.indexOfFirst { it.id == id && it is ChatItem.AssistantMessage } } ?: -1
            if (index >= 0) {
                val item = state.items[index] as ChatItem.AssistantMessage
                val blocks = if (renderable.isEmpty()) item.blocks else renderable
                nextState = state.copy(
                    items = state.items.toMutableList().also {
                        it[index] = item.copy(blocks = blocks, usage = message.usage, streaming = false)
                    },
                    activeAssistantId = null,
                    thinking = false,
                )
            } else if (renderable.isNotEmpty() || calls.isNotEmpty()) {
                val next = state.sequence + 1
                nextState = state.copy(
                    items = (state.items + ChatItem.AssistantMessage(
                        id = "i$next",
                        blocks = renderable,
                        usage = message.usage,
                        streaming = false,
                    )).takeLast(MAX_ITEMS),
                    sequence = next,
                    activeAssistantId = null,
                    thinking = false,
                )
            }
            for (call in calls) {
                if (call.id.isBlank()) continue
                val exists = nextState.items.any { it is ChatItem.ToolCard && it.id == call.id }
                if (!exists) {
                    nextState = ensureToolCard(nextState, call.id, call.name.ifBlank { "tool" }, call.arguments)
                } else {
                    nextState = setToolArgumentsQuiet(nextState, call.id, call.arguments)
                }
            }
            nextState
        }

        dev.pi.mobile.proto.MessageRole.User -> startUser(state, message)

        dev.pi.mobile.proto.MessageRole.ToolResult -> {
            val text = message.content.plainText()
            val id = message.toolCallId
            if (id != null && state.items.any { it is ChatItem.ToolCard && it.id == id }) {
                updateCard(state, id) { card ->
                    val (output, truncated) = capOutput(text, card.truncated)
                    card.copy(
                        output = output,
                        truncated = truncated,
                        status = if (message.isError) ToolStatus.Error else ToolStatus.Success,
                        toolName = message.toolName ?: card.toolName,
                    )
                }
            } else {
                val next = state.sequence + 1
                state.copy(
                    items = (state.items + ChatItem.ToolCard(
                        id = id?.takeIf { it.isNotBlank() } ?: "i$next",
                        toolName = message.toolName ?: "tool result",
                        status = if (message.isError) ToolStatus.Error else ToolStatus.Success,
                        output = text.take(MAX_TOOL_OUTPUT),
                        truncated = text.length > MAX_TOOL_OUTPUT,
                    )).takeLast(MAX_ITEMS),
                    sequence = next,
                )
            }
        }

        dev.pi.mobile.proto.MessageRole.System -> {
            // pi sends its system prompt as a message with empty text content
            // (the prompt lives in `sections`); rendering that as "System notice"
            // put a blank i-line at the top of every run on the device.
            val text = message.content.plainText()
            if (text.isBlank()) state else addNotice(state, text, NoticeKind.Info)
        }

        dev.pi.mobile.proto.MessageRole.Other -> {
            val text = message.content.plainText()
            val label = message.rawRole?.takeIf { it.isNotBlank() } ?: "system"
            addNotice(
                state,
                text = if (text.isBlank()) label else "$label · $text",
                kind = NoticeKind.Info,
            )
        }
    }

    // ------------------------------------------------------------- tool cards

    private fun upsertToolCard(state: ChatState, record: PiRecord.ToolExecutionStart): ChatState {
        val id = record.toolCallId.ifBlank { "tool-${state.sequence + 1}" }
        val ensured = ensureToolCard(state, id, record.toolName, record.args)
        return updateCard(ensured, id) { it.copy(status = ToolStatus.Running) }
    }

    private fun updateToolOutput(state: ChatState, record: PiRecord.ToolExecutionUpdate): ChatState {
        if (record.toolCallId.isBlank()) return state
        return updateCard(state, record.toolCallId) { card ->
            val (output, truncated) = capOutput(record.partialResult, card.truncated)
            card.copy(
                output = if (record.partialResult.isNullOrEmpty()) card.output else output,
                truncated = truncated,
                status = ToolStatus.Running,
            )
        }
    }

    private fun endToolCard(state: ChatState, record: PiRecord.ToolExecutionEnd): ChatState {
        if (record.toolCallId.isBlank()) return state
        return updateCard(state, record.toolCallId) { card ->
            val finalText = record.result?.takeIf { it.isNotBlank() }
            val (output, truncated) = if (finalText != null) capOutput(finalText, false) else capOutput(card.output, card.truncated)
            card.copy(
                output = output,
                truncated = truncated,
                status = if (record.isError) ToolStatus.Error else ToolStatus.Success,
                durationMs = record.durationMs ?: card.durationMs,
            )
        }
    }

    private fun appendToolArguments(state: ChatState, toolCallId: String?, delta: String): ChatState {
        if (delta.isEmpty()) return state
        if (toolCallId != null && state.items.any { it is ChatItem.ToolCard && it.id == toolCallId }) {
            return updateCard(state, toolCallId) { it.copy(args = (it.args ?: "") + delta) }
        }
        // Wire toolcall deltas carry no id: append to the newest pending card so
        // multi-fragment JSON arguments stream in order (the final toolcall_end
        // replaces them with the complete string).
        val index = state.items.indexOfLast { it is ChatItem.ToolCard && it.status == ToolStatus.Pending }
        if (index < 0) return state
        val item = state.items[index] as ChatItem.ToolCard
        return state.copy(
            items = state.items.toMutableList().also {
                it[index] = item.copy(args = (item.args ?: "") + delta)
            },
        )
    }

    private fun setToolArguments(state: ChatState, toolCallId: String?, arguments: String?): ChatState {
        if (toolCallId == null || arguments.isNullOrBlank()) return state
        return setToolArgumentsQuiet(state, toolCallId, arguments)
    }

    private fun setToolArgumentsQuiet(state: ChatState, toolCallId: String, arguments: String?): ChatState {
        if (arguments.isNullOrBlank()) return state
        return updateCard(state, toolCallId) { it.copy(args = arguments) }
    }

    private fun ensureToolCard(
        state: ChatState,
        id: String,
        toolName: String,
        args: String?,
    ): ChatState {
        if (state.items.any { it is ChatItem.ToolCard && it.id == id }) {
            return updateCard(state, id) { card ->
                card.copy(
                    toolName = toolName.takeIf { it.isNotBlank() } ?: card.toolName,
                    args = args ?: card.args,
                )
            }
        }
        val next = state.sequence + 1
        return state.copy(
            items = (state.items + ChatItem.ToolCard(
                id = id,
                toolName = toolName.ifBlank { "tool" },
                args = args,
                status = ToolStatus.Pending,
            )).takeLast(MAX_ITEMS),
            sequence = next,
        )
    }

    private fun updateCard(
        state: ChatState,
        id: String,
        transform: (ChatItem.ToolCard) -> ChatItem.ToolCard,
    ): ChatState {
        if (id.isBlank()) return state
        var changed = false
        val next = state.items.map { item ->
            if (item is ChatItem.ToolCard && item.id == id) {
                changed = true
                transform(item)
            } else {
                item
            }
        }
        return if (changed) state.copy(items = next) else state
    }

    private fun markRunningToolsInterrupted(items: List<ChatItem>): List<ChatItem> = items.map { item ->
        if (item is ChatItem.ToolCard && item.status == ToolStatus.Running) {
            item.copy(status = ToolStatus.Error, output = item.output ?: "Interrupted")
        } else {
            item
        }
    }

    private fun capOutput(text: String?, wasTruncated: Boolean): Pair<String?, Boolean> {
        if (text == null) return null to wasTruncated
        if (text.length <= MAX_TOOL_OUTPUT) return text to wasTruncated
        return text.take(MAX_TOOL_OUTPUT) to true
    }

    // ---------------------------------------------------------------- dialogs

    private fun applyUiRequest(state: ChatState, record: PiRecord.ExtensionUiRequest, nowMs: Long): ChatState {
        val method = record.method.lowercase()
        if (method == "notify") {
            val text = listOfNotNull(record.title, record.message).joinToString(" · ").ifBlank { "Pi notice" }
            return addNotice(state, text, NoticeKind.Info)
        }
        val kind = when (method) {
            "confirm" -> DialogKind.Confirm
            "select" -> DialogKind.Select
            "input" -> DialogKind.Input
            "editor" -> DialogKind.Editor
            else -> return state // setStatus, setWidget, setTitle, set_editor_text: ignore safely
        }
        if (record.id.isBlank()) return state
        if (state.dialogs.any { it.id == record.id }) return state
        val dialog = PendingDialog(
            id = record.id,
            kind = kind,
            title = record.title,
            message = record.message,
            options = record.options,
            placeholder = record.placeholder,
            prefill = record.prefill,
            timeoutMs = record.timeoutMs,
            receivedAt = nowMs,
        )
        return state.copy(dialogs = state.dialogs + dialog, banner = null)
    }

    /** Removes a dialog answered or cancelled by the UI. */
    fun dismissDialog(state: ChatState, id: String): ChatState =
        state.copy(dialogs = state.dialogs.filterNot { it.id == id })

    // ------------------------------------------------------------------ child

    private fun applyChildEvent(state: ChatState, record: PiRecord.Child): ChatState {
        val kind = record.kind.lowercase()
        val fatal = kind.contains("exit") || kind.contains("error") || kind.contains("crash")
        val text = when {
            fatal -> "The Pi process stopped. ${record.detail?.take(500) ?: ""}".trim()
            else -> "Pi child: ${record.kind}${record.detail?.let { " · ${it.take(500)}" } ?: ""}"
        }
        val next = addNotice(state, text, if (fatal) NoticeKind.Error else NoticeKind.Info)
        return if (fatal) {
            next.copy(streaming = false, thinking = false, lastError = text, banner = "The Pi process stopped")
        } else {
            next
        }
    }

    // ------------------------------------------------------------------ stats

    private fun addNotice(state: ChatState, text: String, kind: NoticeKind): ChatState {
        if (text.isBlank()) return state
        val next = state.sequence + 1
        return state.copy(
            items = (state.items + ChatItem.Notice("i$next", text, kind)).takeLast(MAX_ITEMS),
            sequence = next,
        )
    }

    private fun compactionText(record: PiRecord.Compaction): String {
        val starting = record.kind.contains("start")
        val base = when {
            starting -> "Compacting context…"
            record.kind.contains("end") -> "Context compacted"
            else -> "Context compaction"
        }
        val detail = record.text?.takeIf { text ->
            text.isNotBlank() && !(starting && text in COMPACTION_REASONS)
        }
        return detail?.let { "$base · ${it.take(200)}" } ?: base
    }

    // ------------------------------------------------------- command responses

    fun applyCommandResult(
        state: ChatState,
        command: String?,
        success: Boolean,
        data: JsonElement?,
        error: String?,
    ): ChatState {
        if (!success) {
            return state.copy(lastError = error?.takeIf { it.isNotBlank() } ?: "The desktop rejected $command.")
        }
        return when (command) {
            "get_state" -> applyGetState(state, data)
            "get_messages" -> {
                val messages = when (val element = data) {
                    is JsonArray -> element.mapNotNull { Messages.parse(it) }
                    is JsonObject -> element.arr("messages")?.mapNotNull { Messages.parse(it) }
                        ?: element.arr("history")?.mapNotNull { Messages.parse(it) }
                    else -> null
                } ?: return state
                applyHistory(state, messages)
            }
            "get_available_models" -> {
                val array = when (val element = data) {
                    is JsonArray -> element
                    is JsonObject -> element.arr("models")
                    else -> null
                }
                val list = array?.mapNotNull(::parseModel).orEmpty()
                state.copy(availableModels = list.ifEmpty { state.availableModels })
            }
            "get_available_thinking_levels" -> state.copy(
                availableThinkingLevels = parseThinkingLevels(data).ifEmpty { state.availableThinkingLevels },
            )
            "get_session_stats" -> {
                val statsElement = (data as? JsonObject)?.obj("stats") ?: data
                state.copy(stats = parseStats(statsElement) ?: state.stats)
            }
            "set_session_name" -> {
                val name = (data as? JsonObject)?.firstString("name", "sessionName")
                state.copy(sessionName = name ?: state.sessionName)
            }
            "set_model" -> state.copy(model = parseModel(data) ?: state.model)
            "set_thinking_level" -> {
                val level = (data as? JsonObject)?.firstString("level")
                state.copy(thinkingLevel = level ?: state.thinkingLevel)
            }
            "prompt" -> {
                // disposition "handled" means no run started for this prompt.
                val disposition = (data as? JsonObject)?.firstString("disposition")
                if (disposition == "handled") state.copy(streaming = false) else state
            }
            else -> state
        }
    }

    private fun applyGetState(state: ChatState, data: JsonElement?): ChatState {
        val root = (data as? JsonObject) ?: return state
        val stateObject = root.obj("state") ?: root
        val model = parseModel(stateObject["model"])
            ?: stateObject.firstString("modelId", "modelName")?.let { id ->
                ModelInfo(provider = stateObject.firstString("provider", "providerId"), id = id)
            }
        val streaming = stateObject.boolOr("isStreaming")
        return state.copy(
            model = model ?: state.model,
            streaming = streaming ?: state.streaming,
            thinkingLevel = stateObject.firstString("thinkingLevel", "thinking") ?: state.thinkingLevel,
            cwd = stateObject.firstString("cwd", "projectDir", "workdir") ?: state.cwd,
            sessionPath = stateObject.firstString("sessionPath", "sessionFile", "session") ?: state.sessionPath,
            sessionId = stateObject.firstString("sessionId") ?: state.sessionId,
            sessionName = stateObject.firstString("sessionName", "name") ?: state.sessionName,
            availableModels = state.availableModels,
        )
    }

    fun applyHistory(state: ChatState, messages: List<PiMessage>): ChatState {
        val out = ArrayList<ChatItem>(messages.size)
        val cardIndex = HashMap<String, Int>()
        var seq = 0L
        fun nextId(): String {
            seq += 1
            return "h$seq"
        }
        for (message in messages) {
            when (message.role) {
                dev.pi.mobile.proto.MessageRole.User -> out.add(
                    ChatItem.UserMessage(nextId(), message.content.plainText(), message.content),
                )

                dev.pi.mobile.proto.MessageRole.Assistant -> {
                    val renderable = message.content.filterRenderable()
                    if (renderable.isNotEmpty()) {
                        out.add(ChatItem.AssistantMessage(nextId(), renderable, usage = message.usage))
                    }
                    for (call in message.content.filterIsInstance<ContentBlock.ToolCall>()) {
                        val cardId = call.id.ifBlank { nextId() }
                        cardIndex[call.id] = out.size
                        out.add(
                            ChatItem.ToolCard(
                                id = cardId,
                                toolName = call.name.ifBlank { "tool" },
                                args = call.arguments,
                                status = ToolStatus.Pending,
                            ),
                        )
                    }
                }

                dev.pi.mobile.proto.MessageRole.ToolResult -> {
                    val text = message.content.plainText()
                    val index = message.toolCallId?.let { cardIndex[it] }
                    if (index != null && index in out.indices) {
                        val card = out[index] as ChatItem.ToolCard
                        out[index] = card.copy(
                            output = text.take(MAX_TOOL_OUTPUT),
                            truncated = text.length > MAX_TOOL_OUTPUT,
                            status = if (message.isError) ToolStatus.Error else ToolStatus.Success,
                            toolName = message.toolName ?: card.toolName,
                        )
                    } else {
                        out.add(
                            ChatItem.ToolCard(
                                id = message.toolCallId?.takeIf { it.isNotBlank() } ?: nextId(),
                                toolName = message.toolName ?: "tool result",
                                output = text.take(MAX_TOOL_OUTPUT),
                                truncated = text.length > MAX_TOOL_OUTPUT,
                                status = if (message.isError) ToolStatus.Error else ToolStatus.Success,
                            ),
                        )
                    }
                }

                dev.pi.mobile.proto.MessageRole.System -> {
                    val text = message.content.plainText()
                    if (text.isNotBlank()) out.add(ChatItem.Notice(nextId(), text, NoticeKind.Info))
                }

                dev.pi.mobile.proto.MessageRole.Other -> {
                    val text = message.content.plainText()
                    val label = message.rawRole?.takeIf { it.isNotBlank() } ?: "system"
                    if (text.isNotBlank() || message.rawRole != null) {
                        out.add(
                            ChatItem.Notice(
                                nextId(),
                                if (text.isBlank()) label else "$label · $text",
                                NoticeKind.Info,
                            ),
                        )
                    }
                }
            }
        }
        return state.copy(
            items = out.takeLast(MAX_ITEMS),
            sequence = maxOf(state.sequence, seq),
            activeAssistantId = null,
            streaming = false,
            thinking = false,
        )
    }

    // ------------------------------------------------------------------ parsers

    fun parseModel(element: JsonElement?): ModelInfo? {
        val primitive = element as? JsonPrimitive
        if (primitive != null) {
            if (primitive.isString && primitive.content.isNotBlank()) return ModelInfo(id = primitive.content)
            return null
        }
        val obj = element as? JsonObject ?: return null
        val id = obj.firstString("id", "modelId", "model") ?: return null
        return ModelInfo(
            provider = obj.firstString("provider", "providerId"),
            id = id,
            name = obj.firstString("name", "displayName"),
            contextWindow = obj.intOr("contextWindow"),
        )
    }

    private fun parseThinkingLevels(data: JsonElement?): List<String> {
        val array = when (data) {
            is JsonArray -> data
            is JsonObject -> data.arr("levels") ?: data.arr("thinkingLevels")
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                is JsonObject -> element.firstString("id", "level", "name")
                else -> null
            }
        }
    }

    private fun parseStats(element: JsonElement?): SessionStats? {
        val obj = element as? JsonObject ?: return null
        val tokens = obj.obj("tokens")
        val context = obj.obj("contextUsage")
        return SessionStats(
            messages = obj.intOr("totalMessages") ?: obj.intOr("messageCount") ?: obj.intOr("messages"),
            inputTokens = tokens?.intOr("input") ?: obj.intOr("inputTokens"),
            outputTokens = tokens?.intOr("output") ?: obj.intOr("outputTokens"),
            totalTokens = tokens?.intOr("total") ?: obj.intOr("totalTokens"),
            costUsd = obj.doubleOr("cost") ?: obj.obj("cost")?.doubleOr("total") ?: obj.doubleOr("totalCost"),
            durationMs = obj.longOr("durationMs") ?: obj.longOr("duration"),
            contextTokens = context?.intOr("tokens"),
            contextWindow = context?.intOr("contextWindow"),
            contextPercent = context?.intOr("percent"),
        )
    }

    private fun JsonObject.longOr(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun List<ContentBlock>.filterRenderable(): List<ContentBlock> =
        filter { it is ContentBlock.Text || it is ContentBlock.Thinking }
}
