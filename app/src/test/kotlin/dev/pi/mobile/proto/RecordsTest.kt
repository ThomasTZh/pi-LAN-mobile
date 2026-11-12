package dev.pi.mobile.proto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {
    private fun parse(json: String): PiRecord = Records.parse(Json.parseToJsonElement(json))

    @Test
    fun parsesHelloAndMeta() {
        val hello = parse("""{"type":"hello","ok":true,"pid":42}""") as PiRecord.Hello
        assertEquals(42, hello.pid)
        assertTrue(hello.ok)

        val meta = parse("""{"type":"meta","id":"p1","ok":true,"data":{"pong":true}}""") as PiRecord.Meta
        assertEquals("p1", meta.id)
        assertEquals(true, meta.ok)
    }

    @Test
    fun parsesStreamEvents() {
        val text = parse(
            """{"type":"message_update","contentIndex":0,"assistantMessageEvent":{"type":"text_delta","delta":"Hi"}}""",
        ) as PiRecord.MessageUpdate
        assertEquals(0, text.contentIndex)
        assertEquals(AssistantStreamEvent.TextDelta("Hi"), text.event)

        val thinking = parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","thinking":"why"}}""",
        ) as PiRecord.MessageUpdate
        assertEquals(null, thinking.contentIndex)
        assertEquals(AssistantStreamEvent.ThinkingDelta("why"), thinking.event)

        val toolStart = parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"bash"}}""",
        ) as PiRecord.MessageUpdate
        assertEquals(AssistantStreamEvent.ToolCallStart("c1", "bash"), toolStart.event)
        assertEquals(1, toolStart.contentIndex)

        val textEnd = parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_end","contentIndex":0,"content":"final"}}""",
        ) as PiRecord.MessageUpdate
        assertEquals(AssistantStreamEvent.TextEnd("final"), textEnd.event)

        val toolEnd = parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","contentIndex":1,"toolCall":{"id":"c1","name":"bash","arguments":{"command":"ls"}}}}""",
        ) as PiRecord.MessageUpdate
        val end = toolEnd.event as AssistantStreamEvent.ToolCallEnd
        assertEquals("c1", end.toolCallId)
        assertEquals("bash", end.toolName)
        assertTrue(end.arguments.orEmpty().contains("ls"))
    }

    @Test
    fun parsesToolExecutionRecords() {
        val start = parse(
            """{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"ls -la"}}""",
        ) as PiRecord.ToolExecutionStart
        assertEquals("c1", start.toolCallId)
        assertEquals("bash", start.toolName)
        assertTrue(start.args.orEmpty().contains("ls -la"))

        val update = parse(
            """{"type":"tool_execution_update","toolCallId":"c1","partialResult":{"content":[{"type":"text","text":"total 8"}]}}""",
        ) as PiRecord.ToolExecutionUpdate
        assertEquals("total 8", update.partialResult)

        val end = parse(
            """{"type":"tool_execution_end","toolCallId":"c1","result":"done","isError":true,"durationMs":12}""",
        ) as PiRecord.ToolExecutionEnd
        assertTrue(end.isError)
        assertEquals(12L, end.durationMs)
        assertEquals("done", end.result)
    }

    @Test
    fun parsesMessageEndContentBlocks() {
        val record = parse(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hi"},{"type":"thinking","thinking":"plan"},{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}],"usage":{"input":3,"output":4,"totalTokens":7}}}""",
        ) as PiRecord.MessageEnd
        val message = record.message
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(3, message.content.size)
        assertTrue(message.content[0] is ContentBlock.Text)
        assertTrue(message.content[1] is ContentBlock.Thinking)
        val call = message.content[2] as ContentBlock.ToolCall
        assertEquals("c1", call.id)
        assertEquals("bash", call.name)
        assertTrue(call.arguments.orEmpty().contains("ls"))
        assertEquals(7, message.usage?.totalTokens)
    }

    @Test
    fun parsesDialogsAndChildEvents() {
        val confirm = parse(
            """{"type":"extension_ui_request","id":"ui1","method":"confirm","title":"Allow?","message":"rm -rf build","timeoutMs":30000}""",
        ) as PiRecord.ExtensionUiRequest
        assertEquals("confirm", confirm.method)
        assertEquals("Allow?", confirm.title)
        assertEquals("rm -rf build", confirm.message)
        assertEquals(30_000L, confirm.timeoutMs)

        val select = parse(
            """{"type":"extension_ui_request","id":"ui2","method":"select","title":"Pick","options":["A",{"label":"B"}]}""",
        ) as PiRecord.ExtensionUiRequest
        assertEquals(listOf("A", "B"), select.options)

        val child = parse("""{"type":"child","event":"exit","code":1,"stderr":"boom"}""") as PiRecord.Child
        assertEquals("exit", child.kind)
        assertEquals("boom", child.detail)
    }

    @Test
    fun unknownTypesAreForwardCompatible() {
        assertEquals(PiRecord.Unknown("persona_update"), parse("""{"type":"persona_update"}"""))
        assertEquals(PiRecord.Unknown(""), parse("""{"nope":1}"""))
        assertEquals(PiRecord.Unknown("message_update"), parse("""{"type":"message_update"}"""))
    }

    @Test
    fun parsesQueueSettledAndCompaction() {
        val queue = parse(
            """{"type":"queue_update","steering":["next"],"followUp":["after"]}""",
        ) as PiRecord.QueueUpdate
        assertEquals(listOf("next", "after"), queue.queue)

        val settled = parse("""{"type":"agent_settled","aborted":true}""") as PiRecord.AgentSettled
        assertTrue(settled.aborted)

        val compaction = parse(
            """{"type":"compaction_end","reason":"threshold","result":{"summary":"42 messages"},"aborted":false}""",
        ) as PiRecord.Compaction
        assertEquals("compaction_end", compaction.kind)
        assertEquals("42 messages", compaction.text)

        val renamed = parse("""{"type":"session_info_changed","name":"demo"}""") as PiRecord.SessionInfoChanged
        assertEquals("demo", renamed.name)
        val level = parse("""{"type":"thinking_level_changed","level":"high"}""") as PiRecord.ThinkingLevelChanged
        assertEquals("high", level.level)
    }
}
