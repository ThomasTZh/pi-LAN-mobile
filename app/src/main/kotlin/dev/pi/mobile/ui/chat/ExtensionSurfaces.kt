package dev.pi.mobile.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Extension-provided status entries (`setStatus`), for example the plan-mode or
 * working-indicator keys. Shown as a scrollable chip row under the top bar.
 */
@Composable
fun ExtensionStatusRow(entries: Map<String, String>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.toSortedMap().forEach { (_, text) ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Extension-provided widgets (`setWidget`). Plan mode publishes its todo list
 * here, so the panel starts expanded for `plan-todos` and collapsed for the
 * rest. Each widget can be toggled independently.
 */
@Composable
fun ExtensionWidgetPanel(widgets: Map<String, List<String>>, modifier: Modifier = Modifier) {
    val visible = widgets.filterValues { it.isNotEmpty() }
    if (visible.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        visible.toSortedMap().forEach { (key, lines) ->
            var expanded by rememberSaveable(key) { mutableStateOf(key == "plan-todos") }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("widget_$key"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = prettyKey(key),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (expanded) "Hide" else "${lines.size} item${if (lines.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun prettyKey(key: String): String = key
    .replace('-', ' ')
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { it.titlecase() }
    .ifEmpty { "Extension" }
