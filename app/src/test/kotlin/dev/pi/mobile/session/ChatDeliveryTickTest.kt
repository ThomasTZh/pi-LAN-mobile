package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WhatsApp-style delivery ticks on user prompts: Sending (no tick) -> Sent (one
 * check, the host accepted the prompt) -> Processing (two checks, the run
 * started on it). The transition is monotonic and only ever applies to the
 * newest prompt row, so an old message cannot be re-ticked and the host's echo
 * of a prompt cannot reset a tick.
 */
class ChatDeliveryTickTest {
    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private fun ChatState.users(): List<ChatItem.UserMessage> =
        items.filterIsInstance<ChatItem.UserMessage>()

    private fun ChatState.user(): ChatItem.UserMessage = users().single()

    private val agentStart = """{"type":"agent_start"}"""
    private val turnStart = """{"type":"turn_start"}"""

    private fun userStart(text: String) =
        """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun userEnd(text: String) =
        """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun promptGoesFromSendingToSentToProcessing() {
        var state = ChatReducer.userPrompt(ChatState(), "Run the tests")
        assertEquals(DeliveryState.Sending, state.user().delivery)

        // The prompt command reply arrived: the host accepted it.
        state = ChatReducer.markPromptSent(state)
        assertEquals(DeliveryState.Sent, state.user().delivery)

        // The run actually started on it.
        state = reduce(state, agentStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        assertEquals("Run the tests", state.user().text)
        assertEquals(1, state.users().size)
    }

    @Test
    fun theSuccessfulPromptResponseAlsoMarksSent() {
        // The reducer path: a reply that reached the transcript instead of the
        // socket's pending-request table still ticks the row.
        var state = ChatReducer.userPrompt(ChatState(), "hello")
        state = ChatReducer.applyCommandResult(state, "prompt", true, null, null)
        assertEquals(DeliveryState.Sent, state.user().delivery)
    }

    @Test
    fun everyRunRecordMarksProcessing() {
        val evidence = listOf(
            agentStart,
            turnStart,
            """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            """{"type":"message_update","contentIndex":0,"event":{"type":"text_delta","text":"hi"}}""",
            """{"type":"message_update","contentIndex":0,"event":{"type":"thinking_delta","text":"hm"}}""",
            """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash"}""",
        )
        for (record in evidence) {
            var state = ChatReducer.markPromptSent(ChatReducer.userPrompt(ChatState(), "go"))
            state = reduce(state, record)
            assertEquals("$record must tick the prompt", DeliveryState.Processing, state.user().delivery)
        }
    }

    @Test
    fun evidenceBeforeTheAcceptanceReplyStillEndsOnTwoChecks() {
        // The host can stream the run before the prompt reply lands on a slow
        // LAN: the row is promoted in place and the late reply, plus its echo,
        // must not take the second check away.
        var state = ChatReducer.userPrompt(ChatState(), "Reply with exactly: pong")
        state = reduce(state, agentStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        val promptId = state.user().id

        state = reduce(state, userStart("Reply with exactly: pong"))
        state = ChatReducer.markPromptSent(state, promptId)
        state = ChatReducer.applyCommandResult(state, "prompt", true, null, null)
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Processing, state.user().delivery)
    }

    @Test
    fun aRejectedPromptKeepsNoTick() {
        var state = ChatReducer.userPrompt(ChatState(), "hello")
        state = ChatReducer.applyCommandResult(state, "prompt", false, null, "No model is loaded.")
        assertEquals(DeliveryState.Sending, state.user().delivery)
        assertEquals("No model is loaded.", state.lastError)
    }

    @Test
    fun processingIsNeverDowngraded() {
        var state = ChatReducer.userPrompt(ChatState(), "Run the tests")
        state = ChatReducer.markPromptSent(state)
        val promptId = state.user().id
        state = reduce(state, turnStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)

        // A late or repeated acceptance reply, and more run records, keep both
        // checks: the tick never falls back to one.
        state = ChatReducer.markPromptSent(state, promptId)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        state = ChatReducer.applyCommandResult(state, "prompt", true, null, null)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        state = reduce(state, agentStart)
        state = reduce(state, turnStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)
    }

    @Test
    fun echoDedupeKeepsTheTick() {
        var state = ChatReducer.userPrompt(ChatState(), "Reply with exactly: pong")
        state = ChatReducer.markPromptSent(state)
        state = reduce(state, turnStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)

        // The host echoes the prompt back as a user message; it must neither add
        // a second bubble nor drop the double check.
        state = reduce(state, userStart("Reply with exactly: pong"))
        state = reduce(state, userEnd("Reply with exactly: pong"))
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Processing, state.user().delivery)

        // The assistant echo of the same run does not touch the row either.
        state = reduce(state, """{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Processing, state.user().delivery)
    }

    @Test
    fun echoOfAnUnknownPromptStartsAtOneCheck() {
        // A steer or a queued follow-up has no optimistic row of its own: the
        // host echoing it proves it accepted the message.
        val state = reduce(ChatState(), userStart("and then this"))
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Sent, state.user().delivery)

        // The run records that follow belong to that message.
        val started = reduce(state, turnStart)
        assertEquals(DeliveryState.Processing, started.user().delivery)
    }

    @Test
    fun aSecondPromptDoesNotRetickTheFirst() {
        var state = ChatReducer.userPrompt(ChatState(), "first")
        state = ChatReducer.markPromptSent(state)
        state = reduce(state, turnStart)
        state = reduce(state, """{"type":"agent_settled","aborted":false}""")
        val firstId = state.user().id
        assertEquals(DeliveryState.Processing, state.user().delivery)

        // Second prompt: still Sending, and only it may be promoted.
        state = ChatReducer.userPrompt(state, "second")
        val second = state.users()[1]
        assertEquals(DeliveryState.Sending, second.delivery)
        assertEquals(DeliveryState.Processing, state.users()[0].delivery)

        // An acceptance reply for the first id must not touch the new row.
        state = ChatReducer.markPromptSent(state, firstId)
        assertEquals(DeliveryState.Sending, state.users()[1].delivery)
        assertEquals(DeliveryState.Processing, state.users()[0].delivery)

        state = ChatReducer.markPromptSent(state)
        assertEquals(DeliveryState.Sent, state.users()[1].delivery)
        state = reduce(state, turnStart)
        assertEquals(DeliveryState.Processing, state.users()[1].delivery)
        assertEquals(DeliveryState.Processing, state.users()[0].delivery)
    }

    @Test
    fun aQueuedFollowUpWithoutARowLeavesThePreviousPromptAlone() {
        // The composer queues a message while a run is active: no new row, so no
        // row may be promoted or reset by that.
        var state = ChatReducer.userPrompt(ChatState(), "first")
        state = ChatReducer.markPromptSent(state)
        state = reduce(state, turnStart)
        state = reduce(state, """{"type":"queue_update","queue":["later"]}""")
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        assertEquals(listOf("later"), state.queue)
    }

    @Test
    fun processingEvidenceWithoutAPromptRowKeepsState() {
        // No user row at all (for example a reconnect mid-run): nothing to tick.
        val started = reduce(ChatState(), agentStart)
        assertTrue(started.users().isEmpty())
        assertTrue(started.streaming)
    }
}
