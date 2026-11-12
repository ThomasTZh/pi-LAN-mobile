package dev.pi.mobile.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A rendered chunk of an assistant or user message. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Thinking(val text: String, val durationMs: Long? = null) : ContentBlock
    data class Image(val mimeType: String? = null) : ContentBlock
    data class ToolCall(val id: String, val name: String, val arguments: String? = null) : ContentBlock
}

fun List<ContentBlock>.plainText(): String = buildString {
    for (block in this@plainText) {
        if (block is ContentBlock.Text) append(block.text)
    }
}

fun List<ContentBlock>.thinkingText(): String = buildString {
    for (block in this@thinkingText) {
        if (block is ContentBlock.Thinking) append(block.text)
    }
}

data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
)

enum class MessageRole {
    User,
    Assistant,
    ToolResult,
    System,
    Other,
    ;

    companion object {
        fun from(raw: String?): MessageRole = when (raw?.trim()?.lowercase()) {
            "user" -> User
            "assistant" -> Assistant
            "toolresult", "tool_result", "tool" -> ToolResult
            "system" -> System
            else -> Other
        }
    }
}

/**
 * A message from `get_messages`, `message_end` or a partial `message_update`.
 * Coding-agent roles that the phone cannot render as chat bubbles keep their
 * raw role in [rawRole] and are shown as system notices.
 */
data class PiMessage(
    val role: MessageRole,
    val content: List<ContentBlock> = emptyList(),
    val usage: Usage? = null,
    val rawRole: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val timestampMs: Long? = null,
)

data class ModelInfo(
    val provider: String? = null,
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: id
    val qualifiedId: String get() = if (provider.isNullOrBlank()) id else "$provider/$id"
}

data class SessionStats(
    val messages: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val durationMs: Long? = null,
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
    val contextPercent: Int? = null,
)

/** Parses the message object used by events and history. */
object Messages {
    fun parse(element: JsonElement?): PiMessage? {
        val obj = element as? JsonObject ?: return null
        val rawRole = obj.str("role")
        val role = MessageRole.from(rawRole)
        val content = when (val raw = obj["content"]) {
            is JsonArray -> raw.mapNotNull(::parseBlock)
            is JsonPrimitive -> listOf(ContentBlock.Text(raw.content))
            else -> emptyList()
        }
        val toolCallId = obj.firstString("toolCallId", "tool_call_id", "id")
        val toolName = obj.firstString("toolName", "tool_name", "name")
        return PiMessage(
            role = role,
            content = content,
            usage = parseUsage(obj),
            rawRole = rawRole,
            toolCallId = toolCallId,
            toolName = toolName,
            isError = obj.boolOr("isError") ?: false,
            timestampMs = obj.longOr("timestamp") ?: obj.longOr("timestampMs"),
        )
    }

    fun parseUsage(obj: JsonObject): Usage? {
        val usage = obj.obj("usage") ?: return null
        val cost = usage.obj("cost")?.doubleOr("total") ?: usage.doubleOr("cost")
        return Usage(
            inputTokens = usage.intOr("input") ?: usage.intOr("inputTokens"),
            outputTokens = usage.intOr("output") ?: usage.intOr("outputTokens"),
            totalTokens = usage.intOr("totalTokens") ?: usage.intOr("total"),
            costUsd = cost,
        )
    }

    fun parseBlock(element: JsonElement): ContentBlock? {
        val obj = element as? JsonObject
            ?: return (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { ContentBlock.Text(it) }
        return when (obj.str("type")) {
            "text", "text_delta" -> ContentBlock.Text(obj.str("text") ?: obj.str("delta") ?: "")
            "thinking", "thinking_delta" -> ContentBlock.Thinking(obj.str("thinking") ?: obj.str("text") ?: obj.str("delta") ?: "")
            "image" -> ContentBlock.Image(obj.firstString("mimeType", "mediaType"))
            "toolCall", "tool_call", "toolUse", "tool_use" -> ContentBlock.ToolCall(
                id = obj.firstString("id", "toolCallId") ?: "",
                name = obj.firstString("name", "toolName") ?: "",
                arguments = (obj["arguments"] ?: obj["input"])?.renderArguments(),
            )
            else -> null
        }
    }

    private fun JsonElement.renderArguments(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else toString()
        else -> toString()
    }
}
