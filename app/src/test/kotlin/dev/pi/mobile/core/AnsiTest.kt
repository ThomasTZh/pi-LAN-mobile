package dev.pi.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTest {
    @Test
    fun stripsSgrColoursAndResets() {
        assertEquals("Ready", "\u001B[39m\u001B[2mReady\u001B[22;39m".stripAnsi())
        assertEquals("red", "\u001B[38;5;196mred\u001B[0m".stripAnsi())
    }

    @Test
    fun stripsOscTitlesAndHyperlinks() {
        assertEquals("text", "\u001B]0;window title\u0007text".stripAnsi())
        assertEquals(
            "link",
            "\u001B]8;;https://example.com\u001B\\link\u001B]8;;\u001B\\".stripAnsi(),
        )
    }

    @Test
    fun stripsCursorAndEraseSequences() {
        assertEquals("abc", "\u001B[2K\u001B[1Gabc\u001B[?25l".stripAnsi())
    }

    @Test
    fun leavesPlainTextNewlinesAndBrackets() {
        assertEquals("hello [1] list\nsecond", "hello [1] list\nsecond".stripAnsi())
    }
}
