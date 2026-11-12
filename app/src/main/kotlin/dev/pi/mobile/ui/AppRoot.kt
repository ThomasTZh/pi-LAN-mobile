package dev.pi.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pi.mobile.data.AppSettings
import dev.pi.mobile.session.ChatController
import dev.pi.mobile.session.ChatState
import dev.pi.mobile.session.ConnectionPhase
import dev.pi.mobile.session.SessionsState
import dev.pi.mobile.ui.chat.ChatActions
import dev.pi.mobile.ui.chat.ChatScreen
import dev.pi.mobile.ui.components.LocalPiHaptics
import dev.pi.mobile.ui.components.openLink
import dev.pi.mobile.ui.connect.ConnectScreen
import dev.pi.mobile.ui.connect.displayAddress
import dev.pi.mobile.ui.sheets.ApprovalSheetContent
import dev.pi.mobile.ui.sheets.ModelSheetContent
import dev.pi.mobile.ui.sheets.SessionsSheetContent
import dev.pi.mobile.ui.sheets.SettingsSheetContent
import dev.pi.mobile.ui.sheets.StatsSheetContent
import dev.pi.mobile.ui.sheets.ThinkingSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import dev.pi.mobile.ui.theme.findActivity

private enum class Sheet { Sessions, Model, Thinking, Stats, Settings }

/**
 * Root of the Compose tree. Owns screen selection, sheet visibility, the
 * approval sheet hand-off, theme/text-scale, insets and device effects; every
 * screen below it is a pure function of [ChatState] and explicit callbacks so
 * UI tests can drive fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(controller: ChatController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by controller.settingsState.collectAsStateWithLifecycle()
    val profiles by controller.profileState.collectAsStateWithLifecycle()
    val sessions by controller.sessionsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val (notificationsAllowed, requestNotifications) = rememberNotificationPermission()
    var sheetName by rememberSaveable { mutableStateOf("") }
    var approvalOpen by remember { mutableStateOf(false) }
    var dismissedApprovalId by remember { mutableStateOf<String?>(null) }

    PiTheme(theme = settings.theme, textScalePercent = settings.textScale) {
        CompositionLocalProvider(
            LocalPiHaptics provides {
                if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        ) {
            KeepAwake(settings.keepAwake)

            LaunchedEffect(profiles.selectedProfileId, state.phase) {
                if (state.phase == ConnectionPhase.Idle) {
                    val selected = profiles.selected()
                    if (selected != null && selected.pinSaved) controller.autoConnect()
                }
            }

            LaunchedEffect(state.activeDialog?.id) {
                val dialogId = state.activeDialog?.id
                if (dialogId == null) {
                    approvalOpen = false
                    dismissedApprovalId = null
                } else if (dialogId != dismissedApprovalId) {
                    sheetName = ""
                    approvalOpen = true
                }
            }

            LaunchedEffect(sheetName) {
                if (sheetName == Sheet.Sessions.name) controller.refreshSessions()
            }

            // Review F7/F17: the rule lives on ChatState so a JVM test can pin
            // it (a reconnect keeps the chat screen because it has a transcript).
            val showConnect = state.showsConnectCard

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    if (showConnect) {
                        ConnectScreen(
                            initialAddress = displayAddress(
                                profiles.selected()?.baseUrl
                                    ?: profiles.profiles.maxByOrNull { it.lastUsedAt }?.baseUrl,
                            ).orEmpty(),
                            remember = settings.remember,
                            connecting = state.phase == ConnectionPhase.Connecting,
                            error = state.lastError,
                            onRememberChange = { enabled ->
                                controller.updateSettings { it.copy(remember = enabled) }
                            },
                            onConnect = { address, pin -> controller.connect(address, pin) },
                        )
                    } else {
                        val approvalBanner = state.activeDialog != null && !approvalOpen
                        val actions = chatActions(
                            controller = controller,
                            wide = wide,
                            openSheet = { sheetName = it.name },
                            onOpenLink = { url -> openLink(context, url) },
                        )
                        val openApproval = {
                            dismissedApprovalId = null
                            approvalOpen = true
                        }
                        if (wide) {
                            WideChatLayout(
                                state = state,
                                sessions = sessions,
                                actions = actions,
                                approvalWaiting = approvalBanner,
                                waitingCount = state.waitingDialogCount,
                                onOpenApproval = openApproval,
                                onNewChat = { controller.newChat() },
                                onRefreshSessions = { controller.refreshSessions() },
                                onOpenSession = { row -> controller.openSession(row) },
                                onRenameSession = { row, name -> controller.renameSession(row, name) },
                                onDeleteSession = { row -> controller.deleteSession(row) },
                            )
                        } else {
                            ChatScreen(
                                state = state,
                                actions = actions,
                                modifier = Modifier.fillMaxSize(),
                                approvalWaiting = approvalBanner,
                                waitingCount = state.waitingDialogCount,
                                onOpenApproval = openApproval,
                            )
                        }
                    }
                }
            }

            PiSheetHost(
                sheet = sheetName,
                onDismiss = { sheetName = "" },
                sessions = sessions,
                state = state,
                settings = settings,
                notificationsAllowed = notificationsAllowed,
                onRequestNotifications = requestNotifications,
                controller = controller,
                onCloseSheet = { sheetName = "" },
            )

            val dialog = state.activeDialog
            if (dialog != null && approvalOpen) {
                ModalBottomSheet(
                    onDismissRequest = {
                        approvalOpen = false
                        dismissedApprovalId = dialog.id
                    },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ApprovalSheetContent(
                        dialog = dialog,
                        waitingCount = state.waitingDialogCount,
                        onAnswer = { id, confirmed, value ->
                            controller.answerDialog(id, confirmed, value)
                            approvalOpen = false
                        },
                        onCancel = { id ->
                            controller.cancelDialog(id)
                            approvalOpen = false
                        },
                        // Pi auto-resolves a timed-out dialog with its default;
                        // the countdown is display-only (review F8).
                        onTimeout = { id ->
                            controller.dialogTimedOut(id)
                            approvalOpen = false
                            dismissedApprovalId = id
                        },
                    )
                }
            }
        }
    }
}

private fun chatActions(
    controller: ChatController,
    wide: Boolean,
    openSheet: (Sheet) -> Unit,
    onOpenLink: (String) -> Unit,
): ChatActions = ChatActions(
    onSessions = { if (!wide) openSheet(Sheet.Sessions) },
    onModel = { openSheet(Sheet.Model) },
    onThinking = { openSheet(Sheet.Thinking) },
    onStats = { openSheet(Sheet.Stats) },
    onSettings = { openSheet(Sheet.Settings) },
    onDisconnect = { controller.disconnect() },
    onForget = { controller.forgetServer() },
    onSend = { text -> controller.send(text) },
    onQueue = { text -> controller.followUp(text) },
    onStop = { controller.abort() },
    onRetry = { controller.retry() },
    onOpenLink = onOpenLink,
)

/**
 * >= 600dp layout from PLAN.md 5.5: sessions stay as a fixed left rail and the
 * chat occupies the rest. Extracted so the adaptive rule is testable without a
 * tablet (the phone cannot produce 600dp).
 */
@Composable
internal fun WideChatLayout(
    state: ChatState,
    sessions: SessionsState,
    actions: ChatActions,
    approvalWaiting: Boolean,
    waitingCount: Int,
    onOpenApproval: () -> Unit,
    onNewChat: () -> Unit,
    onRefreshSessions: () -> Unit,
    onOpenSession: (dev.pi.mobile.session.SessionRow) -> Unit,
    onRenameSession: (dev.pi.mobile.session.SessionRow, String) -> Unit,
    onDeleteSession: (dev.pi.mobile.session.SessionRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                SessionsSheetContent(
                    state = sessions,
                    currentPath = state.sessionPath,
                    streaming = state.streaming,
                    onNewChat = onNewChat,
                    onRefresh = onRefreshSessions,
                    onOpen = onOpenSession,
                    onRename = onRenameSession,
                    onDelete = onDeleteSession,
                    onClose = null,
                    fillHeight = 1f,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ChatScreen(
            state = state,
            actions = actions,
            modifier = Modifier.weight(1f),
            approvalWaiting = approvalWaiting,
            waitingCount = waitingCount,
            onOpenApproval = onOpenApproval,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PiSheetHost(
    sheet: String,
    onDismiss: () -> Unit,
    sessions: SessionsState,
    state: ChatState,
    settings: AppSettings,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    controller: ChatController,
    onCloseSheet: () -> Unit,
) {
    if (sheet.isBlank()) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (sheet) {
            Sheet.Sessions.name -> SessionsSheetContent(
                state = sessions,
                currentPath = state.sessionPath,
                streaming = state.streaming,
                onNewChat = {
                    onCloseSheet()
                    controller.newChat()
                },
                onRefresh = { controller.refreshSessions() },
                onOpen = { row ->
                    onCloseSheet()
                    controller.openSession(row)
                },
                onRename = { row, name -> controller.renameSession(row, name) },
                onDelete = { row -> controller.deleteSession(row) },
                onClose = onCloseSheet,
            )

            Sheet.Model.name -> ModelSheetContent(
                state = state,
                onSelect = { model ->
                    onCloseSheet()
                    controller.setModel(model.provider.orEmpty(), model.id)
                },
                onClose = onCloseSheet,
            )

            Sheet.Thinking.name -> ThinkingSheetContent(
                state = state,
                onSelect = { level ->
                    onCloseSheet()
                    controller.setThinking(level)
                },
                onClose = onCloseSheet,
            )

            Sheet.Stats.name -> StatsSheetContent(
                stats = state.stats,
                title = state.title,
                onClose = onCloseSheet,
            )

            Sheet.Settings.name -> {
                val settingsContext = LocalContext.current
                SettingsSheetContent(
                    settings = settings,
                    notificationsAllowed = notificationsAllowed,
                    onAlertsChange = { enabled ->
                        controller.setAlerts(enabled)
                        if (enabled && !notificationsAllowed) onRequestNotifications()
                    },
                    onRequestNotifications = onRequestNotifications,
                    onHideDetailsChange = { hidden -> controller.setHideDetails(hidden) },
                    onThemeChange = { theme -> controller.setTheme(theme) },
                    onTextScaleChange = { percent -> controller.setTextScale(percent) },
                    onKeepAwakeChange = { enabled -> controller.setKeepAwake(enabled) },
                    onHapticsChange = { enabled -> controller.setHaptics(enabled) },
                    batteryOptimised = rememberBatteryOptimisationState(settingsContext),
                    onBatteryOptimisation = { openBatteryOptimisationSettings(settingsContext) },
                    onForget = {
                        onCloseSheet()
                        controller.forgetServer()
                    },
                    onClose = onCloseSheet,
                )
            }
        }
    }
}

/** Keeps the screen on while the Settings preference is enabled. */
@Composable
private fun KeepAwake(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** Notification permission state, refreshed whenever the app resumes. */
@Composable
private fun rememberNotificationPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    fun granted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    var allowed by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = granted()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = granted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val request = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return allowed to request
}

/** True while Android may still restrict Pi's foreground service (PLAN.md 5.6). */
@Composable
private fun rememberBatteryOptimisationState(context: Context): Boolean {
    fun optimised(): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return !manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    var value by remember { mutableStateOf(optimised()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = optimised()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return value
}

/**
 * Opens the system screen that exempts the app from battery restrictions.
 * Falls back to the generic optimisation list, then to the app's own settings
 * page, on builds that do not ship the direct per-app dialog.
 */
@SuppressLint("BatteryLife") // PLAN 5.6 asks for exactly this shortcut; no Play release is planned.
private fun openBatteryOptimisationSettings(context: Context) {
    val packageUri = "package:${context.packageName}".toUri()
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Try the next, less direct screen.
        }
    }
}
