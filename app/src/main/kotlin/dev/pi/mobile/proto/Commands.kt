package dev.pi.mobile.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request builders for the Pi RPC contract (PLAN.md 5.3). Every command is one
 * JSON object; optional fields are omitted rather than sent as null.
 */
object Commands {
    const val PROMPT = "prompt"
    const val STEER = "steer"
    const val FOLLOW_UP = "follow_up"
    const val ABORT = "abort"
    const val GET_STATE = "get_state"
    const val GET_MESSAGES = "get_messages"
    const val GET_AVAILABLE_MODELS = "get_available_models"
    const val GET_AVAILABLE_THINKING_LEVELS = "get_available_thinking_levels"
    const val SET_MODEL = "set_model"
    const val SET_THINKING_LEVEL = "set_thinking_level"
    const val GET_SESSION_STATS = "get_session_stats"
    const val SET_SESSION_NAME = "set_session_name"
    const val GET_COMMANDS = "get_commands"

    data class ImageAttachment(val mimeType: String, val base64Data: String)

    fun hello(cwd: String?, session: String? = null, extraArgs: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "hello")
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
            if (!session.isNullOrBlank()) put("session", session)
            if (extraArgs.isNotEmpty()) {
                put(
                    "extraArgs",
                    buildJsonArray { extraArgs.forEach { add(it) } },
                )
            }
        }

    fun command(name: String, payload: JsonObject? = null): JsonObject = buildJsonObject {
        put("type", name)
        payload?.forEach { (key, value) -> if (key != "type") put(key, value) }
    }

    fun prompt(
        message: String,
        images: List<ImageAttachment> = emptyList(),
        streamingBehavior: String? = null,
    ): JsonObject = command(
        PROMPT,
        buildJsonObject {
            put("message", message)
            if (images.isNotEmpty()) {
                put(
                    "images",
                    buildJsonArray {
                        images.forEach { image ->
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    put("mimeType", image.mimeType)
                                    put("data", image.base64Data)
                                },
                            )
                        }
                    },
                )
            }
            if (!streamingBehavior.isNullOrBlank()) put("streamingBehavior", streamingBehavior)
        },
    )

    fun steer(message: String): JsonObject =
        command(STEER, buildJsonObject { put("message", message) })

    fun followUp(message: String): JsonObject =
        command(FOLLOW_UP, buildJsonObject { put("message", message) })

    fun abort(): JsonObject = command(ABORT)

    fun getState(): JsonObject = command(GET_STATE)

    fun getMessages(): JsonObject = command(GET_MESSAGES)

    fun getAvailableModels(): JsonObject = command(GET_AVAILABLE_MODELS)

    fun getAvailableThinkingLevels(): JsonObject = command(GET_AVAILABLE_THINKING_LEVELS)

    fun setModel(provider: String, modelId: String): JsonObject = command(
        SET_MODEL,
        buildJsonObject {
            put("provider", provider)
            put("modelId", modelId)
        },
    )

    fun setThinkingLevel(level: String): JsonObject =
        command(SET_THINKING_LEVEL, buildJsonObject { put("level", level) })

    fun getSessionStats(): JsonObject = command(GET_SESSION_STATS)

    fun setSessionName(name: String): JsonObject =
        command(SET_SESSION_NAME, buildJsonObject { put("name", name) })

    fun getCommands(): JsonObject = command(GET_COMMANDS)

    fun meta(id: String, action: String, payload: JsonObject? = null): JsonObject = buildJsonObject {
        put("type", "meta")
        put("id", id)
        put("action", action)
        payload?.forEach { (key, value) -> if (key !in META_RESERVED) put(key, value) }
    }

    fun confirmResponse(id: String, confirmed: Boolean): JsonObject = buildJsonObject {
        put("type", "extension_ui_response")
        put("id", id)
        put("confirmed", confirmed)
    }

    fun valueResponse(id: String, value: String): JsonObject = buildJsonObject {
        put("type", "extension_ui_response")
        put("id", id)
        put("value", value)
    }

    fun cancelledResponse(id: String): JsonObject = buildJsonObject {
        put("type", "extension_ui_response")
        put("id", id)
        put("cancelled", true)
    }

    fun sessionsList(): String = "sessions.list"
    fun sessionsRename(): String = "sessions.rename"
    fun sessionsDelete(): String = "sessions.delete"
    fun ping(): String = "ping"
    fun projectInfo(): String = "project.info"
    fun projectCheck(): String = "project.check"

    private val META_RESERVED = setOf("type", "id", "action")

    /** Parses an array-valued or object-wrapped array payload leniently. */
    fun arrayOf(element: JsonElement?, key: String): JsonArray? = when (element) {
        is JsonArray -> element
        is JsonObject -> element.arr(key)
        else -> null
    }
}
