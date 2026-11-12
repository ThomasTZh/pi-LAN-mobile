package dev.pi.mobile.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.markdown.Markdown
import dev.pi.mobile.ui.markdown.MdBlock
import dev.pi.mobile.ui.markdown.MdSpan
import org.junit.Assert.assertEquals
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
        assertEquals("one", Markdown.plainText(unordered.items[0]))

        val ordered = blocks[1] as MdBlock.ListBlock
        assertEquals(true, ordered.ordered)
        assertEquals(1, ordered.start)
        assertEquals("second", Markdown.plainText(ordered.items[1]))
    }

    @Test
    fun ordered_list_keeps_start_number() {
        val list = Markdown.parse("3. three\n4. four").single() as MdBlock.ListBlock
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
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
    fun unknown_markdown_is_literal_and_never_throws() {
        val source = "| a | b |\n|---|---|\n| 1 | 2 |\n\n~~strike~~ and #not-a-heading"
        val blocks = Markdown.parse(source)
        val text = blocks.joinToString("\n") { block ->
            when (block) {
                is MdBlock.Paragraph -> Markdown.plainText(block.spans)
                is MdBlock.Heading -> Markdown.plainText(block.spans)
                else -> ""
            }
        }
        assertTrue(text.contains("| a | b |"))
        assertTrue(text.contains("~~strike~~"))
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
