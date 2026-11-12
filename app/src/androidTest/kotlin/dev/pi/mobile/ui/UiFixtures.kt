package dev.pi.mobile.ui

import dev.pi.mobile.proto.ContentBlock
import dev.pi.mobile.proto.ModelInfo
import dev.pi.mobile.proto.SessionStats
import dev.pi.mobile.proto.Usage
import dev.pi.mobile.session.ChatItem
import dev.pi.mobile.session.ChatState
import dev.pi.mobile.session.ConnectionPhase
import dev.pi.mobile.session.DialogKind
import dev.pi.mobile.session.NoticeKind
import dev.pi.mobile.session.PendingDialog
import dev.pi.mobile.session.SessionGrouping
import dev.pi.mobile.session.SessionRow
import dev.pi.mobile.session.SessionsState
import dev.pi.mobile.session.ToolStatus

/**
 * Fixture chat states for the Compose tests. Everything here is the same
 * immutable ChatState the reducer produces, so the UI tests exercise the real
 * rendering path without a socket or a ViewModel.
 */
object UiFixtures {
    const val CWD = "/home/user/Documents/localLLMsetup"

    fun model(): ModelInfo = ModelInfo(
        provider = "pi",
        id = "swift-1.5-qwen3.8-27b-q4-k-s",
        name = "Swift 1.5",
    )

    fun baseState(): ChatState = ChatState(
        phase = ConnectionPhase.Connected,
        cwd = CWD,
        sessionPath = "$CWD/../sessions/pi-mobile.jsonl",
        sessionId = "session-1",
        sessionName = "Pi Mobile plan",
        model = model(),
        thinkingLevel = "medium",
        availableModels = listOf(model(), ModelInfo(provider = "pi", id = "qwen3-small", name = "Qwen3 Small")),
        availableThinkingLevels = listOf("off", "low", "medium", "high"),
    )

    val MARKDOWN = """
        ## Sessions sheet

        A **tested** parser with `inline code` and a [Pi link](https://pi.dev).

        - first item
        - second item

        1. ordered one
        2. ordered two

        ```kotlin
        val answer = 42
        ```

        > quoted note
    """.trimIndent()

    fun markdownState(): ChatState = baseState().copy(
        items = listOf(
            ChatItem.UserMessage("u1", "Make the sessions sheet match the plan."),
            ChatItem.AssistantMessage(
                id = "a1",
                blocks = listOf(
                    ContentBlock.Thinking("The plan asks for 64dp rows and a per-row menu.", durationMs = 3200),
                    ContentBlock.Text(MARKDOWN),
                ),
                usage = Usage(inputTokens = 100, outputTokens = 40, totalTokens = 140, costUsd = 0.001),
            ),
        ),
        sequence = 5,
    )

    fun toolState(truncated: Boolean = false): ChatState = baseState().copy(
        items = listOf(
            ChatItem.ToolCard(
                id = "call_1",
                toolName = "read_file",
                args = """{"path":"pi-app/PLAN.md"}""",
                status = ToolStatus.Success,
                output = if (truncated) {
                    (1..80).joinToString("\n") { "line $it of the tool output" } + "\n…"
                } else {
                    "line 1\nline 2\nline 3"
                },
                truncated = truncated,
                durationMs = 1450,
            ),
        ),
        sequence = 6,
    )

    fun noticeState(): ChatState = baseState().copy(
        items = listOf(ChatItem.Notice("n1", "Context compacted", NoticeKind.Info)),
        sequence = 7,
    )

    fun richState(): ChatState = baseState().copy(
        items = listOf(
            ChatItem.UserMessage("u1", "Make the sessions sheet match the plan."),
            ChatItem.AssistantMessage(
                id = "a1",
                blocks = listOf(
                    ContentBlock.Thinking("The plan asks for 64dp rows and a per-row menu.", durationMs = 3200),
                    ContentBlock.Text(MARKDOWN),
                ),
            ),
            ChatItem.ToolCard(
                id = "call_1",
                toolName = "read_file",
                args = """{"path":"pi-app/PLAN.md"}""",
                status = ToolStatus.Success,
                output = "…PLAN.md (210 lines)",
                durationMs = 1450,
            ),
            ChatItem.Notice("n1", "Context compacted", NoticeKind.Info),
        ),
        stats = stats(),
        sequence = 9,
    )

    fun streamingState(): ChatState = baseState().copy(
        items = listOf(
            ChatItem.UserMessage("u1", "Stream a long answer."),
            ChatItem.AssistantMessage(
                id = "a1",
                blocks = listOf(ContentBlock.Text("Streaming **the** answer…")),
                streaming = true,
            ),
        ),
        streaming = true,
        queue = listOf("and then queue this"),
        sequence = 10,
    )

    fun emptyState(): ChatState = baseState().copy(items = emptyList())

    fun connectingState(): ChatState = ChatState(
        phase = ConnectionPhase.Connecting,
        cwd = CWD,
        sessionPath = "$CWD/sessions/resume.jsonl",
        model = model(),
    )

    fun failedState(): ChatState = ChatState(
        phase = ConnectionPhase.Failed,
        lastError = "Host unreachable. Check the desktop.",
    )

    fun confirmDialog(
        id: String = "d1",
        timeoutMs: Long? = null,
        receivedAt: Long = System.currentTimeMillis(),
    ): PendingDialog = PendingDialog(
        id = id,
        kind = DialogKind.Confirm,
        title = "Run this command?",
        message = "rm -rf build && ./gradlew :app:assembleDebug",
        timeoutMs = timeoutMs,
        receivedAt = receivedAt,
    )

    fun selectDialog(): PendingDialog = PendingDialog(
        id = "d2",
        kind = DialogKind.Select,
        title = "Which model?",
        options = listOf("Swift 1.5", "Qwen3 Small", "Keep current"),
        receivedAt = System.currentTimeMillis(),
    )

    fun inputDialog(): PendingDialog = PendingDialog(
        id = "d3",
        kind = DialogKind.Input,
        title = "Session name",
        placeholder = "Name this session",
        receivedAt = System.currentTimeMillis(),
    )

    fun editorDialog(): PendingDialog = PendingDialog(
        id = "d4",
        kind = DialogKind.Editor,
        title = "Edit the prompt",
        prefill = "Prefilled text",
        receivedAt = System.currentTimeMillis(),
    )

    fun sessionsState(): SessionsState {
        val now = System.currentTimeMillis()
        val rows = listOf(
            SessionRow(
                path = "/sessions/one.jsonl",
                mtimeMs = now - 5 * 60_000,
                name = "Pi Mobile plan",
                messageCount = 12,
                cwd = CWD,
                cwdExists = true,
            ),
            SessionRow(
                path = "/sessions/two.jsonl",
                mtimeMs = now - 2 * 60 * 60_000,
                firstPrompt = "Check the Gradle build",
                messageCount = 4,
                cwd = CWD,
                cwdExists = true,
            ),
            SessionRow(
                path = "/sessions/three.jsonl",
                mtimeMs = now - 26 * 60 * 60_000,
                name = "Other project",
                messageCount = 2,
                cwd = "/home/user/other",
                cwdExists = false,
            ),
        )
        return SessionsState(rows = rows, groups = SessionGrouping.group(rows), loading = false)
    }

    fun stats(): SessionStats = SessionStats(
        messages = 4,
        inputTokens = 1234,
        outputTokens = 567,
        totalTokens = 1801,
        costUsd = 0.0042,
        durationMs = 64_000,
        contextTokens = 15_360,
        contextWindow = 128_000,
        contextPercent = 12,
    )
}
