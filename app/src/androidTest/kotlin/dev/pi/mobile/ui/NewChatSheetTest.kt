package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.sheets.NewChatSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** New chat: workspace folder picker, host check, and start callback. */
@RunWith(AndroidJUnit4::class)
class NewChatSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun known_folder_fills_the_field_and_start_uses_it() {
        var started: String? = null
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                NewChatSheetContent(
                    currentFolder = "/home/user/project",
                    knownFolders = listOf("/home/user/alpha", "/home/user/beta"),
                    onClose = {},
                    onCheck = { null },
                    onStart = { started = it },
                )
            }
        }
        compose.onNodeWithTag("newchat_known_1").performClick()
        compose.onNodeWithTag("newchat_folder").assertTextContains("/home/user/beta")
        compose.onNodeWithTag("newchat_start").performClick()
        compose.waitForIdle()
        assertEquals("/home/user/beta", started)
    }

    @Test
    fun relative_path_shows_validation_error_and_does_not_start() {
        var started: String? = null
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                NewChatSheetContent(
                    currentFolder = null,
                    knownFolders = emptyList(),
                    onClose = {},
                    onCheck = { null },
                    onStart = { started = it },
                )
            }
        }
        compose.onNodeWithTag("newchat_folder").performTextReplacement("project/subdir")
        compose.onNodeWithTag("newchat_start").performClick()
        compose.onNodeWithTag("newchat_error").assertIsDisplayed()
        compose.waitForIdle()
        assertNull(started)
    }

    @Test
    fun host_rejection_is_shown_and_does_not_start() {
        var started: String? = null
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                NewChatSheetContent(
                    currentFolder = "/home/user/missing",
                    knownFolders = emptyList(),
                    onClose = {},
                    onCheck = { "Folder not found on the host." },
                    onStart = { started = it },
                )
            }
        }
        compose.onNodeWithTag("newchat_start").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("newchat_error").assertIsDisplayed()
        assertNull(started)
    }

    @Test
    fun blank_folder_disables_start() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                NewChatSheetContent(
                    currentFolder = "",
                    knownFolders = emptyList(),
                    onClose = {},
                    onCheck = { null },
                    onStart = {},
                )
            }
        }
        compose.onNodeWithTag("newchat_start").assertIsNotEnabled()
    }
}
