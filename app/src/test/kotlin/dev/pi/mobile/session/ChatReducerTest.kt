package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun fixture(name: String): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream("rpc/$name")
            ?: error("missing fixture rpc/$name")
        return stream.bufferedReader().use { it.readLines() }
    }

    private fun reduceFixture(name: String): ChatState = ChatReducer.reduceLines(ChatState(), fixture(name))

    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private fun ChatState.assistants(): List<ChatItem.AssistantMessage> =
        items.filterIsInstance<ChatItem.AssistantMessage>()

    private fun ChatState.cards(): List<ChatItem.ToolCard> = items.filterIsInstance<ChatItem.ToolCard>()

    private fun ChatState.notices(): List<ChatItem.Notice> = items.filterIsInstance<ChatItem.Notice>()

    @Test
    fun textStreamingThenSettles() {
        val state = reduceFixture("turn-text.jsonl")
        val assistant = state.assistants().single()
        assertEquals("Hello, world.", assistant.text)
        assertFalse(assistant.streaming)
        assertEquals(16, assistant.usage?.totalTokens)
        assertFalse(state.streaming)
        assertEquals(ConnectionPhase.Idle, state.phase)
        // message_start + message_end for the same user prompt is one row.
        assertEquals("Say hello.", state.items.filterIsInstance<ChatItem.UserMessage>().single().text)
    }

    @Test
    fun thinkingAndToolStream() {
        val state = reduceFixture("turn-thinking-tool.jsonl")
        val assistants = state.assistants()
        assertEquals(2, assistants.size)
        assertEquals("Need to list files. Then read one.", assistants[0].thinking)
        assertEquals("Two entries.", assistants[1].text)

        val card = state.cards().single()
        assertEquals("bash", card.toolName)
        assertTrue(card.args.orEmpty().contains("ls -la"))
        assertEquals(ToolStatus.Success, card.status)
        assertTrue(card.output.orEmpty().contains("drwxr-xr-x"))
        assertEquals(1200L, card.durationMs)
        assertFalse(state.streaming)
    }

    @Test
    fun abortMarksRunAndRunningTools() {
        val state = reduceFixture("abort.jsonl")
        assertEquals("Run aborted", state.banner)
        assertFalse(state.streaming)
        val card = state.cards().single()
        assertEquals(ToolStatus.Error, card.status)
        assertEquals("partial output", card.output)
    }

    @Test
    fun dialogsAreFifoAndOneAtATime() {
        val state = reduceFixture("dialogs.jsonl")
        assertEquals(3, state.dialogs.size)
        assertEquals(
            listOf(DialogKind.Confirm, DialogKind.Select, DialogKind.Input),
            state.dialogs.map { it.kind },
        )
        assertEquals("ui1", state.activeDialog?.id)
        assertEquals(2, state.waitingDialogCount)
        assertEquals(listOf("A", "B"), state.dialogs[1].options)
        assertEquals("session name", state.dialogs[2].placeholder)
        assertEquals(30_000L, state.dialogs[0].timeoutMs)
        assertTrue(state.notices().any { it.text.contains("Heads up") })
    }

    @Test
    fun unknownRecordsKeepState() {
        val untouched = ChatState()
        val unknown = Json.parseToJsonElement("""{"type":"persona_update","x":1}""") as JsonObject
        assertSame(untouched, ChatReducer.reduce(untouched, unknown))

        val state = reduceFixture("unknown.jsonl")
        assertEquals(3, state.notices().size)
        assertTrue(state.notices().any { it.text.contains("Compacting") })
        assertTrue(state.notices().any { it.text.contains("Context compacted") })
        assertTrue(state.lastError.orEmpty().contains("The Pi process stopped"))
        assertEquals("The Pi process stopped", state.banner)
        assertFalse(state.streaming)
        assertEquals("demo", state.sessionName)
        assertEquals("high", state.thinkingLevel)
    }

    @Test
    fun messageEndIsAuthoritative() {
        var state = reduce(ChatState(), """{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        state = reduce(
            state,
            """{"type":"message_update","contentIndex":0,"assistantMessageEvent":{"type":"text_delta","delta":"Draft"}}""",
        )
        state = reduce(
            state,
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Final answer"}]}}""",
        )
        assertEquals("Final answer", state.assistants().single().text)
        assertNull(state.activeAssistantId)
    }

    @Test
    fun historyMaterializesMessagesAndToolCards() {
        val data = Json.parseToJsonElement(
            """
            {"messages":[
              {"role":"user","content":[{"type":"text","text":"List files"}]},
              {"role":"assistant","content":[{"type":"text","text":"Sure."},{"type":"toolCall","id":"call_7","name":"bash","arguments":{"command":"ls"}}],"usage":{"input":5,"output":6}},
              {"role":"toolResult","toolCallId":"call_7","toolName":"bash","content":[{"type":"text","text":"file.txt"}]},
              {"role":"system","content":[{"type":"text","text":"Context compacted"}]},
              {"role":"bashExecution","content":[{"type":"text","text":"echo hi"}]}
            ]}
            """.trimIndent(),
        )
        val state = ChatReducer.applyCommandResult(ChatState(streaming = true), "get_messages", true, data, null)
        assertFalse(state.streaming)

        val user = state.items.filterIsInstance<ChatItem.UserMessage>().single()
        assertEquals("List files", user.text)
        val assistant = state.assistants().single()
        assertEquals("Sure.", assistant.text)
        assertEquals(5, assistant.usage?.inputTokens)

        val card = state.cards().single()
        assertEquals("call_7", card.id)
        assertEquals(ToolStatus.Success, card.status)
        assertEquals("file.txt", card.output)
        assertEquals(2, state.notices().size)
        assertTrue(state.notices().any { it.text.contains("Context compacted") })
        assertTrue(state.notices().any { it.text.contains("bashExecution") })
    }

    @Test
    fun getStateParsesSessionIdentity() {
        val data = Json.parseToJsonElement(
            """
            {"model":{"provider":"llama","id":"qwen3","name":"Qwen3"},"thinkingLevel":"high",
             "cwd":"/home/pi/project","sessionPath":"/home/pi/.pi/session.jsonl","sessionName":"demo","sessionId":"s1"}
            """.trimIndent(),
        )
        val state = ChatReducer.applyCommandResult(ChatState(), "get_state", true, data, null)
        assertEquals("llama", state.model?.provider)
        assertEquals("qwen3", state.model?.id)
        assertEquals("Qwen3", state.model?.label)
        assertEquals("high", state.thinkingLevel)
        assertEquals("/home/pi/project", state.cwd)
        assertEquals("/home/pi/.pi/session.jsonl", state.sessionPath)
        assertEquals("demo", state.sessionName)
        assertEquals("s1", state.sessionId)
        assertEquals("demo", state.title)
    }

    @Test
    fun availableModelsAndThinkingLevels() {
        val models = Json.parseToJsonElement(
            """{"models":[{"provider":"llama","id":"qwen3","name":"Qwen3"},{"provider":"local","id":"small"}]}""",
        )
        var state = ChatReducer.applyCommandResult(ChatState(), "get_available_models", true, models, null)
        assertEquals(2, state.availableModels.size)
        assertEquals("Qwen3", state.availableModels[0].label)
        assertEquals("local/small", state.availableModels[1].qualifiedId)

        val levels = Json.parseToJsonElement("""["low","medium","high"]""")
        state = ChatReducer.applyCommandResult(state, "get_available_thinking_levels", true, levels, null)
        assertEquals(listOf("low", "medium", "high"), state.availableThinkingLevels)
    }

    @Test
    fun queueUpdateAndToolOutputCap() {
        var state = reduce(
            ChatState(),
            """{"type":"queue_update","steering":["one"],"followUp":["two"]}""",
        )
        assertEquals(listOf("one", "two"), state.queue)

        state = reduce(state, """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash"}""")
        state = reduce(
            state,
            """{"type":"tool_execution_update","toolCallId":"t1","partialResult":"${"x".repeat(25_000)}"}""",
        )
        val card = state.cards().single()
        assertEquals(ChatReducer.MAX_TOOL_OUTPUT, card.output.orEmpty().length)
        assertTrue(card.truncated)
        assertEquals(ToolStatus.Running, card.status)
    }

    @Test
    fun toolResultMessageUpdatesExistingCard() {
        var state = reduce(ChatState(), """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash"}""")
        state = reduce(
            state,
            """{"type":"message_end","message":{"role":"toolResult","toolCallId":"t1","toolName":"bash","content":[{"type":"text","text":"done"}],"isError":false}}""",
        )
        val card = state.cards().single()
        assertEquals(ToolStatus.Success, card.status)
        assertEquals("done", card.output)
    }

    @Test
    fun userPromptAndDismissDialog() {
        var state = ChatReducer.userPrompt(ChatState(), "Fix the build")
        assertTrue(state.streaming)
        assertEquals("Fix the build", state.items.filterIsInstance<ChatItem.UserMessage>().single().text)

        state = state.copy(dialogs = listOf(PendingDialog(id = "ui1", kind = DialogKind.Confirm)))
        state = ChatReducer.dismissDialog(state, "ui1")
        assertTrue(state.dialogs.isEmpty())
        assertNull(state.activeDialog)
    }

    @Test
    fun commandResultsUpdateModelThinkingAndRunState() {
        val model = Json.parseToJsonElement(
            """{"id":"qwen3","name":"Qwen3","provider":"llama","contextWindow":212000}""",
        )
        var state = ChatReducer.applyCommandResult(ChatState(), "set_model", true, model, null)
        assertEquals("qwen3", state.model?.id)
        assertEquals(212_000, state.model?.contextWindow)

        val level = Json.parseToJsonElement("""{"level":"high"}""")
        state = ChatReducer.applyCommandResult(state, "set_thinking_level", true, level, null)
        assertEquals("high", state.thinkingLevel)

        state = state.copy(streaming = true)
        state = ChatReducer.applyCommandResult(
            state,
            "prompt",
            true,
            Json.parseToJsonElement("""{"disposition":"handled"}"""),
            null,
        )
        assertFalse(state.streaming)
    }

    @Test
    fun sessionStatsParse() {
        val data = Json.parseToJsonElement(
            """
            {"sessionFile":"/s.jsonl","sessionId":"s1","userMessages":5,"assistantMessages":5,
             "toolCalls":12,"toolResults":12,"totalMessages":22,
             "tokens":{"input":50000,"output":10000,"cacheRead":40000,"cacheWrite":5000,"total":105000},
             "cost":0.45,"contextUsage":{"tokens":60000,"contextWindow":200000,"percent":30}}
            """.trimIndent(),
        )
        val state = ChatReducer.applyCommandResult(ChatState(), "get_session_stats", true, data, null)
        assertEquals(22, state.stats?.messages)
        assertEquals(105_000, state.stats?.totalTokens)
        assertEquals(0.45, state.stats?.costUsd ?: 0.0, 0.0001)
        assertEquals(60_000, state.stats?.contextTokens)
        assertEquals(200_000, state.stats?.contextWindow)
        assertEquals(30, state.stats?.contextPercent)
    }

    @Test
    fun commandFailureSetsLastError() {
        val state = ChatReducer.applyCommandResult(ChatState(), "prompt", false, null, "quota exceeded")
        assertEquals("quota exceeded", state.lastError)

        val unnamed = ChatReducer.applyCommandResult(ChatState(), "abort", false, null, null)
        assertEquals("The desktop rejected abort.", unnamed.lastError)
    }

    @Test
    fun fatalChildStopsRun() {
        var state = ChatReducer.userPrompt(ChatState(), "hi")
        state = reduce(state, """{"type":"child","event":"exit","code":1,"stderr":"segfault"}""")
        assertFalse(state.streaming)
        assertEquals("The Pi process stopped", state.banner)
        assertTrue(state.lastError.orEmpty().contains("segfault"))
    }
}
