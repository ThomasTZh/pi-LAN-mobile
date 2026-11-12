package dev.pi.mobile.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.core.TimeFormat
import dev.pi.mobile.session.SessionGroup
import dev.pi.mobile.session.SessionRow
import dev.pi.mobile.session.SessionsState
import dev.pi.mobile.ui.components.PiDivider
import dev.pi.mobile.ui.components.PiEmptyLine
import dev.pi.mobile.ui.components.PiErrorLine
import dev.pi.mobile.ui.theme.PiDims

/**
 * Full-height sessions sheet (PLAN.md 5.5): New chat row, groups by project,
 * 64dp rows with relative time and count, current mark, refresh, and a per-row
 * Open/Rename/Delete menu. Opening a session while streaming asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsSheetContent(
    state: SessionsState,
    currentPath: String?,
    streaming: Boolean,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SessionRow) -> Unit,
    onRename: (SessionRow, String) -> Unit,
    onDelete: (SessionRow) -> Unit,
    onClose: (() -> Unit)? = null,
    fillHeight: Float = 0.94f,
) {
    var pendingOpen by remember { mutableStateOf<SessionRow?>(null) }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionRow?>(null) }

    fun requestOpen(row: SessionRow) {
        if (streaming) pendingOpen = row else onOpen(row)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(fillHeight)
            .testTag("sessions_sheet"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sheet_sessions),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(PiDims.TouchTarget),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.sheet_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(PiDims.TouchTarget),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.sheet_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PiDims.SessionRowHeight)
                .clickable { onNewChat() }
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("sessions_new_chat"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.sheet_new_chat), style = MaterialTheme.typography.bodyLarge)
        }

        PiDivider()

        if (state.loading && state.rows.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        // Pull to refresh in addition to the icon (PLAN.md 5.5, review F5).
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("sessions_pull_refresh"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PiErrorLine(error)
                    }
                }

                if (!state.loading && state.rows.isEmpty() && state.error == null) {
                    PiEmptyLine("No sessions on the desktop yet.", Modifier.testTag("sessions_empty"))
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    state.groups.forEach { group ->
                        item(key = "group-${group.cwd ?: group.project}") {
                            GroupHeader(group)
                        }
                        items(group.rows, key = { it.path }) { row ->
                            SessionRowItem(
                                row = row,
                                isCurrent = row.path == currentPath,
                                onOpen = { requestOpen(row) },
                                onRename = { renameTarget = row },
                                onDelete = { deleteTarget = row },
                            )
                        }
                    }
                    item(key = "sessions-bottom-space") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    pendingOpen?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingOpen = null },
            title = { Text(stringResource(R.string.sheet_switch)) },
            text = { Text("Pi is streaming. Opening “${row.displayName}” interrupts the current run.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOpen = null
                        onOpen(row)
                    },
                ) { Text(stringResource(R.string.sheet_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingOpen = null }) { Text(stringResource(R.string.sheet_stay)) }
            },
        )
    }

    renameTarget?.let { row ->
        var name by remember(row.path) { mutableStateOf(row.name ?: row.displayName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.sheet_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sheet_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next = name.trim()
                        renameTarget = null
                        if (next.isNotEmpty()) onRename(row, next)
                    },
                ) { Text(stringResource(R.string.sheet_rename_action)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.sheet_delete)) },
            text = { Text("“${row.displayName}” is removed from the desktop. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDelete(row)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.sheet_delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
private fun GroupHeader(group: SessionGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.project,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = group.rows.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dim = if (row.cwdExists) 1f else 0.55f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PiDims.SessionRowHeight)
            .clickable { onOpen() }
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = buildString {
                    append(TimeFormat.relative(row.mtimeMs))
                    append(" · ")
                    append(row.messageCount)
                    append(" messages")
                    if (!row.cwdExists) append(" · missing folder")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(PiDims.TouchTarget),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Session actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_open)) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sheet_delete_action), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
