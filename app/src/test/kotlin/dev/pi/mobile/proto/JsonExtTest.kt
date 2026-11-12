package dev.pi.mobile.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression from the T4 device run: `meta sessions.list` rows carry Node stat
 * numbers such as `"mtimeMs":1794497263456.789`, which plain toLongOrNull()
 * rejects. Every row then rendered "unknown · N messages" in the Sessions sheet.
 */
class JsonExtTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun longOrAcceptsNodeStyleDecimals() {
        val parsed = obj("""{"mtimeMs":1794497263456.789,"whole":42}""")
        assertEquals(1794497263456L, parsed.longOr("mtimeMs"))
        assertEquals(42L, parsed.longOr("whole"))
        assertNull(parsed.longOr("missing"))
    }

    @Test
    fun intOrAcceptsNodeStyleDecimals() {
        val parsed = obj("""{"count":14.0,"whole":7}""")
        assertEquals(14, parsed.intOr("count"))
        assertEquals(7, parsed.intOr("whole"))
    }

    @Test
    fun textKeysStillRejectNumbers() {
        val parsed = obj("""{"path":"/tmp/x.jsonl","mtimeMs":1.5}""")
        assertEquals("/tmp/x.jsonl", parsed.firstString("path"))
        assertNull(parsed.firstString("mtimeMs"))
    }
}
