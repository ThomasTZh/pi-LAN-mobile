package dev.pi.mobile.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.markdown.Markdown
import dev.pi.mobile.ui.markdown.MdAlignment
import dev.pi.mobile.ui.markdown.MdBlock
import dev.pi.mobile.ui.markdown.MdSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The markdown subset parser, tested directly (no Compose runtime needed). */
@RunWith(AndroidJUnit4::class)
class MarkdownParserTest {

    @Test
    fun parses_headings_by_level() {
        val blocks = Markdown.parse("# One\n\n## Two\n\n### Three")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals("One", Markdown.plainText((blocks[0] as MdBlock.Heading).spans))
    }

    @Test
    fun parses_bold_italic_and_inline_code() {
        val spans = Markdown.parseInline("plain **bold** _italic_ and `code`")
        assertTrue(spans.any { it is MdSpan.Bold && Markdown.plainText(it.spans) == "bold" })
        assertTrue(spans.any { it is MdSpan.Italic && Markdown.plainText(it.spans) == "italic" })
        assertTrue(spans.any { it is MdSpan.Code && it.text == "code" })
    }

    @Test
    fun nested_bold_and_code_stays_one_span_tree() {
        val spans = Markdown.parseInline("**bold with `code` inside**")
        val bold = spans.filterIsInstance<MdSpan.Bold>().single()
        assertTrue(bold.spans.any { it is MdSpan.Code && it.text == "code" })
    }

    @Test
    fun parses_strikethrough_and_nests_it_in_bold() {
        val spans = Markdown.parseInline("~~gone~~ and **~~both~~**")
        val strike = spans.filterIsInstance<MdSpan.Strike>().single()
        assertEquals("gone", Markdown.plainText(strike.spans))
        val bold = spans.filterIsInstance<MdSpan.Bold>().single()
        assertTrue(bold.spans.any { it is MdSpan.Strike && Markdown.plainText(it.spans) == "both" })
    }

    @Test
    fun unclosed_strikethrough_and_single_tilde_stay_literal() {
        assertEquals("~~no closer", Markdown.plainText(Markdown.parseInline("~~no closer")))
        assertEquals("~one~", Markdown.plainText(Markdown.parseInline("~one~")))
    }

    @Test
    fun parses_fenced_code_with_language() {
        val blocks = Markdown.parse("before\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\nafter")
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\n\nval y = 2", code.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun fenced_code_without_language_has_no_label() {
        val code = Markdown.parse("```\nplain\n```").filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals(null, code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun unclosed_fence_is_tolerated() {
        val blocks = Markdown.parse("```\nunterminated")
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("unterminated", code.code)
    }

    @Test
    fun parses_unordered_and_ordered_lists() {
        val blocks = Markdown.parse("- one\n- two\n\n1. first\n2. second")
        val unordered = blocks[0] as MdBlock.ListBlock
        assertEquals(false, unordered.ordered)
        assertEquals(2, unordered.items.size)
        assertEquals("one", Markdown.plainText(unordered.items[0].spans))

        val ordered = blocks[1] as MdBlock.ListBlock
        assertEquals(true, ordered.ordered)
        assertEquals(1, ordered.start)
        assertEquals("second", Markdown.plainText(ordered.items[1].spans))
    }

    @Test
    fun ordered_list_keeps_start_number() {
        val list = Markdown.parse("3. three\n4. four").single() as MdBlock.ListBlock
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        assertEquals(3, list.items[0].number)
        assertEquals(4, list.items[1].number)
    }

    @Test
    fun parses_task_list_checkbox_state() {
        val list = Markdown.parse("- [ ] todo\n- [x] done\n- [X] also done\n- plain").single() as MdBlock.ListBlock
        assertEquals(4, list.items.size)
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
        assertEquals(true, list.items[2].checked)
        assertNull(list.items[3].checked)
        assertEquals("todo", Markdown.plainText(list.items[0].spans))
        assertEquals("also done", Markdown.plainText(list.items[2].spans))
        assertEquals("plain", Markdown.plainText(list.items[3].spans))
    }

    @Test
    fun parses_nested_list_levels() {
        val list = Markdown.parse("- top\n  - child\n    - grandchild\n- back").single() as MdBlock.ListBlock
        assertEquals(listOf(0, 1, 2, 0), list.items.map { it.level })
        assertEquals("child", Markdown.plainText(list.items[1].spans))
        assertEquals("grandchild", Markdown.plainText(list.items[2].spans))
        assertEquals("back", Markdown.plainText(list.items[3].spans))
    }

    @Test
    fun parses_ordered_sublist_with_its_numbers() {
        val list = Markdown.parse("- outer\n  1. first\n  2. second").single() as MdBlock.ListBlock
        assertEquals(listOf(0, 1, 1), list.items.map { it.level })
        assertEquals(listOf(null, 1, 2), list.items.map { it.number })
        assertEquals(false, list.ordered)
    }

    @Test
    fun four_space_nesting_is_one_level() {
        val list = Markdown.parse("- top\n    - nested").single() as MdBlock.ListBlock
        assertEquals(listOf(0, 1), list.items.map { it.level })
        assertEquals("nested", Markdown.plainText(list.items[1].spans))
    }

    @Test
    fun list_row_continuation_lines_join_the_row() {
        val list = Markdown.parse("- long line\n  continues here").single() as MdBlock.ListBlock
        assertEquals(1, list.items.size)
        assertEquals("long line continues here", Markdown.plainText(list.items[0].spans))
    }

    @Test
    fun parses_blockquote() {
        val quote = Markdown.parse("> first line\n> second line").single() as MdBlock.Quote
        assertEquals("first line second line", Markdown.plainText(quote.spans))
    }

    @Test
    fun parses_links_and_keeps_plain_text_label() {
        val spans = Markdown.parseInline("see [the docs](https://pi.dev/docs) now")
        val link = spans.filterIsInstance<MdSpan.Link>().single()
        assertEquals("the docs", link.text)
        assertEquals("https://pi.dev/docs", link.url)
        assertEquals("see the docs now", Markdown.plainText(spans))
    }

    @Test
    fun malformed_link_stays_literal_text() {
        val spans = Markdown.parseInline("[not a link and no close")
        assertEquals("[not a link and no close", Markdown.plainText(spans))
    }

    @Test
    fun parses_table_with_alignment_and_inline_cells() {
        val table = Markdown.parse(
            "| Name | Score | Note |\n" +
                "| :--- | ---: | :-: |\n" +
                "| **pi** | 42 | `ok` |\n" +
                "| a | 1 | b |",
        ).single() as MdBlock.Table

        assertEquals(3, table.header.size)
        assertEquals("Name", Markdown.plainText(table.header[0]))
        assertEquals(listOf(MdAlignment.LEFT, MdAlignment.RIGHT, MdAlignment.CENTER), table.alignments)
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0][0].any { it is MdSpan.Bold })
        assertEquals("42", Markdown.plainText(table.rows[0][1]))
        assertTrue(table.rows[0][2].any { it is MdSpan.Code && it.text == "ok" })
        assertEquals("b", Markdown.plainText(table.rows[1][2]))
    }

    @Test
    fun parses_table_without_outer_pipes() {
        val table = Markdown.parse("a | b\n--- | ---\n1 | 2").single() as MdBlock.Table
        assertEquals("a", Markdown.plainText(table.header[0]))
        assertEquals("b", Markdown.plainText(table.header[1]))
        assertEquals(listOf(MdAlignment.NONE, MdAlignment.NONE), table.alignments)
        assertEquals(1, table.rows.size)
        assertEquals("1", Markdown.plainText(table.rows[0][0]))
        assertEquals("2", Markdown.plainText(table.rows[0][1]))
    }

    @Test
    fun table_cells_keep_pipes_inside_inline_code_and_escaped_pipes() {
        val table = Markdown.parse("| cmd | desc |\n| --- | --- |\n| `a|b` | x \\| y |").single() as MdBlock.Table
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows[0].size)
        assertEquals("a|b", Markdown.plainText(table.rows[0][0]))
        assertEquals("x | y", Markdown.plainText(table.rows[0][1]))
    }

    @Test
    fun table_rows_are_padded_to_the_header_width() {
        val table = Markdown.parse("| a | b | c |\n| --- | --- | --- |\n| 1 |").single() as MdBlock.Table
        assertEquals(3, table.rows[0].size)
        assertEquals("1", Markdown.plainText(table.rows[0][0]))
        assertEquals("", Markdown.plainText(table.rows[0][2]))
    }

    @Test
    fun table_requires_a_matching_delimiter_row() {
        val blocks = Markdown.parse("a | b\n---\n\n| x | y |")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("a | b", Markdown.plainText(blocks[0]))
        assertTrue(blocks[1] is MdBlock.Rule)
        assertEquals("| x | y |", Markdown.plainText(blocks[2]))
    }

    @Test
    fun table_interrupts_a_paragraph() {
        val blocks = Markdown.parse("Intro:\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(2, blocks.size)
        assertEquals("Intro:", Markdown.plainText(blocks[0]))
        assertTrue(blocks[1] is MdBlock.Table)
    }

    @Test
    fun images_are_rendered_from_alt_text_without_loading() {
        val spans = Markdown.parseInline("look ![diagram](https://example.com/a.png) here")
        val image = spans.filterIsInstance<MdSpan.Image>().single()
        assertEquals("diagram", image.alt)
        assertEquals("https://example.com/a.png", image.url)
        assertEquals("look diagram here", Markdown.plainText(spans))
        assertTrue(spans.none { it is MdSpan.Link })
    }

    @Test
    fun image_without_alt_text_falls_back_to_the_url() {
        val image = Markdown.parseInline("![](https://example.com/a.png)").filterIsInstance<MdSpan.Image>().single()
        assertEquals("", image.alt)
        assertEquals("https://example.com/a.png", Markdown.plainText(listOf(image)))
    }

    @Test
    fun html_tags_stay_literal_text() {
        val source = "<b>bold</b> &amp; <script>alert(1)</script>"
        val spans = Markdown.parseInline(source)
        assertEquals(source, Markdown.plainText(spans))
        assertTrue(spans.all { it is MdSpan.Text })
    }

    @Test
    fun unknown_markdown_is_literal_and_never_throws() {
        val source = "Setext\n===\n\n[ref][1]\n\n{curly} %percent%"
        val blocks = Markdown.parse(source)
        assertEquals(3, blocks.size)
        // `===` is not a heading in this subset, so both lines stay prose.
        assertEquals("Setext ===", Markdown.plainText(blocks[0]))
        assertEquals("[ref][1]", Markdown.plainText(blocks[1]))
        assertEquals("{curly} %percent%", Markdown.plainText(blocks[2]))
    }

    @Test
    fun heading_without_space_is_not_a_heading() {
        val blocks = Markdown.parse("#hashtag")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun soft_line_breaks_join_into_one_paragraph() {
        val block = Markdown.parse("line one\nline two").single() as MdBlock.Paragraph
        assertEquals("line one line two", Markdown.plainText(block.spans))
    }

    @Test
    fun horizontal_rule_is_a_block() {
        val blocks = Markdown.parse("before\n\n---\n\nafter")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Rule)
    }

    @Test
    fun blank_paragraphs_do_not_create_empty_blocks() {
        assertTrue(Markdown.parse("\n\n   \n\n").isEmpty())
    }
}
