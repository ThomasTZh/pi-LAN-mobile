package dev.pi.mobile.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.core.TimeFormat
import dev.pi.mobile.proto.ModelInfo
import dev.pi.mobile.proto.SessionStats
import dev.pi.mobile.session.ChatState
import dev.pi.mobile.ui.components.PiChoiceChips
import dev.pi.mobile.ui.components.PiDivider
import dev.pi.mobile.ui.components.PiEmptyLine
import dev.pi.mobile.ui.components.PiSectionLabel
import dev.pi.mobile.ui.theme.PiDims
import java.util.Locale

/** Model picker: grouped by provider with a checkmark on the current model. */
@Composable
fun ModelSheetContent(
    state: ChatState,
    onSelect: (ModelInfo) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("model_sheet")) {
        SheetHeader(title = stringResource(R.string.sheet_model), subtitle = state.model?.label, onClose = onClose)
        if (state.availableModels.isEmpty()) {
            PiEmptyLine("Pi reported no models.")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val groups = state.availableModels.groupBy { it.provider?.takeIf { p -> p.isNotBlank() } ?: "Models" }
                groups.forEach { (provider, models) ->
                    PiSectionLabel(provider)
                    models.forEach { model ->
                        val selected = state.model?.provider == model.provider && state.model?.id == model.id
                        ModelRow(model = model, selected = selected, onClick = { onSelect(model) })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PiDims.TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.qualifiedId != model.label) {
                Text(
                    text = model.qualifiedId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Current model",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Thinking-level picker limited to the levels Pi reports. */
@Composable
fun ThinkingSheetContent(
    state: ChatState,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("thinking_sheet")) {
        SheetHeader(
            title = stringResource(R.string.sheet_thinking),
            subtitle = state.thinkingLevel?.replaceFirstChar { it.uppercase() },
            onClose = onClose,
        )
        if (state.availableThinkingLevels.isEmpty()) {
            PiEmptyLine("Pi reported no thinking levels.")
        } else {
            PiChoiceChips(
                options = state.availableThinkingLevels.map { level ->
                    level to level.replaceFirstChar { it.uppercase() }
                },
                selected = state.thinkingLevel ?: "",
                onSelect = onSelect,
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** Session stats from `get_session_stats`. */
@Composable
fun StatsSheetContent(
    stats: SessionStats?,
    title: String,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("stats_sheet"),
    ) {
        SheetHeader(title = stringResource(R.string.sheet_stats), subtitle = title.takeIf { it.isNotBlank() }, onClose = onClose)
        if (stats == null) {
            PiEmptyLine("No stats reported yet.")
        } else {
            StatRow("Messages", stats.messages?.toString() ?: "—")
            StatRow("Input tokens", count(stats.inputTokens))
            StatRow("Output tokens", count(stats.outputTokens))
            StatRow("Total tokens", count(stats.totalTokens))
            StatRow("Cost", stats.costUsd?.let { String.format(Locale.US, "$%.4f", it) } ?: "—")
            StatRow("Duration", stats.durationMs?.let { TimeFormat.duration(it) } ?: "—")
            StatRow("Context", contextLabel(stats))
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun count(value: Int?): String =
    value?.let { String.format(Locale.US, "%,d", it) } ?: "—"

private fun contextLabel(stats: SessionStats): String {
    val percent = stats.contextPercent
    val window = stats.contextWindow
    if (percent != null && window != null) {
        return "$percent% of ${compact(window)}"
    }
    if (percent != null) return "$percent%"
    return count(stats.contextTokens)
}

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%dk", value / 1_000)
    else -> value.toString()
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
    PiDivider(Modifier.padding(horizontal = 20.dp))
}

/** Shared header with a title, optional one-line subtitle and a close target. */
@Composable
internal fun SheetHeader(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(PiDims.TouchTarget)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.sheet_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
