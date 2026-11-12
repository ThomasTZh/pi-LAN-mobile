package dev.pi.mobile.session

import dev.pi.mobile.proto.ContentBlock
import dev.pi.mobile.proto.ModelInfo
import dev.pi.mobile.proto.SessionStats
import dev.pi.mobile.proto.Usage

/** Connection lifecycle as the chat screen sees it. */
enum class ConnectionPhase { Idle, Connecting, Connected, Reconnecting, Disconnected, Failed }

enum class ToolStatus { Pending, Running, Success, Error }

/**
 * WhatsApp-style delivery state of a user prompt. It only ever moves forward:
 * [Sending] (the app queued the prompt, no tick) -> [Sent] (Pi accepted it, one
 * check) -> [Processing] (the run actually started on it, two checks).
 */
enum class DeliveryState { Sending, Sent, Processing }

enum class NoticeKind { Info, Success, Warning, Error }

enum class DialogKind { Confirm, Select, Input, Editor, Unknown }

/**
 * A blocking extension UI request. Dialogs are FIFO; [ChatState.activeDialog] is
 * the only one the UI may show at a time.
 */
data class PendingDialog(
    val id: String,
    val kind: DialogKind,
    val title: String? = null,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String? = null,
    val timeoutMs: Long? = null,
    val receivedAt: Long = 0L,
) {
    val isApproval: Boolean get() = kind == DialogKind.Confirm
}

/** One rendered row in the chat list. Keyed by a stable id. */
sealed interface ChatItem {
    val id: String

    data class UserMessage(
        override val id: String,
        val text: String,
        val blocks: List<ContentBlock> = emptyList(),
        /** Tick state; [ChatReducer] only ever moves it forward. */
        val delivery: DeliveryState = DeliveryState.Sending,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val blocks: List<ContentBlock> = emptyList(),
        val usage: Usage? = null,
        val streaming: Boolean = false,
    ) : ChatItem {
        val text: String get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        val thinking: String get() = blocks.filterIsInstance<ContentBlock.Thinking>().joinToString("") { it.text }
    }

    data class ToolCard(
        override val id: String,
        val toolName: String,
        val args: String? = null,
        val status: ToolStatus = ToolStatus.Pending,
        val output: String? = null,
        val truncated: Boolean = false,
        val durationMs: Long? = null,
    ) : ChatItem

    data class Notice(
        override val id: String,
        val text: String,
        val kind: NoticeKind = NoticeKind.Info,
    ) : ChatItem
}

/**
 * Everything the chat screen renders. Immutable and produced only by
 * [ChatReducer], so the UI can be driven from fixtures in JVM tests.
 */
data class ChatState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val phaseDetail: String? = null,
    val cwd: String? = null,
    val sessionPath: String? = null,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val model: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val availableThinkingLevels: List<String> = emptyList(),
    val items: List<ChatItem> = emptyList(),
    val activeAssistantId: String? = null,
    val streaming: Boolean = false,
    val thinking: Boolean = false,
    val queue: List<String> = emptyList(),
    val stats: SessionStats? = null,
    val dialogs: List<PendingDialog> = emptyList(),
    val banner: String? = null,
    val lastError: String? = null,
    val interrupted: Boolean = false,
    /** Extension status chips (`setStatus`), keyed by the extension's key. */
    val statusEntries: Map<String, String> = emptyMap(),
    /** Extension widgets (`setWidget`), for example plan mode's todo list. */
    val widgets: Map<String, List<String>> = emptyMap(),
    /** Text an extension asked to place in the composer (`set_editor_text`). */
    val editorDraft: String? = null,
    val editorDraftId: Long = 0L,
    val sequence: Long = 0L,
) {
    val activeDialog: PendingDialog? get() = dialogs.firstOrNull()
    val waitingDialogCount: Int get() = (dialogs.size - 1).coerceAtLeast(0)
    val hasDialogs: Boolean get() = dialogs.isNotEmpty()

    /**
     * True while the Connect card is the screen (review F17 seam): idle,
     * disconnected or failed, or a first connection that has no transcript yet
     * (which is when the in-button spinner has to stay visible). A reconnect
     * keeps the chat screen because it has messages to show.
     */
    val showsConnectCard: Boolean
        get() = phase == ConnectionPhase.Idle ||
            phase == ConnectionPhase.Disconnected ||
            phase == ConnectionPhase.Failed ||
            (phase == ConnectionPhase.Connecting && items.isEmpty())

    val title: String
        get() = sessionName?.takeIf { it.isNotBlank() }
            ?: cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Pi"

    /** Subtitle for the chat bar: "model · streaming" (11sp, one line). */
    val subtitle: String
        get() = listOfNotNull(
            model?.label,
            thinkingLevel?.takeIf { it.isNotBlank() }?.let { "thinking: $it" },
            if (streaming) "streaming" else null,
        ).joinToString(" · ")
}
