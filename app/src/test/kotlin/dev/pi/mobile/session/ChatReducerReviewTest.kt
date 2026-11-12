package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions from the T5 review (reports/pi-mobile-2026-11-12.md):
 *
 *  - F2: id-less `toolcall_delta` records replaced the streamed tool arguments
 *    instead of appending them.
 *  - F3: `agent_end` cleared the streaming flag, although retries, steering and
 *    queued follow-ups can still run until `agent_settled`.
 *  - F4 companion: a drop during a run has to surface as "Run interrupted" once
 *    the socket is back.
 *  - F8 companion: an expired local countdown drops the dialog locally and says
 *    that Pi resolved it with its default; nothing is answered.
 */
class ChatReducerReviewTest {
    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private fun reduce(state: ChatState, record: JsonObject): ChatState = ChatReducer.reduce(state, record)

    private fun ChatState.cards(): List<ChatItem.ToolCard> = items.filterIsInstance<ChatItem.ToolCard>()

    private val toolCardPending = ChatState(
        items = listOf(ChatItem.ToolCard(id = "call-1", toolName = "bash", status = ToolStatus.Pending)),
        streaming = true,
    )

    private fun idlessDelta(delta: String): JsonObject = buildJsonObject {
        put("type", "message_update")
        put(
            "assistantMessageEvent",
            buildJsonObject {
                put("type", "toolcall_delta")
                put("delta", delta)
            },
        )
    }

    @Test
    fun idlessToolcallDeltasAccumulate() {
        var state = toolCardPending
        state = reduce(state, idlessDelta("{\"command\":"))
        state = reduce(state, idlessDelta("\"ls -la\"}"))
        assertEquals("{\"command\":\"ls -la\"}", state.cards().single().args)
    }

    @Test
    fun toolcallEndReplacesTheStreamedFragment() {
        var state = reduce(toolCardPending, idlessDelta("{\"comm"))
        state = reduce(
            state,
            buildJsonObject {
                put("type", "message_update")
                put(
                    "assistantMessageEvent",
                    buildJsonObject {
                        put("type", "toolcall_end")
                        put("toolCallId", "call-1")
                        put("arguments", "{\"command\":\"ls -la\"}")
                    },
                )
            },
        )
        assertEquals("{\"command\":\"ls -la\"}", state.cards().single().args)
    }

    @Test
    fun agentEndKeepsStreamingUntilAgentSettled() {
        var state = ChatReducer.userPrompt(ChatState(), "hello")
        state = reduce(state, """{"type":"agent_end","messages":[]}""")
        assertTrue("agent_end is one low-level run, not the end of the work", state.streaming)
        assertFalse(state.thinking)
        state = reduce(state, """{"type":"agent_settled","aborted":false}""")
        assertFalse(state.streaming)
        assertNull(state.banner)
    }

    @Test
    fun aDropDuringARunFlagsRunInterruptedOnReconnect() {
        var state = ChatReducer.userPrompt(ChatState(), "do the long thing")
        state = reduce(state, """{"type":"turn_start"}""")
        // Drop: the controller moves to Reconnecting and remembers the run.
        state = ChatReducer.connectionDropped(state)
        assertEquals(ConnectionPhase.Reconnecting, state.phase)
        assertFalse(state.streaming)
        assertTrue("the interrupted run has to be remembered across the drop", state.interrupted)
        // Socket is back.
        state = ChatReducer.connectionRestored(state, reconnected = true)
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertEquals("Run interrupted", state.banner)
        assertFalse(state.streaming)
    }

    @Test
    fun aReconnectWithoutARunDoesNotShowTheBanner() {
        val state = ChatReducer.connectionRestored(ChatState(phase = ConnectionPhase.Reconnecting), reconnected = true)
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertNull(state.banner)
        assertFalse(state.interrupted)
    }

    @Test
    fun aFreshConnectKeepsAnExistingBanner() {
        val state = ChatReducer.connectionRestored(
            ChatState(banner = "Pi error", streaming = false),
            reconnected = false,
        )
        assertEquals("Pi error", state.banner)
    }

    @Test
    fun anExpiredDialogIsDroppedLocallyWithANotice() {
        val dialog = PendingDialog(
            id = "d1",
            kind = DialogKind.Confirm,
            title = "Allow change outside the workspace?",
            message = "write /home/user/x",
            timeoutMs = 5_000,
            receivedAt = 1_000,
        )
        val state = ChatState(dialogs = listOf(dialog), streaming = true)
        val next = ChatReducer.dialogTimedOut(state, "d1")
        assertTrue(next.dialogs.isEmpty())
        val notice = next.items.filterIsInstance<ChatItem.Notice>().single()
        assertEquals("Pi resolved this with the default.", notice.text)
        // Pi resolves it, so the client keeps waiting for the run instead of
        // cancelling anything locally.
        assertTrue(next.streaming)
    }
}
