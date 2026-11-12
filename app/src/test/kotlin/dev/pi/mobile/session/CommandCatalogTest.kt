package dev.pi.mobile.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogTest {
    @Test
    fun parsesSourcesDropsNamelessAndSorts() {
        val data = Json.parseToJsonElement(
            """
            {"commands":[
              {"name":"plan","description":"Plan mode","source":"extension"},
              {"name":"skill:debug","source":"skill"},
              {"name":"/fix-tests","description":"Fix failing tests","source":"prompt"},
              {"description":"no name"}
            ]}
            """.trimIndent(),
        )
        val commands = CommandCatalog.parse(data)
        assertEquals(listOf("fix-tests", "plan", "skill:debug"), commands.map { it.name })
        assertEquals("prompt", commands[0].source)
        assertEquals("Plan mode", commands[1].description)
        assertEquals("skill", commands[2].sourceLabel)
        assertEquals("/skill:debug", commands[2].slash)
    }

    @Test
    fun suggestionsFilterByPrefixAndLimit() {
        val commands = listOf(
            PiCommand("plan"),
            PiCommand("plan:off"),
            PiCommand("fix-tests"),
            PiCommand("skill:debug", source = "skill"),
        )
        assertEquals(listOf("plan", "plan:off"), CommandCatalog.suggestions(commands, "pl").map { it.name })
        assertEquals(listOf("fix-tests"), CommandCatalog.suggestions(commands, "fix").map { it.name })
        assertTrue(CommandCatalog.suggestions(commands, "zzz").isEmpty())
        assertTrue(CommandCatalog.suggestions(commands, "").size <= 6)
    }

    @Test
    fun missingPayloadYieldsNoCommands() {
        assertTrue(CommandCatalog.parse(null).isEmpty())
        assertTrue(CommandCatalog.parse(Json.parseToJsonElement("{}")).isEmpty())
    }
}
