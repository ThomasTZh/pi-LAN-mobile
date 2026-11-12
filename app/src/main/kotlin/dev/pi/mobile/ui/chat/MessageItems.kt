package dev.pi.mobile.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.core.TimeFormat
import dev.pi.mobile.session.ChatItem
import dev.pi.mobile.session.NoticeKind
import dev.pi.mobile.session.ToolStatus
import dev.pi.mobile.proto.ContentBlock
import dev.pi.mobile.ui.components.CopyGlyph
import dev.pi.mobile.ui.markdown.MarkdownText
import dev.pi.mobile.ui.theme.PiDims
import dev.pi.mobile.ui.theme.codeTextStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A user prompt: right-aligned bubble, 88% max width, long-press to copy. */
@Composable
fun UserMessageRow(item: ChatItem.UserMessage, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .pointerInput(item.text) {
                    detectTapGestures(onLongPress = { onCopy(item.text) })
                },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(
                text = item.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * An assistant turn: collapsed thinking blocks, then prose rendered with the
 * markdown subset. Long-press copies the prose; links open through [onOpenLink].
 */
@Composable
fun AssistantMessageRow(
    item: ChatItem.AssistantMessage,
    onCopy: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val thinkingBlocks = item.blocks.filterIsInstance<ContentBlock.Thinking>()
    val text = item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(item.id, text) {
                if (text.isNotBlank()) {
                    detectTapGestures(onLongPress = { onCopy(text) })
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        thinkingBlocks.forEach { block ->
            ThinkingBlock(text = block.text, durationMs = block.durationMs, id = item.id)
        }
        if (text.isNotBlank()) {
            MarkdownText(markdown = text, onOpenLink = onOpenLink)
        }
    }
}

/** Collapsed "Thinking · 3.2s" row; expands to the reasoning in muted prose. */
@Composable
fun ThinkingBlock(text: String, durationMs: Long?, id: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val label = stringResource(R.string.chat_thinking) +
        (durationMs?.let { " · ${TimeFormat.duration(it)}" } ?: "")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PiDims.TouchTarget)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkingMark()
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_hide_thinking else R.string.chat_show_thinking,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Text(
                text = text,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(14.dp)) {
        val radius = size.minDimension * 0.11f
        val y = size.height * 0.5f
        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.18f, y))
        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.5f, y))
        drawCircle(color = color, radius = radius, center = Offset(size.width * 0.82f, y))
    }
}

/** Tool execution card: name, status, one-line arg summary, expandable output. */
@Composable
fun ToolCardRow(item: ChatItem.ToolCard, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val output = item.output
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PiDims.TouchTarget)
                    .clickable(enabled = !output.isNullOrBlank()) { expanded = !expanded }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolStatusIcon(item.status)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = toolArgSummary(item.args) ?: "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.durationMs?.let { duration ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = TimeFormat.duration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (!output.isNullOrBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.chat_hide_output else R.string.chat_show_output,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded && !output.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(if (item.truncated) R.string.chat_output_truncated else R.string.chat_output),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onCopy(output) },
                        contentAlignment = Alignment.Center,
                    ) {
                        CopyGlyph()
                    }
                }
                val vertical = rememberScrollState()
                val horizontal = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(vertical)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = output,
                        style = codeTextStyle(),
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(horizontal),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolStatus) {
    val description = stringResource(
        when (status) {
            ToolStatus.Pending -> R.string.chat_status_queued
            ToolStatus.Running -> R.string.chat_status_running
            ToolStatus.Success -> R.string.chat_status_done
            ToolStatus.Error -> R.string.chat_status_failed
        },
    )
    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ToolStatus.Running -> CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .semantics { contentDescription = description },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            ToolStatus.Success -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )

            ToolStatus.Error -> Icon(
                Icons.Default.Warning,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp),
            )

            ToolStatus.Pending -> Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
            )
        }
    }
}

/** System notices (compaction, child events, notifications) as one muted line. */
@Composable
fun NoticeRow(item: ChatItem.Notice) {
    val color = when (item.kind) {
        NoticeKind.Error -> MaterialTheme.colorScheme.error
        NoticeKind.Warning -> MaterialTheme.colorScheme.error
        NoticeKind.Success -> MaterialTheme.colorScheme.primary
        NoticeKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.kind == NoticeKind.Info) Icons.Default.Info else Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One-line argument summary for a tool card. Recognises the common coding-agent
 * keys (command/path/query/...) and otherwise shows the first JSON entries.
 */
fun toolArgSummary(args: String?): String? {
    val raw = args?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    val summary = when (element) {
        is JsonPrimitive -> element.content
        is JsonObject -> {
            val preferred = listOf(
                "command", "path", "file_path", "filePath", "query", "pattern",
                "url", "description", "prompt", "name",
            ).firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }
            preferred ?: element.entries.take(2).joinToString(", ") { (key, value) ->
                "$key=${value.toString().take(48)}"
            }
        }

        else -> raw
    }
    return summary
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(140)
        .ifBlank { null }
}
