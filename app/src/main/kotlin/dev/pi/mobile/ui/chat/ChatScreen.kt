package dev.pi.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.session.ChatItem
import dev.pi.mobile.session.ChatState
import dev.pi.mobile.session.ConnectionPhase
import dev.pi.mobile.ui.components.LocalPiHaptics
import dev.pi.mobile.ui.components.PiGlyph
import dev.pi.mobile.ui.components.PiLiveDot
import dev.pi.mobile.ui.components.copyToClipboard
import dev.pi.mobile.ui.components.openLink
import dev.pi.mobile.ui.theme.PiDims
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Intents the chat screen can raise; AppRoot wires them to the controller. */
data class ChatActions(
    val onSessions: () -> Unit = {},
    val onModel: () -> Unit = {},
    val onThinking: () -> Unit = {},
    val onStats: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onSend: (String) -> Unit = {},
    val onQueue: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
)

/**
 * Chat screen: 56dp bar, scrollable message list with auto-follow only at the
 * bottom, a Jump-to-latest pill, banners, and the composer. Screens with an
 * empty list show connecting/loading/error/hero states instead.
 */
@Composable
fun ChatScreen(
    state: ChatState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    approvalWaiting: Boolean = false,
    waitingCount: Int = 0,
    onOpenApproval: () -> Unit = {},
) {
    val haptics = LocalPiHaptics.current
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    val tailLength = state.items.lastOrNull()?.let(::itemLength) ?: 0
    var slowModelHint by remember { mutableStateOf(false) }

    // PLAN.md 6: after 20 s without new tokens while streaming, say the desktop
    // may still be loading the model. Any token resets the timer.
    LaunchedEffect(state.streaming, state.sequence, tailLength) {
        slowModelHint = false
        if (state.streaming) {
            delay(20_000)
            slowModelHint = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatTopBar(state = state, actions = actions, onForget = { confirmForget = true })

        ChatBanners(
            state = state,
            approvalWaiting = approvalWaiting,
            waitingCount = waitingCount,
            slowModelHint = slowModelHint,
            onOpenApproval = onOpenApproval,
            onRetry = actions.onRetry,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.items.isEmpty()) {
                EmptyState(state = state, onRetry = actions.onRetry)
            } else {
                MessageList(
                    state = state,
                    onOpenLink = actions.onOpenLink,
                )
            }
        }

        ComposerDivider()
        Composer(
            value = draft,
            onValueChange = { draft = it },
            streaming = state.streaming,
            enabled = state.phase == ConnectionPhase.Connected,
            onSend = {
                val message = draft.trim()
                if (message.isNotEmpty()) {
                    haptics()
                    actions.onSend(message)
                    draft = ""
                }
            },
            onQueue = {
                val message = draft.trim()
                if (message.isNotEmpty()) {
                    haptics()
                    actions.onQueue(message)
                    draft = ""
                }
            },
            onStop = {
                haptics()
                actions.onStop()
            },
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.chat_forget_title)) },
            text = { Text(stringResource(R.string.chat_forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        actions.onForget()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.chat_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }

}

@Composable
private fun ChatTopBar(state: ChatState, actions: ChatActions, onForget: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PiDims.BarHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = actions.onSessions,
                modifier = Modifier
                    .size(PiDims.TouchTarget)
                    .testTag("chat_sessions"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.chat_sessions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = state.title,
                    modifier = Modifier.testTag("chat_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitleFor(state),
                        modifier = Modifier.testTag("chat_subtitle"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.streaming) {
                        Spacer(Modifier.width(6.dp))
                        PiLiveDot()
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier
                        .size(PiDims.TouchTarget)
                        .testTag("chat_menu"),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem(stringResource(R.string.chat_model)) { menuOpen = false; actions.onModel() }
                    MenuItem(stringResource(R.string.chat_thinking)) { menuOpen = false; actions.onThinking() }
                    MenuItem(stringResource(R.string.chat_stats)) { menuOpen = false; actions.onStats() }
                    MenuItem(stringResource(R.string.chat_settings)) { menuOpen = false; actions.onSettings() }
                    HorizontalDivider()
                    MenuItem(stringResource(R.string.chat_disconnect)) { menuOpen = false; actions.onDisconnect() }
                    MenuItem(stringResource(R.string.chat_forget), destructive = true) {
                        menuOpen = false
                        onForget()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = PiDims.TouchTarget),
    )
}

@Composable
private fun subtitleFor(state: ChatState): String = when (state.phase) {
    ConnectionPhase.Connecting -> stringResource(R.string.chat_connecting)
    ConnectionPhase.Reconnecting -> state.phaseDetail ?: stringResource(R.string.chat_reconnecting)
    ConnectionPhase.Disconnected -> stringResource(R.string.chat_disconnected)
    else -> state.subtitle.ifBlank { "Pi" }
}

@Composable
private fun ChatBanners(
    state: ChatState,
    approvalWaiting: Boolean,
    waitingCount: Int,
    slowModelHint: Boolean,
    onOpenApproval: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (approvalWaiting) {
            val label = if (waitingCount > 0) {
                stringResource(R.string.sheet_approval_waiting) + " · $waitingCount"
            } else {
                stringResource(R.string.sheet_approval_waiting)
            }
            Banner(
                text = label,
                tone = BannerTone.Action,
                actionLabel = stringResource(R.string.sheet_review),
                onAction = onOpenApproval,
                onClick = onOpenApproval,
                tag = "approval_banner",
            )
        }
        val banner = state.banner
        if (banner != null) {
            Banner(
                text = banner,
                tone = if (state.phase == ConnectionPhase.Reconnecting) BannerTone.Accent else BannerTone.Warning,
                actionLabel = stringResource(R.string.chat_retry).takeIf { state.phase == ConnectionPhase.Failed || banner.contains("stopped") },
                onAction = onRetry,
                tag = "chat_banner",
            )
        } else if (state.items.isNotEmpty() && state.lastError != null) {
            Banner(
                text = state.lastError,
                tone = BannerTone.Error,
                actionLabel = stringResource(R.string.chat_retry),
                onAction = onRetry,
                tag = "chat_error_banner",
            )
        }
        if (slowModelHint && state.streaming) {
            Banner(
                text = stringResource(R.string.chat_slow_model),
                tone = BannerTone.Accent,
                tag = "slow_model_hint",
            )
        }
    }
}

internal enum class BannerTone { Accent, Action, Warning, Error }

@Composable
internal fun Banner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
) {
    val container = when (tone) {
        BannerTone.Accent -> MaterialTheme.colorScheme.primaryContainer
        BannerTone.Action -> MaterialTheme.colorScheme.surfaceContainerHigh
        BannerTone.Warning -> MaterialTheme.colorScheme.surfaceContainerHigh
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        BannerTone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
        BannerTone.Action -> MaterialTheme.colorScheme.onSurface
        BannerTone.Warning -> MaterialTheme.colorScheme.onSurface
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val iconTint = when (tone) {
        BannerTone.Warning, BannerTone.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PiDims.TouchTarget)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tone == BannerTone.Action) {
                PiLiveDot(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(
                    imageVector = if (tone == BannerTone.Accent) Icons.Default.Info else Icons.Default.Warning,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: ChatState, onRetry: () -> Unit) {
    val loading = state.phase == ConnectionPhase.Connecting || state.phase == ConnectionPhase.Reconnecting
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(if (loading || state.lastError != null) "chat_state" else "empty_hero"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.lastError != null && !loading -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = state.lastError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Button(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
                }

                loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = stringResource(if (state.sessionPath != null) R.string.chat_loading_session else R.string.chat_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    PiGlyph(size = 54.dp)
                    Text(
                        text = stringResource(R.string.chat_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.chat_hero_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun itemLength(item: ChatItem): Int = when (item) {
    is ChatItem.UserMessage -> item.text.length
    is ChatItem.AssistantMessage -> item.blocks.sumOf { block ->
        when (block) {
            is dev.pi.mobile.proto.ContentBlock.Text -> block.text.length
            is dev.pi.mobile.proto.ContentBlock.Thinking -> block.text.length
            else -> 0
        }
    }

    is ChatItem.ToolCard -> (item.output?.length ?: 0) + (item.args?.length ?: 0)
    is ChatItem.Notice -> item.text.length
}

/**
 * LazyColumn keyed by item id. Auto-follow is enabled only while the list is at
 * the bottom; a user scroll up stops it until the user returns or taps the pill.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageList(state: ChatState, onOpenLink: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalPiHaptics.current
    val listState = rememberLazyListState()
    val endRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // A drag hands control to the reader; returning to the bottom re-enables follow.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            if (!canScrollForward) follow = true
        }
    }

    val listTailLength = state.items.lastOrNull()?.let(::itemLength) ?: 0
    LaunchedEffect(state.sequence, state.items.size, listTailLength) {
        if (follow && state.items.isNotEmpty()) {
            withFrameNanos { }
            runCatching { endRequester.bringIntoView() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("chat_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.UserMessage -> UserMessageRow(item) { text ->
                        haptics()
                        copyToClipboard(context, text)
                    }

                    is ChatItem.AssistantMessage -> AssistantMessageRow(
                        item = item,
                        onCopy = { text ->
                            haptics()
                            copyToClipboard(context, text)
                        },
                        onOpenLink = onOpenLink,
                    )

                    is ChatItem.ToolCard -> ToolCardRow(item) { text ->
                        haptics()
                        copyToClipboard(context, text, "Pi output")
                    }

                    is ChatItem.Notice -> NoticeRow(item)
                }
            }
            item(key = "pi-end-marker") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .bringIntoViewRequester(endRequester),
                )
            }
        }

        if (!atBottom && state.items.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .heightIn(min = PiDims.TouchTarget)
                    .testTag("jump_to_latest")
                    .clickable {
                        follow = true
                        scope.launch {
                            if (state.items.isNotEmpty()) {
                                listState.animateScrollToItem(state.items.lastIndex)
                            }
                            runCatching { endRequester.bringIntoView() }
                        }
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_jump_latest),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
