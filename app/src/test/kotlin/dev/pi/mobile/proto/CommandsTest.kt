package dev.pi.mobile.proto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test
    fun helloOmitsUnknownFields() {
        assertEquals("""{"type":"hello","cwd":"/p"}""", Commands.hello("/p").toString())
        assertEquals("""{"type":"hello"}""", Commands.hello(null).toString())
        val withSession = Commands.hello("/p", "/p/session.jsonl", listOf("--no-mcp")).toString()
        assertTrue(withSession.contains("\"session\":\"/p/session.jsonl\""))
        assertTrue(withSession.contains("--no-mcp"))
    }

    @Test
    fun promptCarriesMessageAndBehavior() {
        val json = Commands.prompt("hello", streamingBehavior = "steer").toString()
        assertTrue(json.contains("\"type\":\"prompt\""))
        assertTrue(json.contains("\"message\":\"hello\""))
        assertTrue(json.contains("\"streamingBehavior\":\"steer\""))
    }

    @Test
    fun promptImages() {
        val json = Commands.prompt("look", listOf(Commands.ImageAttachment("image/jpeg", "AAAA"))).toString()
        assertTrue(json.contains("\"type\":\"image\""))
        assertTrue(json.contains("\"mimeType\":\"image/jpeg\""))
    }

    @Test
    fun metaKeepsReservedFields() {
        val json = Commands.meta(
            "m1",
            "sessions.rename",
            buildJsonObject {
                put("id", "evil")
                put("action", "oops")
                put("type", "meta2")
                put("path", "/s")
            },
        ).toString()
        assertTrue(json.contains("\"id\":\"m1\""))
        assertTrue(json.contains("\"action\":\"sessions.rename\""))
        assertTrue(json.contains("\"path\":\"/s\""))
        assertFalse(json.contains("evil"))
        assertFalse(json.contains("oops"))
    }

    @Test
    fun dialogResponses() {
        assertEquals(
            """{"type":"extension_ui_response","id":"ui1","confirmed":false}""",
            Commands.confirmResponse("ui1", false).toString(),
        )
        assertEquals(
            """{"type":"extension_ui_response","id":"ui1","value":"yes"}""",
            Commands.valueResponse("ui1", "yes").toString(),
        )
        assertEquals(
            """{"type":"extension_ui_response","id":"ui1","cancelled":true}""",
            Commands.cancelledResponse("ui1").toString(),
        )
    }

    @Test
    fun modelAndThinkingBuilders() {
        assertEquals(
            """{"type":"set_model","provider":"llama","modelId":"qwen3"}""",
            Commands.setModel("llama", "qwen3").toString(),
        )
        assertEquals(
            """{"type":"set_thinking_level","level":"high"}""",
            Commands.setThinkingLevel("high").toString(),
        )
        assertEquals("""{"type":"abort"}""", Commands.abort().toString())
        assertEquals("""{"type":"follow_up","message":"later"}""", Commands.followUp("later").toString())
        assertEquals("""{"type":"steer","message":"stop"}""", Commands.steer("stop").toString())
    }
}
