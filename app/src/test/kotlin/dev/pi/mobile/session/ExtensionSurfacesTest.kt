package dev.pi.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extension status entries, widgets and editor text survive the reducer. */
class ExtensionSurfacesTest {
    @Test
    fun status_is_set_and_cleared() {
        var state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"extension_ui_request","id":"1","method":"setStatus","statusKey":"plan-mode","statusText":"Plan mode"}""",
        )
        assertEquals("Plan mode", state.statusEntries["plan-mode"])

        state = ChatReducer.reduceJson(
            state,
            """{"type":"extension_ui_request","id":"2","method":"setStatus","statusKey":"plan-mode"}""",
        )
        assertTrue(state.statusEntries.isEmpty())
    }

    @Test
    fun widget_is_set_and_cleared() {
        var state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"extension_ui_request","id":"1","method":"setWidget","widgetKey":"plan-todos","widgetLines":["1. read","2. write"]}""",
        )
        assertEquals(listOf("1. read", "2. write"), state.widgets["plan-todos"])

        state = ChatReducer.reduceJson(
            state,
            """{"type":"extension_ui_request","id":"2","method":"setWidget","widgetKey":"plan-todos"}""",
        )
        assertTrue(state.widgets.isEmpty())
    }

    @Test
    fun editor_text_bumps_the_draft_id() {
        var state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"extension_ui_request","id":"1","method":"set_editor_text","text":"draft one"}""",
        )
        assertEquals("draft one", state.editorDraft)
        assertEquals(1L, state.editorDraftId)

        state = ChatReducer.reduceJson(
            state,
            """{"type":"extension_ui_request","id":"2","method":"set_editor_text","text":"draft two"}""",
        )
        assertEquals("draft two", state.editorDraft)
        assertEquals(2L, state.editorDraftId)
    }

    @Test
    fun extension_text_drops_terminal_codes() {
        var state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"extension_ui_request","id":"1","method":"setStatus","statusKey":"working-indicator","statusText":"\u001b[39m\u001b[2mIndicator: custom spinner\u001b[22;39m"}""",
        )
        assertEquals("Indicator: custom spinner", state.statusEntries["working-indicator"])

        state = ChatReducer.reduceJson(
            state,
            """{"type":"extension_ui_request","id":"2","method":"setWidget","widgetKey":"plan-todos","widgetLines":["\u001b[2m1. read\u001b[0m"]}""",
        )
        assertEquals(listOf("1. read"), state.widgets["plan-todos"])
    }

    @Test
    fun tool_output_drops_terminal_codes() {
        var state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{}}""",
        )
        state = ChatReducer.reduceJson(
            state,
            """{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash","result":"\u001b[31merror\u001b[0m","isError":true,"durationMs":5}""",
        )
        val card = state.items.filterIsInstance<ChatItem.ToolCard>().single()
        assertEquals("error", card.output)
    }

    @Test
    fun notify_still_becomes_a_notice() {
        val state = ChatReducer.reduceJson(
            ChatState(),
            """{"type":"extension_ui_request","id":"1","method":"notify","message":"Saved"}""",
        )
        assertTrue(state.items.any { it is ChatItem.Notice })
    }
}
