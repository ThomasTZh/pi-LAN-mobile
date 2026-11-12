package dev.pi.mobile.session

import dev.pi.mobile.proto.arr
import dev.pi.mobile.proto.firstString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A slash command the host reports through `get_commands`: an extension
 * command, a prompt template, or a skill. Sending `/name args` as a prompt runs
 * it, and Pi expands skills and templates before the model sees the message.
 */
data class PiCommand(
    val name: String,
    val description: String? = null,
    val source: String = "extension",
) {
    val slash: String get() = "/$name"

    val sourceLabel: String
        get() = when (source) {
            "extension" -> "extension"
            "skill" -> "skill"
            "prompt" -> "template"
            else -> source
        }
}

object CommandCatalog {
    /** Parses `data.commands` and drops entries without a name. */
    fun parse(data: JsonElement?): List<PiCommand> {
        val array = (data as? JsonObject)?.arr("commands") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.firstString("name")
                ?.trim()
                ?.removePrefix("/")
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            PiCommand(
                name = name,
                description = obj.firstString("description")?.trim()?.takeIf { it.isNotEmpty() },
                source = obj.firstString("source") ?: "extension",
            )
        }.distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    /** Commands whose name starts with [prefix] (the text after "/"). */
    fun suggestions(commands: List<PiCommand>, prefix: String, limit: Int = 6): List<PiCommand> {
        val query = prefix.lowercase()
        return commands
            .filter { it.name.lowercase().startsWith(query) }
            .sortedBy { it.name.lowercase() }
            .take(limit)
    }
}
