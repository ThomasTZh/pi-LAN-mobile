package dev.pi.mobile.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.session.DialogKind
import dev.pi.mobile.session.PendingDialog
import dev.pi.mobile.ui.theme.PiDims
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Blocking extension-UI request as a modal sheet (PLAN.md 5.5/6): confirm has
 * Deny/Allow at thumb height, select has large option rows, input/editor have a
 * field and Submit, and a timeout is display-only: Pi resolves the request with
 * its default by itself, so [onTimeout] only drops the sheet locally and never
 * answers the dialog (review F8).
 */
@Composable
fun ApprovalSheetContent(
    dialog: PendingDialog,
    waitingCount: Int,
    onAnswer: (dialogId: String, confirmed: Boolean?, value: String?) -> Unit,
    onCancel: (String) -> Unit,
    onTimeout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deadline = dialog.timeoutMs?.takeIf { it > 0 && dialog.receivedAt > 0 }
        ?.let { dialog.receivedAt + it }
    var remainingMs by remember(dialog.id) {
        mutableLongStateOf(deadline?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) } ?: 0L)
    }

    LaunchedEffect(dialog.id) {
        val deadlineMs = deadline ?: return@LaunchedEffect
        while (true) {
            val left = deadlineMs - System.currentTimeMillis()
            remainingMs = left.coerceAtLeast(0)
            if (left <= 0) {
                onTimeout(dialog.id)
                break
            }
            delay(250)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("approval_sheet"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = titleFor(dialog),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            if (waitingCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "$waitingCount " + stringResource(R.string.approval_more_waiting),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("approval_waiting"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        dialog.message?.takeIf { it.isNotBlank() }?.let { message ->
            val vertical = rememberScrollState()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 300.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .verticalScroll(vertical)
                        .padding(14.dp),
                    style = if (looksLikeCommand(message)) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (deadline != null) {
            val seconds = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)
            Text(
                text = if (remainingMs > 0) stringResource(R.string.sheet_auto_dismiss, seconds) else stringResource(R.string.approval_timed_out),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("approval_countdown"),
                style = MaterialTheme.typography.bodySmall,
                color = if (seconds <= 5) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        when (dialog.kind) {
            DialogKind.Confirm -> ConfirmActions(dialog, onAnswer)
            DialogKind.Select -> SelectActions(dialog, onAnswer, onCancel)
            DialogKind.Input -> FieldActions(dialog, editor = false, onAnswer = onAnswer, onCancel = onCancel)
            DialogKind.Editor -> FieldActions(dialog, editor = true, onAnswer = onAnswer, onCancel = onCancel)
            DialogKind.Unknown -> CancelOnly(dialog, onCancel)
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ConfirmActions(
    dialog: PendingDialog,
    onAnswer: (String, Boolean?, String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = { onAnswer(dialog.id, false, null) },
            modifier = Modifier
                .weight(1f)
                .height(PiDims.ButtonHeight)
                .testTag("approval_deny"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.sheet_deny), style = MaterialTheme.typography.labelLarge)
        }
        Button(
            onClick = { onAnswer(dialog.id, true, null) },
            modifier = Modifier
                .weight(1f)
                .height(PiDims.ButtonHeight)
                .testTag("approval_allow"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.sheet_allow), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SelectActions(
    dialog: PendingDialog,
    onAnswer: (String, Boolean?, String?) -> Unit,
    onCancel: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dialog.options.forEachIndexed { index, option ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onAnswer(dialog.id, null, option) }
                    .testTag("approval_option_$index"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextButton(
            onClick = { onCancel(dialog.id) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.chat_cancel)) }
    }
}

@Composable
private fun FieldActions(
    dialog: PendingDialog,
    editor: Boolean,
    onAnswer: (String, Boolean?, String?) -> Unit,
    onCancel: (String) -> Unit,
) {
    var value by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("approval_input"),
            placeholder = { dialog.placeholder?.let { Text(it) } },
            singleLine = !editor,
            minLines = if (editor) 4 else 1,
            shape = RoundedCornerShape(12.dp),
        )
        Button(
            onClick = { onAnswer(dialog.id, null, value) },
            modifier = Modifier
                .fillMaxWidth()
                .height(PiDims.ButtonHeight)
                .testTag("approval_submit"),
            enabled = value.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.sheet_submit), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(
            onClick = { onCancel(dialog.id) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.chat_cancel)) }
    }
}

@Composable
private fun CancelOnly(dialog: PendingDialog, onCancel: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        OutlinedButton(
            onClick = { onCancel(dialog.id) },
            modifier = Modifier
                .fillMaxWidth()
                .height(PiDims.ButtonHeight),
        ) { Text(stringResource(R.string.sheet_dismiss)) }
    }
}

@Composable
private fun titleFor(dialog: PendingDialog): String =
    dialog.title?.takeIf { it.isNotBlank() } ?: stringResource(
        when (dialog.kind) {
            DialogKind.Confirm -> R.string.approval_title_confirm
            DialogKind.Select -> R.string.approval_title_select
            DialogKind.Input -> R.string.approval_title_input
            DialogKind.Editor -> R.string.approval_title_editor
            DialogKind.Unknown -> R.string.approval_title_unknown
        },
    )

/**
 * Heuristic for monospace approval text: true when the message looks like a
 * shell command, patch or multiline tool payload rather than prose.
 */
fun looksLikeCommand(message: String): Boolean {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.contains('\n')) return true
    if (trimmed.startsWith("$ ") || trimmed.startsWith("> ")) return true
    if (trimmed.startsWith("./")) return true
    val firstWord = trimmed.substringBefore(' ').lowercase()
    val commandStarts = setOf(
        "sudo", "git", "npm", "pnpm", "yarn", "rm", "mv", "cp", "mkdir", "chmod", "chown",
        "cd", "ls", "cat", "curl", "wget", "node", "python", "python3", "pip", "docker",
        "systemctl", "apt", "dnf", "make", "gradle", "ssh", "scp", "sed", "awk", "grep",
    )
    if (firstWord in commandStarts) return true
    return trimmed.contains(" && ") || trimmed.contains(" -") && trimmed.contains("/")
}
