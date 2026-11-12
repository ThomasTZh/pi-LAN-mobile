package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 device finding: the `prompt` reply carries `data.disposition`. An extension
 * can consume a prompt without starting a model run, and then no
 * `agent_start`/`agent_settled` follows to end the run state. The prompt is
 * still accepted (single check), but `streaming` must end in the reducer rather
 * than waiting for a run that will never come.
 */
class PromptDispositionTest {
    private val notify =
        """{"type":"extension_ui_request","method":"notify","id":"u1","title":"pong"}"""

    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private fun reply(disposition: String): JsonElement =
        Json.parseToJsonElement("""{"disposition":"$disposition"}""")

    private fun ChatState.user(): ChatItem.UserMessage =
        items.filterIsInstance<ChatItem.UserMessage>().single()

    private fun prompted(): ChatState = ChatReducer.userPrompt(ChatState(), "ping")

    @Test
    fun handledClearsStreamingAndKeepsTheSingleCheck() {
        var state = prompted()
        assertTrue("sending a prompt arms the run state", state.streaming)

        state = ChatReducer.applyCommandResult(state, "prompt", true, reply("handled"), null)

        assertEquals(DeliveryState.Sent, state.user().delivery)
        assertFalse("an extension-handled prompt must not leave the run state on", state.streaming)
        assertFalse(state.thinking)
        assertNull(state.activeAssistantId)
        assertTrue("no run rows are created", state.items.filterIsInstance<ChatItem.AssistantMessage>().isEmpty())
    }

    @Test
    fun handledClearsARunScopedAssistantId() {
        var state = prompted().copy(activeAssistantId = "i9", thinking = true)
        state = ChatReducer.applyPromptAccepted(state, reply("handled"))
        assertNull(state.activeAssistantId)
        assertFalse(state.thinking)
        assertFalse(state.streaming)
    }

    @Test
    fun handledTicksTheCorrelatedRowOnly() {
        // The controller passes the id of the row its reply belongs to.
        var state = ChatReducer.userPrompt(ChatState(), "first")
        state = ChatReducer.markPromptSent(state)
        state = ChatReducer.userPrompt(state, "second")
        val firstId = state.items.filterIsInstance<ChatItem.UserMessage>().first().id

        state = ChatReducer.applyPromptAccepted(state, reply("handled"), firstId)

        assertFalse(state.streaming)
        assertEquals(DeliveryState.Sent, state.items.filterIsInstance<ChatItem.UserMessage>().first().delivery)
        assertEquals(DeliveryState.Sending, state.items.filterIsInstance<ChatItem.UserMessage>().last().delivery)
    }

    @Test
    fun queuedKeepsStreaming() {
        var state = prompted()
        state = ChatReducer.applyCommandResult(state, "prompt", true, reply("queued"), null)
        assertTrue("a queued prompt still has a run ahead of it", state.streaming)
        assertEquals(DeliveryState.Sent, state.user().delivery)
    }

    @Test
    fun startedKeepsStreaming() {
        var state = prompted()
        state = ChatReducer.applyCommandResult(state, "prompt", true, reply("started"), null)
        assertTrue("a started prompt is streaming", state.streaming)
        assertEquals(DeliveryState.Sent, state.user().delivery)
    }

    @Test
    fun aReplyWithoutADispositionKeepsTheCurrentBehaviour() {
        // Older hosts send no disposition: keep the run state armed.
        var state = prompted()
        state = ChatReducer.applyCommandResult(state, "prompt", true, Json.parseToJsonElement("{}"), null)
        assertTrue(state.streaming)
        assertEquals(DeliveryState.Sent, state.user().delivery)

        state = prompted()
        state = ChatReducer.applyCommandResult(state, "prompt", true, null, null)
        assertTrue(state.streaming)
        assertEquals(DeliveryState.Sent, state.user().delivery)
    }

    @Test
    fun theDispositionIsCaseInsensitive() {
        var state = prompted()
        state = ChatReducer.applyPromptAccepted(state, Json.parseToJsonElement("""{"disposition":"Handled"}"""))
        assertFalse(state.streaming)
    }

    @Test
    fun theExtensionNoticeAfterAHandledPromptDoesNotRestartTheIndicator() {
        // The extension notifies right after handling the prompt. In both orders
        // the notice is transcript-only and must not re-arm the run state.
        var state = prompted()
        state = reduce(state, notify)
        state = ChatReducer.applyCommandResult(state, "prompt", true, reply("handled"), null)
        assertFalse(state.streaming)
        assertEquals(1, state.items.filterIsInstance<ChatItem.Notice>().size)

        state = prompted()
        state = ChatReducer.applyCommandResult(state, "prompt", true, reply("handled"), null)
        state = reduce(state, notify)
        assertFalse("a notify record must not re-enable streaming", state.streaming)
        assertTrue(state.items.filterIsInstance<ChatItem.Notice>().any { it.text.contains("pong") })
    }
}
