package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions found by the T4 device run against the real host (records captured
 * with pi-app/tools/ws-dump.mjs):
 *
 *  - pi sends its system prompt as `message_start`/`message_end` with
 *    `role: "system"`, `content: ""` right after `turn_start`. The old reducer
 *    turned that into a "System notice" row, so every run started with an empty
 *    notice line on the phone.
 *  - the echo of the user prompt then arrived after that notice, and the old
 *    dedupe only replaced the row when it was the last item, so the prompt was
 *    rendered twice.
 */
class ChatReducerEchoTest {
    private fun reduce(state: ChatState, json: String): ChatState =
        ChatReducer.reduce(state, Json.parseToJsonElement(json) as JsonObject)

    private val turnStart = """{"type":"turn_start"}"""

    private val systemStart =
        """{"type":"message_start","message":{"role":"system","content":"",""" +
            """"sections":{"preamble":"You are an expert coding assistant."}}}"""

    private val systemEnd =
        """{"type":"message_end","message":{"role":"system","content":"",""" +
            """"sections":{"preamble":"You are an expert coding assistant."}}}"""

    private fun userStart(text: String) =
        """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun userEnd(text: String) =
        """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun blankSystemMessageDoesNotCreateANotice() {
        var state = ChatState()
        state = reduce(state, turnStart)
        state = reduce(state, systemStart)
        state = reduce(state, systemEnd)
        assertTrue("a blank system message must not render", state.items.isEmpty())
    }

    @Test
    fun echoedPromptIsNotDuplicatedAfterANotice() {
        // Exactly the device order: optimistic row, system message, user echo.
        var state = ChatReducer.userPrompt(ChatState(), "Reply with exactly: pong")
        state = reduce(state, turnStart)
        state = reduce(state, systemStart)
        state = reduce(state, systemEnd)
        state = reduce(state, userStart("Reply with exactly: pong"))
        state = reduce(state, userEnd("Reply with exactly: pong"))
        val users = state.items.filterIsInstance<ChatItem.UserMessage>()
        assertEquals(1, users.size)
        assertEquals("Reply with exactly: pong", users.single().text)
        assertTrue(state.streaming)
    }

    @Test
    fun aRealSecondPromptStillAddsARow() {
        // After a completed turn the same text is a new prompt, not an echo.
        var state = ChatReducer.userPrompt(ChatState(), "ping")
        state = reduce(state, userStart("ping"))
        state = reduce(state, userEnd("ping"))
        state = reduce(state, """{"type":"agent_settled","aborted":false}""")
        state = reduce(state, """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"pong"}]}}""")
        state = ChatReducer.userPrompt(state, "ping")
        state = reduce(state, userStart("ping"))
        val users = state.items.filterIsInstance<ChatItem.UserMessage>()
        assertEquals(2, users.size)
    }
}
