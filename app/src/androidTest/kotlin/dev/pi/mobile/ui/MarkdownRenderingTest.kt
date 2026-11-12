package dev.pi.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.markdown.MarkdownText
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders assistant markdown through [MarkdownText] (the composable MessageItems
 * uses) and checks that tables and task lists are laid out on a phone.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownRenderingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renders_a_table_with_header_row_cells_and_alignment() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                MarkdownText(
                    markdown = """
                        | Name | Score |
                        | :--- | ---: |
                        | pi | 42 |
                        | markdown | 7 |
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithTag("md_table").assertIsDisplayed()
        compose.onNodeWithTag("md_table_header").assertIsDisplayed()
        compose.onAllNodesWithTag("md_table_row").assertCountEquals(2)
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("Score").assertIsDisplayed()
        compose.onNodeWithText("pi").assertIsDisplayed()
        compose.onNodeWithText("42").assertIsDisplayed()
        compose.onNodeWithText("markdown").assertIsDisplayed()
    }

    @Test
    fun renders_task_list_checkboxes_with_nested_rows() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                MarkdownText(
                    markdown = """
                        - [x] ship ticks
                        - [ ] write parser
                          - nested detail
                    """.trimIndent(),
                )
            }
        }
        compose.onNodeWithContentDescription("Checked task").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unchecked task").assertIsDisplayed()
        compose.onAllNodesWithTag("md_task_checked").assertCountEquals(1)
        compose.onAllNodesWithTag("md_task_unchecked").assertCountEquals(1)
        compose.onNodeWithText("ship ticks").assertIsDisplayed()
        compose.onNodeWithText("write parser").assertIsDisplayed()
        compose.onNodeWithText("nested detail").assertIsDisplayed()
    }

    @Test
    fun renders_strikethrough_rules_and_nested_bullets() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                MarkdownText(
                    markdown = "~~old~~ new\n\n---\n\n- parent\n  - child",
                )
            }
        }
        compose.onNodeWithText("old new").assertIsDisplayed()
        compose.onNodeWithText("parent").assertIsDisplayed()
        compose.onNodeWithText("child").assertIsDisplayed()
    }
}
