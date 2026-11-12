package dev.pi.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlTest {
    @Test
    fun splitsRecordsAcrossChunks() {
        val buffer = JsonlBuffer()
        assertEquals(emptyList<String>(), buffer.feed("{\"a\":1}"))
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), buffer.feed("\n{\"b\":2}\n"))
        assertEquals(emptyList<String>(), buffer.feed("{\"c\":"))
        assertEquals(listOf("{\"c\":3}"), buffer.feed("3}\n"))
    }

    @Test
    fun toleratesCrlfAndBlankLines() {
        val buffer = JsonlBuffer()
        val records = buffer.feed("{\"a\":1}\r\n\r\n{\"b\":2}\r\n")
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), records)
    }

    @Test
    fun keepsPartialRecordUntilTerminator() {
        val buffer = JsonlBuffer()
        assertEquals(emptyList<String>(), buffer.feed("{\"partial\":true}"))
        assertEquals("{\"partial\":true}", buffer.flush())
        assertNull(buffer.flush())
    }

    @Test
    fun linesHelperIgnoresBlankRecords() {
        assertEquals(
            listOf("{\"a\":1}", "{\"b\":2}"),
            Jsonl.lines("{\"a\":1}\n\n  \n{\"b\":2}\n"),
        )
        assertTrue(Jsonl.isRecord("{\"a\":1}"))
        assertFalse(Jsonl.isRecord("not json"))
    }

    @Test
    fun encodeAddsExactlyOneNewline() {
        assertEquals("{\"a\":1}\n", Jsonl.encode("{\"a\":1}"))
        assertEquals("{\"a\":1}\n", Jsonl.encode("{\"a\":1}\n"))
    }

    @Test
    fun oversizedPartialRecordIsDropped() {
        val buffer = JsonlBuffer(maxRecordChars = 8)
        assertEquals(emptyList<String>(), buffer.feed("123456789"))
        assertEquals(listOf("ok"), buffer.feed("ok\n"))
    }
}
