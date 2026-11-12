package dev.pi.mobile.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pi.mobile.ui.chat.ChatActions
import dev.pi.mobile.ui.chat.ChatScreen
import dev.pi.mobile.ui.components.PiGlyph
import dev.pi.mobile.ui.connect.ConnectScreen
import dev.pi.mobile.ui.sheets.ApprovalSheetContent
import dev.pi.mobile.ui.sheets.ModelSheetContent
import dev.pi.mobile.ui.sheets.SessionsSheetContent
import dev.pi.mobile.ui.sheets.SettingsSheetContent
import dev.pi.mobile.ui.sheets.StatsSheetContent
import dev.pi.mobile.ui.sheets.ThinkingSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Saves PNGs of every screen into the app's external files directory
 * (`/sdcard/Android/data/dev.pi.mobile/files/screenshots`) so the device run can
 * pull them into `pi-app/docs/` for the G6 evidence. Each test also asserts the
 * capture is non-empty, so a blank render fails instead of producing a black file.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String, tag: String? = null) {
        compose.waitForIdle()
        val image = if (tag == null) {
            compose.onRoot().captureToImage()
        } else {
            compose.onNodeWithTag(tag).captureToImage()
        }
        val bitmap = image.asAndroidBitmap()
        val dir = context.getExternalFilesDir("screenshots") ?: context.filesDir
        dir.mkdirs()
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name ${bitmap.width}x${bitmap.height} -> ${file.absolutePath}")
        assertTrue("screenshot $name is empty", file.length() > 1_000)
    }

    @Test
    fun connect_screen() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ConnectScreen(
                    initialAddress = "192.168.1.50:7690",
                    remember = true,
                    connecting = false,
                    error = null,
                    onRememberChange = {},
                    onConnect = { _, _ -> },
                )
            }
        }
        capture("01-connect")
    }

    @Test
    fun connect_error_state() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ConnectScreen(
                    initialAddress = "192.168.1.50:7690",
                    remember = true,
                    connecting = false,
                    error = "That PIN was not accepted.",
                    onRememberChange = {},
                    onConnect = { _, _ -> },
                )
            }
        }
        capture("02-connect-error")
    }

    @Test
    fun chat_rich_state() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ChatScreen(state = UiFixtures.richState(), actions = ChatActions())
            }
        }
        capture("03-chat")
    }

    @Test
    fun chat_streaming() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ChatScreen(state = UiFixtures.streamingState(), actions = ChatActions())
            }
        }
        capture("04-chat-streaming")
    }

    @Test
    fun chat_empty() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ChatScreen(state = UiFixtures.emptyState(), actions = ChatActions())
            }
        }
        capture("05-chat-empty")
    }

    @Test
    fun chat_light_theme() {
        compose.setContent {
            PiTheme(theme = "light", textScalePercent = 100) {
                ChatScreen(state = UiFixtures.markdownState(), actions = ChatActions())
            }
        }
        capture("06-chat-light")
    }

    @Test
    fun chat_360dp() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .testTag("small_phone_root"),
                    ) {
                        ChatScreen(state = UiFixtures.richState(), actions = ChatActions())
                    }
                }
            }
        }
        capture("07-chat-360dp")
    }

    @Test
    fun chat_large_font() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                PiTheme(theme = "dark", textScalePercent = 130) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .testTag("small_phone_root"),
                        ) {
                            ChatScreen(state = UiFixtures.markdownState(), actions = ChatActions())
                        }
                    }
                }
            }
        }
        capture("08-chat-360dp-130pct")
    }

    @Test
    fun approval_confirm_sheet() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    ApprovalSheetContent(
                        dialog = UiFixtures.confirmDialog(),
                        waitingCount = 1,
                        onAnswer = { _, _, _ -> },
                        onCancel = {},
                        onTimeout = {},
                    )
                }
            }
        }
        capture("09-approval-confirm")
    }

    @Test
    fun approval_select_sheet() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    ApprovalSheetContent(
                        dialog = UiFixtures.selectDialog(),
                        waitingCount = 0,
                        onAnswer = { _, _, _ -> },
                        onCancel = {},
                        onTimeout = {},
                    )
                }
            }
        }
        capture("10-approval-select")
    }

    @Test
    fun sessions_sheet() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    SessionsSheetContent(
                        state = UiFixtures.sessionsState(),
                        currentPath = "/sessions/one.jsonl",
                        streaming = false,
                        onNewChat = {},
                        onRefresh = {},
                        onOpen = {},
                        onRename = { _, _ -> },
                        onDelete = {},
                        onClose = {},
                    )
                }
            }
        }
        capture("11-sessions")
    }

    @Test
    fun settings_sheet() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    SettingsSheetContent(
                        settings = dev.pi.mobile.data.AppSettings(alertsEnabled = true),
                        notificationsAllowed = false,
                        onAlertsChange = {},
                        onRequestNotifications = {},
                        onHideDetailsChange = {},
                        onThemeChange = {},
                        onTextScaleChange = {},
                        onKeepAwakeChange = {},
                        onHapticsChange = {},
                        batteryOptimised = true,
                        onBatteryOptimisation = {},
                        onForget = {},
                        onClose = {},
                    )
                }
            }
        }
        capture("12-settings")
    }

    @Test
    fun model_and_thinking_sheets() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    Column {
                        ModelSheetContent(
                            state = UiFixtures.baseState(),
                            onSelect = {},
                            onClose = {},
                        )
                        ThinkingSheetContent(
                            state = UiFixtures.baseState(),
                            onSelect = {},
                            onClose = {},
                        )
                    }
                }
            }
        }
        capture("13-model-thinking")
    }

    @Test
    fun stats_sheet() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SheetPreview {
                    StatsSheetContent(
                        stats = UiFixtures.stats(),
                        title = "Pi Mobile plan",
                        onClose = {},
                    )
                }
            }
        }
        capture("14-stats")
    }

    @Test
    fun wide_two_pane_layout() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density / 2f, base.fontScale)) {
                PiTheme(theme = "dark", textScalePercent = 100) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(700.dp)
                                .fillMaxHeight()
                                .testTag("wide_root"),
                        ) {
                            WideChatLayout(
                                state = UiFixtures.markdownState(),
                                sessions = UiFixtures.sessionsState(),
                                actions = ChatActions(),
                                approvalWaiting = false,
                                waitingCount = 0,
                                onOpenApproval = {},
                                commands = emptyList(),
                                onNewChat = {},
                                onRefreshSessions = {},
                                onOpenSession = {},
                                onRenameSession = { _, _ -> },
                                onDeleteSession = {},
                            )
                        }
                    }
                }
            }
        }
        capture("16-wide-two-pane", tag = "wide_root")
    }

    @Test
    fun app_mark() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    PiGlyph(size = 120.dp)
                }
            }
        }
        capture("15-app-mark")
    }

    /**
     * Draws sheet content over a dimmed background with a rounded top, so the
     * saved PNG reads like the real ModalBottomSheet without the animation.
     */
    @Composable
    private fun SheetPreview(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)),
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .align(Alignment.CenterHorizontally)
                            .width(34.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    content()
                }
            }
        }
    }

    private companion object {
        const val TAG = "PiScreenshots"
    }
}
