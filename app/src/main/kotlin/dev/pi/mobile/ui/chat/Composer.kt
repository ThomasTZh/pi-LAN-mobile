package dev.pi.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.session.CommandCatalog
import dev.pi.mobile.session.PiCommand
import dev.pi.mobile.ui.components.StopGlyph
import dev.pi.mobile.ui.theme.PiDims

/**
 * Bottom composer (PLAN.md 5.5): 52dp minimum, grows to six lines, filled Send
 * when idle and Queue + Stop while streaming. Empty Send is disabled. The
 * bottom inset is IME-or-navigation-bar, whichever is larger.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    commands: List<PiCommand>,
    modifier: Modifier = Modifier,
) {
    val stopLabel = stringResource(R.string.chat_stop)
    val query = value.trim()
    val palette = remember(query, commands) {
        if (query.startsWith("/") && query.none { it == ' ' || it == '\n' }) {
            CommandCatalog.suggestions(commands, query.removePrefix("/"))
        } else {
            emptyList()
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (palette.isNotEmpty()) {
                CommandPalette(
                    commands = palette,
                    onPick = { command -> onValueChange(command.slash + " ") },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PiDims.ComposerMinHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_message_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= 20_000) onValueChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("composer_input"),
                        enabled = enabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 6,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // Prompts for a coding agent are technical text: sentence
                        // auto-capitalisation and autocorrect rewrote paths on the
                        // device (a typed path segment gets its first letter capitalised),
                        // which silently breaks the command the model runs.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Default,
                        ),
                    )
                }
            }

            if (streaming) {
                FilledTonalButton(
                    onClick = onQueue,
                    modifier = Modifier
                        .height(PiDims.TouchTarget)
                        .testTag("composer_queue"),
                    enabled = enabled && value.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.chat_queue), style = MaterialTheme.typography.labelLarge)
                }
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(PiDims.TouchTarget)
                        .testTag("composer_stop"),
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    StopGlyph(
                        modifier = Modifier.semantics {
                            contentDescription = stopLabel
                        },
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    modifier = Modifier
                        .size(PiDims.TouchTarget)
                        .testTag("composer_send"),
                    enabled = enabled && value.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            }
        }
    }
}

/** Visual separation between the message list and the composer. */
@Composable
fun ComposerDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
