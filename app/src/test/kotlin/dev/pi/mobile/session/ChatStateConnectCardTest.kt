package dev.pi.mobile.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review F17: the connect-card decision used to live inline in `AppRoot`, so
 * reverting that call site would not have failed any test. The rule is now a
 * pure property on [ChatState] that `AppRoot` reads verbatim; these cases pin
 * it (including the F7 spinner window).
 */
class ChatStateConnectCardTest {
    @Test
    fun idleDisconnectedAndFailedShowTheConnectCard() {
        assertTrue(ChatState(phase = ConnectionPhase.Idle).showsConnectCard)
        assertTrue(ChatState(phase = ConnectionPhase.Disconnected).showsConnectCard)
        assertTrue(ChatState(phase = ConnectionPhase.Failed).showsConnectCard)
    }

    @Test
    fun aFirstConnectWithNoTranscriptKeepsTheCardUp() {
        // This is the window in which the in-button spinner is visible (F7).
        assertTrue(
            ChatState(phase = ConnectionPhase.Connecting, items = emptyList()).showsConnectCard,
        )
    }

    @Test
    fun aReconnectWithATranscriptKeepsTheChatScreen() {
        val state = ChatState(
            phase = ConnectionPhase.Connecting,
            items = listOf(ChatItem.UserMessage("i1", "hello")),
        )
        assertFalse(state.showsConnectCard)
        assertFalse(
            ChatState(
                phase = ConnectionPhase.Connecting,
                items = listOf(ChatItem.Notice("i1", "Run interrupted")),
            ).showsConnectCard,
        )
    }

    @Test
    fun connectedAndReconnectingStatesAreChatScreens() {
        assertFalse(ChatState(phase = ConnectionPhase.Connected).showsConnectCard)
        assertFalse(
            ChatState(phase = ConnectionPhase.Reconnecting, items = listOf(ChatItem.UserMessage("i1", "hi")))
                .showsConnectCard,
        )
    }
}
