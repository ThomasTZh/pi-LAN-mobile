package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WhatsApp-style delivery ticks on user prompts: Sending (no tick) -> Sent (one
 * check, the host accepted the prompt) -> Processing (two checks, the model
 * produced output).
 *
 * Only real model output advances the second check. `agent_start`, `turn_start`
 * and the assistant `message_start` fire before the provider answers, so a run
 * that fails with the model unreachable (llama.cpp stopped) ends on the single
 * check (T5 device finding). The transition is monotonic and only ever applies
 * to the newest prompt row, so an old message cannot be re-ticked and the host's
 * echo of a prompt cannot reset a tick.
 */
class ChatDeliveryTickTest {
    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private fun ChatState.users(): List<ChatItem.UserMessage> =
        items.filterIsInstance<ChatItem.UserMessage>()

    private fun ChatState.user(): ChatItem.UserMessage = users().single()

    private val agentStart = """{"type":"agent_start"}"""
    private val turnStart = """{"type":"turn_start"}"""
    private val assistantStart =
        """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}"""

    /** A streamed text fragment: the first record that proves the provider answered. */
    private val textDelta =
        """{"type":"message_update","contentIndex":0,"event":{"type":"text_delta","delta":"hi"}}"""
    private val thinkingDelta =
        """{"type":"message_update","contentIndex":0,"event":{"type":"thinking_delta","delta":"hm"}}"""
    private val toolCallStart =
        """{"type":"message_update","contentIndex":1,"event":{"type":"toolcall_start","id":"t1","toolName":"bash"}}"""
    private val toolExecutionStart =
        """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"ls"}}"""

    /** A provider that does not stream deltas: the answer only arrives here. */
    private val assistantEndWithText =
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hello, world."}],"stopReason":"stop"}}"""

    /** What Pi sends when the model backend is unreachable: no content at all. */
    private val errorEndNoContent =
        """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error",""" +
            """"errorMessage":"connect ECONNREFUSED 127.0.0.1:8080"}}"""

    private val retryStart =
        """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,""" +
            """"errorMessage":"connect ECONNREFUSED 127.0.0.1:8080"}"""

    private val agentSettled = """{"type":"agent_settled","aborted":false}"""

    private fun userStart(text: String) =
        """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun userEnd(text: String) =
        """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun sentPrompt(text: String = "Run the tests"): ChatState =
        ChatReducer.markPromptSent(ChatReducer.userPrompt(ChatState(), text))

    @Test
    fun promptGoesFromSendingToSentToProcessing() {
        var state = ChatReducer.userPrompt(ChatState(), "Run the tests")
        assertEquals(DeliveryState.Sending, state.user().delivery)

        // The prompt command reply arrived: the host accepted it.
        state = ChatReducer.markPromptSent(state)
        assertEquals(DeliveryState.Sent, state.user().delivery)

        // The first streamed fragment proves the model answered.
        state = reduce(state, textDelta)
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
    fun runStartRecordsKeepTheSingleCheck() {
        // Pi emits these before the provider has answered: with a dead backend
        // they are all the phone ever sees, so none of them may show two checks.
        val beforeOutput = listOf(agentStart, turnStart, assistantStart)
        for (record in beforeOutput) {
            var state = sentPrompt()
            state = reduce(state, record)
            assertEquals("$record must not tick the prompt", DeliveryState.Sent, state.user().delivery)
        }

        // The whole pre-output run, and the streaming flag it sets, unchanged.
        var state = sentPrompt()
        state = reduce(state, agentStart)
        state = reduce(state, turnStart)
        state = reduce(state, assistantStart)
        assertTrue("the working indicator still follows the run", state.streaming)
        assertEquals(DeliveryState.Sent, state.user().delivery)
    }

    @Test
    fun theFirstModelOutputMarksProcessing() {
        val evidence = listOf(
            textDelta,
            thinkingDelta,
            toolCallStart,
            toolExecutionStart,
            assistantEndWithText,
        )
        for (record in evidence) {
            var state = sentPrompt()
            state = reduce(state, record)
            assertEquals("$record is model output", DeliveryState.Processing, state.user().delivery)
        }
    }

    @Test
    fun anErrorRunWithoutOutputKeepsTheSingleCheck() {
        // Device repro (T5): llama.cpp stopped. Pi starts the run, the provider
        // call fails, Pi retries and settles, and no model output ever arrives.
        var state = sentPrompt("ping")
        state = reduce(state, agentStart)
        state = reduce(state, turnStart)
        state = reduce(state, assistantStart)
        assertTrue("the run is still live while Pi retries", state.streaming)

        state = reduce(state, errorEndNoContent)
        state = reduce(state, retryStart)
        assertEquals(DeliveryState.Sent, state.user().delivery)
        assertTrue("the retry keeps the working indicator on", state.streaming)

        state = reduce(state, agentSettled)
        assertFalse(state.streaming)
        assertEquals("a failed run must never show two checks", DeliveryState.Sent, state.user().delivery)
        assertEquals(1, state.users().size)
        // The provider error leaves an empty assistant row; nothing was rendered.
        assertTrue(
            state.items.filterIsInstance<ChatItem.AssistantMessage>().all { it.blocks.isEmpty() },
        )
    }

    @Test
    fun evidenceBeforeTheAcceptanceReplyStillEndsOnTwoChecks() {
        // The host can stream output before the prompt reply lands on a slow LAN:
        // the row is promoted in place and the late reply must not take the
        // second check away.
        var state = ChatReducer.userPrompt(ChatState(), "Reply with exactly: pong")
        state = reduce(state, textDelta)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        val promptId = state.user().id

        state = ChatReducer.markPromptSent(state, promptId)
        assertEquals(DeliveryState.Processing, state.user().delivery)
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
        var state = sentPrompt()
        val promptId = state.user().id
        state = reduce(state, textDelta)
        assertEquals(DeliveryState.Processing, state.user().delivery)

        // A late or repeated acceptance reply, and the run records that arrive
        // afterwards, keep both checks: the tick never falls back to one.
        state = ChatReducer.markPromptSent(state, promptId)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        state = ChatReducer.applyCommandResult(state, "prompt", true, null, null)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        state = reduce(state, agentStart)
        state = reduce(state, turnStart)
        state = reduce(state, assistantStart)
        assertEquals(DeliveryState.Processing, state.user().delivery)
    }

    @Test
    fun echoDedupeKeepsTheTick() {
        // Device order: agent_start, turn_start, the user echo, then the
        // assistant message and its deltas.
        var state = sentPrompt("Reply with exactly: pong")
        state = reduce(state, agentStart)
        state = reduce(state, turnStart)

        state = reduce(state, userStart("Reply with exactly: pong"))
        state = reduce(state, userEnd("Reply with exactly: pong"))
        assertEquals(1, state.users().size)
        assertEquals("the echo is not new evidence", DeliveryState.Sent, state.user().delivery)

        state = reduce(state, assistantStart)
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Sent, state.user().delivery)

        state = reduce(state, textDelta)
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

        // Model output for that message advances it.
        val answered = reduce(state, textDelta)
        assertEquals(DeliveryState.Processing, answered.user().delivery)
    }

    @Test
    fun aSecondPromptDoesNotRetickTheFirst() {
        var state = sentPrompt("first")
        state = reduce(state, textDelta)
        state = reduce(state, agentSettled)
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

        // Run records for the first prompt cannot promote the new one either.
        state = reduce(state, turnStart)
        assertEquals(DeliveryState.Sending, state.users()[1].delivery)

        state = ChatReducer.markPromptSent(state)
        assertEquals(DeliveryState.Sent, state.users()[1].delivery)
        state = reduce(state, textDelta)
        assertEquals(DeliveryState.Processing, state.users()[1].delivery)
        assertEquals(DeliveryState.Processing, state.users()[0].delivery)
    }

    @Test
    fun aQueuedFollowUpWithoutARowLeavesThePreviousPromptAlone() {
        // The composer queues a message while a run is active: no new row, so no
        // row may be promoted or reset by that.
        var state = sentPrompt("first")
        state = reduce(state, textDelta)
        state = reduce(state, """{"type":"queue_update","queue":["later"]}""")
        assertEquals(1, state.users().size)
        assertEquals(DeliveryState.Processing, state.user().delivery)
        assertEquals(listOf("later"), state.queue)
    }

    @Test
    fun runStartWithoutAPromptRowKeepsState() {
        // No user row at all (for example a reconnect mid-run): nothing to tick.
        val started = reduce(ChatState(), agentStart)
        assertTrue(started.users().isEmpty())
        assertTrue(started.streaming)
    }
}
