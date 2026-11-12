package dev.pi.mobile.proto

import dev.pi.mobile.core.stripAnsi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed Pi records. Unknown record types become [PiRecord.Unknown] and are
 * ignored by the reducer without touching state, which keeps the app forward
 * compatible with newer harness versions.
 */
sealed interface PiRecord {
    data class Hello(val pid: Int?, val ok: Boolean, val error: String?) : PiRecord
    data class Meta(val id: String?, val ok: Boolean?, val data: JsonElement?, val error: String?) : PiRecord
    data class Response(val command: String?, val success: Boolean, val data: JsonElement?, val error: String?) : PiRecord

    data object AgentStart : PiRecord
    data object TurnStart : PiRecord
    data class MessageStart(val role: MessageRole, val message: PiMessage?) : PiRecord
    data class MessageUpdate(
        val contentIndex: Int?,
        val event: AssistantStreamEvent,
        val message: PiMessage? = null,
    ) : PiRecord
    data class MessageEnd(val message: PiMessage) : PiRecord
    data object TurnEnd : PiRecord
    data class ToolExecutionStart(val toolCallId: String, val toolName: String, val args: String?) : PiRecord
    data class ToolExecutionUpdate(val toolCallId: String, val partialResult: String?) : PiRecord
    data class ToolExecutionEnd(
        val toolCallId: String,
        val result: String?,
        val isError: Boolean,
        val durationMs: Long?,
    ) : PiRecord
    data class QueueUpdate(val queue: List<String>) : PiRecord
    data object AgentEnd : PiRecord
    data class AgentSettled(val aborted: Boolean) : PiRecord
    data class Compaction(val kind: String, val text: String?) : PiRecord
    data class SessionInfoChanged(val name: String?) : PiRecord
    data class ThinkingLevelChanged(val level: String?) : PiRecord
    data class ExtensionUiRequest(
        val id: String,
        val method: String,
        val title: String?,
        val message: String?,
        val options: List<String>,
        val placeholder: String?,
        val prefill: String?,
        val timeoutMs: Long?,
        val notifyType: String? = null,
        val statusKey: String? = null,
        val statusText: String? = null,
        val widgetKey: String? = null,
        val widgetLines: List<String> = emptyList(),
        val editorText: String? = null,
    ) : PiRecord
    data class Child(val kind: String, val detail: String?) : PiRecord
    data class Unknown(val type: String) : PiRecord
}

sealed interface AssistantStreamEvent {
    data class TextDelta(val text: String) : AssistantStreamEvent
    data class TextEnd(val text: String?) : AssistantStreamEvent
    data class ThinkingDelta(val text: String) : AssistantStreamEvent
    data class ThinkingEnd(val text: String?) : AssistantStreamEvent
    data class ToolCallStart(val toolCallId: String?, val toolName: String?) : AssistantStreamEvent
    data class ToolCallDelta(val text: String, val toolCallId: String?) : AssistantStreamEvent
    data class ToolCallEnd(
        val toolCallId: String?,
        val toolName: String?,
        val arguments: String?,
    ) : AssistantStreamEvent
    data class Unknown(val type: String) : AssistantStreamEvent
}

object Records {
    fun parse(element: JsonElement): PiRecord {
        val obj = element as? JsonObject ?: return PiRecord.Unknown("")
        return parse(obj)
    }

    fun parse(obj: JsonObject): PiRecord {
        val type = obj.str("type") ?: return PiRecord.Unknown("")
        return when (type) {
            "hello" -> PiRecord.Hello(
                pid = obj.intOr("pid"),
                ok = obj.boolOr("ok") ?: true,
                error = obj.errorText(),
            )
            "meta" -> PiRecord.Meta(
                id = obj.str("id"),
                ok = obj.boolOr("ok"),
                data = obj["data"],
                error = obj.errorText(),
            )
            "response" -> PiRecord.Response(
                command = obj.firstString("command", "commandType"),
                success = obj.boolOr("success") ?: false,
                data = obj["data"],
                error = obj.errorText(),
            )
            "agent_start" -> PiRecord.AgentStart
            "turn_start" -> PiRecord.TurnStart
            "turn_end" -> PiRecord.TurnEnd
            "agent_end" -> PiRecord.AgentEnd
            "agent_settled" -> PiRecord.AgentSettled(aborted = obj.boolOr("aborted") ?: false)
            "message_start" -> {
                val message = Messages.parse(obj["message"])
                PiRecord.MessageStart(
                    role = message?.role ?: MessageRole.from(obj.str("role")),
                    message = message,
                )
            }
            "message_update" -> {
                val event = obj.obj("assistantMessageEvent") ?: obj.obj("event")
                if (event == null) {
                    PiRecord.Unknown(type)
                } else {
                    PiRecord.MessageUpdate(
                        // The documented wire shape nests contentIndex in the event.
                        contentIndex = event.intOr("contentIndex") ?: obj.intOr("contentIndex"),
                        event = parseStreamEvent(event),
                        message = Messages.parse(obj["message"]),
                    )
                }
            }
            "message_end" -> {
                val message = Messages.parse(obj["message"])
                    ?: Messages.parse(obj.obj("data")?.get("message"))
                if (message == null) PiRecord.Unknown(type) else PiRecord.MessageEnd(message)
            }
            "tool_execution_start" -> PiRecord.ToolExecutionStart(
                toolCallId = obj.firstString("toolCallId", "tool_call_id") ?: "",
                toolName = obj.firstString("toolName", "tool_name", "name") ?: "tool",
                args = (obj["args"] ?: obj["arguments"])?.renderText(),
            )
            "tool_execution_update" -> PiRecord.ToolExecutionUpdate(
                toolCallId = obj.firstString("toolCallId", "tool_call_id") ?: "",
                partialResult = (obj["partialResult"] ?: obj["result"] ?: obj["output"])?.renderText(),
            )
            "tool_execution_end" -> PiRecord.ToolExecutionEnd(
                toolCallId = obj.firstString("toolCallId", "tool_call_id") ?: "",
                result = (obj["result"] ?: obj["output"])?.renderText(),
                isError = obj.boolOr("isError") ?: false,
                durationMs = obj.longOr("durationMs") ?: obj.longOr("duration"),
            )
            "queue_update" -> PiRecord.QueueUpdate(
                // Wire shape: {steering: [], followUp: []}; the legacy single
                // "queue" field is accepted for fixtures.
                queue = when {
                    obj.arr("steering") != null || obj.arr("followUp") != null ->
                        stringList(obj.arr("steering")) + stringList(obj.arr("followUp"))
                    else -> stringList(obj["queue"] as? kotlinx.serialization.json.JsonArray)
                },
            )
            "session_info_changed" -> PiRecord.SessionInfoChanged(obj.str("name"))
            "thinking_level_changed" -> PiRecord.ThinkingLevelChanged(obj.str("level"))
            "extension_ui_request" -> parseUiRequest(obj)
            "child" -> PiRecord.Child(
                kind = obj.firstString("event", "kind", "action") ?: "event",
                detail = obj.firstString("stderr", "error", "detail", "message", "reason"),
            )
            else -> when {
                type.startsWith("compaction") -> PiRecord.Compaction(
                    kind = type,
                    // compaction_end carries result.summary; compaction_start a reason.
                    text = obj.obj("result")?.firstString("summary")
                        ?: obj.firstString("summary", "reason", "text", "message"),
                )
                else -> PiRecord.Unknown(type)
            }
        }
    }

    fun parseStreamEvent(obj: JsonObject): AssistantStreamEvent = when (obj.str("type")) {
        "text_delta" -> AssistantStreamEvent.TextDelta(obj.str("delta") ?: obj.str("text") ?: "")
        "text_end" -> AssistantStreamEvent.TextEnd(
            obj.str("content") ?: obj.str("text") ?: obj.str("delta"),
        )
        "thinking_delta" -> AssistantStreamEvent.ThinkingDelta(
            obj.str("delta") ?: obj.str("thinking") ?: obj.str("text") ?: "",
        )
        "thinking_end" -> AssistantStreamEvent.ThinkingEnd(
            obj.str("content") ?: obj.str("thinking") ?: obj.str("text"),
        )
        "toolcall_start", "tool_call_start" -> AssistantStreamEvent.ToolCallStart(
            toolCallId = obj.firstString("toolCallId", "id"),
            toolName = obj.firstString("toolName", "name"),
        )
        "toolcall_delta", "tool_call_delta" -> AssistantStreamEvent.ToolCallDelta(
            text = obj.str("delta") ?: "",
            toolCallId = obj.firstString("toolCallId", "id"),
        )
        "toolcall_end", "tool_call_end" -> {
            // Wire shape: {type:"toolcall_end", contentIndex, toolCall:{id,name,arguments}}
            val call = obj.obj("toolCall")
            AssistantStreamEvent.ToolCallEnd(
                toolCallId = call?.firstString("id", "toolCallId") ?: obj.firstString("toolCallId", "id"),
                toolName = call?.firstString("name", "toolName") ?: obj.firstString("toolName", "name"),
                arguments = (call?.get("arguments") ?: obj["arguments"] ?: obj["input"])?.renderText(),
            )
        }
        else -> AssistantStreamEvent.Unknown(obj.str("type") ?: "")
    }

    private fun parseUiRequest(obj: JsonObject): PiRecord.ExtensionUiRequest {
        val options = obj.arr("options")?.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> element.firstString("label", "value", "name")
                else -> null
            }
        } ?: emptyList()
        return PiRecord.ExtensionUiRequest(
            id = obj.firstString("id") ?: "",
            method = obj.firstString("method", "dialogType", "kind") ?: "unknown",
            title = obj.firstString("title", "header")?.stripAnsi(),
            message = obj.firstString("message", "question", "body", "prompt")?.stripAnsi(),
            options = options.map { it.stripAnsi() },
            placeholder = obj.firstString("placeholder")?.stripAnsi(),
            prefill = obj.firstString("prefill", "value")?.stripAnsi(),
            timeoutMs = obj.longOr("timeoutMs") ?: obj.longOr("timeout"),
            notifyType = obj.firstString("notifyType"),
            statusKey = obj.firstString("statusKey"),
            statusText = obj.firstString("statusText")?.stripAnsi(),
            widgetKey = obj.firstString("widgetKey"),
            widgetLines = stringList(obj.arr("widgetLines")).map { it.stripAnsi() },
            editorText = obj.firstString("text")?.stripAnsi(),
        )
    }

    private fun stringList(array: kotlinx.serialization.json.JsonArray?): List<String> =
        array?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            .orEmpty()
}
