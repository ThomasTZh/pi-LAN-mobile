package dev.pi.mobile.ui.markdown

/**
 * The markdown subset Pi renders in assistant prose (PLAN.md 5.5): headings,
 * bold/italic/strikethrough, inline code, fenced code with a language label,
 * ordered, unordered and task lists (one or more nesting levels), blockquotes,
 * horizontal rules, links, images and GitHub-style tables. The parser is pure
 * Kotlin so it can be tested from JVM/instrumented tests without Android.
 *
 * The renderer is deliberately dependency-free and passive: HTML is shown as
 * literal text and images are never fetched, only their alt text/URL is shown.
 * Anything the subset does not understand stays literal text: unknown markdown
 * must never swallow content or throw.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock

    /**
     * A run of list rows. [ordered]/[start] describe the first row; each
     * [ListItem] carries its own marker so mixed nested lists render correctly.
     */
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<ListItem>,
    ) : MdBlock

    /**
     * One list row. [level] is the indentation depth (0 = top level), [number]
     * is set for ordered rows and [checked] for task rows (`null` when the row
     * is not a task).
     */
    data class ListItem(
        val spans: List<MdSpan>,
        val level: Int = 0,
        val number: Int? = null,
        val checked: Boolean? = null,
    )

    data class Quote(val spans: List<MdSpan>) : MdBlock

    /** GitHub-style table; cells hold inline spans only. */
    data class Table(
        val header: List<List<MdSpan>>,
        val alignments: List<MdAlignment>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock

    data object Rule : MdBlock
}

/** Per-column alignment from the table delimiter row (`:--`, `:-:`, `--:`). */
enum class MdAlignment { NONE, LEFT, CENTER, RIGHT }

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val spans: List<MdSpan>) : MdSpan
    data class Italic(val spans: List<MdSpan>) : MdSpan
    data class Strike(val spans: List<MdSpan>) : MdSpan
    data class Code(val text: String) : MdSpan

    /** [text] defaults to the URL itself (markdown autolink style). */
    data class Link(val text: String, val url: String) : MdSpan

    /** `![alt](url)`: never fetched; the alt text is shown, the URL is the target. */
    data class Image(val alt: String, val url: String) : MdSpan
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UNORDERED = Regex("^([ \\t]*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^([ \\t]*)(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE = Regex("^([-*_])(?:\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^(```+|~~~+)\\s*(.*)$")

    /** `- [ ] task` / `- [x] task`; the space after `]` is optional for leniency. */
    private val TASK = Regex("^\\[([ xX])](?:\\s+(.*))?$")

    /** A table delimiter cell: `---`, `:--`, `--:` or `:-:`. */
    private val DELIMITER_CELL = Regex("^:?-+:?$")

    /** Deeper nesting than this is flattened to one fixed indent on a phone. */
    private const val MAX_NESTING = 5

    /** Tab width for indentation, as in CommonMark. */
    private const val TAB_WIDTH = 4

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                index++
                continue
            }

            val fence = FENCE.matchEntire(trimmed)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val info = fence.groupValues[2].trim()
                val language = info.substringBefore(' ').takeIf { it.isNotBlank() }
                val code = ArrayList<String>()
                index++
                while (index < lines.size && !isClosingFence(lines[index].trim(), marker)) {
                    code.add(lines[index])
                    index++
                }
                if (index < lines.size) index++ // closing fence
                blocks.add(MdBlock.CodeBlock(language, code.joinToString("\n")))
                continue
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2].trimEnd().trimEnd('#').trim()
                blocks.add(MdBlock.Heading(level, parseInline(text)))
                index++
                continue
            }

            val table = parseTable(lines, index)
            if (table != null) {
                blocks.add(table.block)
                index = table.next
                continue
            }

            if (RULE.matches(trimmed)) {
                blocks.add(MdBlock.Rule)
                index++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quoted = ArrayList<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoted.add(lines[index].trim().removePrefix(">").removePrefix(" ").trim())
                    index++
                }
                blocks.add(MdBlock.Quote(parseInline(quoted.joinToString(" ").trim())))
                continue
            }

            if (isListLine(line)) {
                index = parseList(lines, index, blocks)
                continue
            }

            // Paragraph: consume until a blank line or another block start.
            val paragraph = ArrayList<String>()
            while (index < lines.size) {
                val candidate = lines[index]
                val candidateTrimmed = candidate.trim()
                if (candidateTrimmed.isEmpty()) break
                if (startsBlockAt(lines, index)) break
                paragraph.add(candidateTrimmed)
                index++
            }
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(parseInline(paragraph.joinToString(" "))))
            } else {
                // Defensive: never loop forever on a line the block checks miss.
                blocks.add(MdBlock.Paragraph(parseInline(trimmed)))
                index++
            }
        }
        return blocks
    }

    private fun isClosingFence(line: String, opening: String): Boolean {
        if (line.isEmpty()) return false
        val first = line.first()
        if (first != opening.first()) return false
        return line.all { it == first } && line.length >= opening.length
    }

    // ------------------------------------------------------------------- lists

    private fun isListLine(line: String): Boolean =
        UNORDERED.matchEntire(line) != null || ORDERED.matchEntire(line) != null

    /**
     * Parses one run of list rows, including nested rows (indentation is
     * resolved against the content column of the enclosing rows) and lazy
     * continuation lines. Returns the index after the run.
     */
    private fun parseList(lines: List<String>, startIndex: Int, blocks: MutableList<MdBlock>): Int {
        var index = startIndex
        val items = ArrayList<MdBlock.ListItem>()
        val indentStack = ArrayList<Int>()
        var firstOrdered = false
        var startNumber = 1
        var first = true
        while (index < lines.size) {
            val line = lines[index]
            val unordered = UNORDERED.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val match = unordered ?: ordered ?: break

            val isOrdered = unordered == null
            val markerIndent = indentWidth(match.groupValues[1])
            val number = if (isOrdered) match.groupValues[2].toIntOrNull() else null
            // Content column: where the row's text starts (digits + ". " or bullet + space).
            val markerWidth = if (isOrdered) "${number ?: 1}.".length + 1 else 2
            val level = nestLevel(markerIndent, markerIndent + markerWidth, indentStack)
            val text = if (isOrdered) match.groupValues[3] else match.groupValues[2]
            if (first) {
                firstOrdered = isOrdered
                startNumber = number ?: 1
                first = false
            }
            index++

            // Lazy continuation lines belong to this row until a blank line or
            // another block starts (a nested list row therefore ends the run).
            val body = StringBuilder(text.trim())
            while (index < lines.size) {
                val candidate = lines[index]
                if (candidate.isBlank()) break
                if (isListLine(candidate)) break
                if (startsBlockAt(lines, index)) break
                body.append(' ').append(candidate.trim())
                index++
            }

            val raw = body.toString().trim()
            val task = TASK.matchEntire(raw)
            val checked = task?.groupValues?.get(1)?.equals("x", ignoreCase = true)
            val itemText = if (task != null) task.groupValues[2] else raw
            items.add(MdBlock.ListItem(parseInline(itemText.trim()), level, number, checked))
        }
        blocks.add(MdBlock.ListBlock(firstOrdered, startNumber, items))
        return index
    }

    private fun indentWidth(raw: String): Int {
        var width = 0
        for (char in raw) width += if (char == '\t') TAB_WIDTH else 1
        return width
    }

    /**
     * Resolves a row's nesting depth the CommonMark way: a row is nested when its
     * marker starts at or past the enclosing row's content column, and closes the
     * inner levels while it starts to the left of them. This makes both the
     * two-space and the four-space nesting conventions resolve to one level per
     * step, and keeps a dedented row from inheriting a deeper indent.
     */
    private fun nestLevel(markerIndent: Int, contentIndent: Int, stack: MutableList<Int>): Int {
        while (stack.isNotEmpty() && markerIndent < stack.last()) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) {
            stack.add(contentIndent)
            return 0
        }
        stack.add(contentIndent)
        return (stack.size - 1).coerceIn(0, MAX_NESTING)
    }

    // ------------------------------------------------------------------ tables

    private class ParsedTable(val block: MdBlock.Table, val next: Int)

    /**
     * Parses a GitHub-style table at [index] (header row, delimiter row, then
     * body rows) or returns null when the lines are not a table, so malformed
     * or coincidental pipes stay ordinary paragraph text.
     */
    private fun parseTable(lines: List<String>, index: Int): ParsedTable? {
        val alignments = tableAlignmentsAt(lines, index) ?: return null
        val headerCells = splitRow(lines[index].trim()) ?: return null
        val rows = ArrayList<List<List<MdSpan>>>()
        var cursor = index + 2
        while (cursor < lines.size) {
            val line = lines[cursor]
            if (!looksLikeTableRow(line)) break
            if (startsNonTableBlockAt(lines, cursor)) break
            val cells = splitRow(line.trim()) ?: break
            rows.add(
                List(headerCells.size) { column -> parseInline(cells.getOrElse(column) { "" }.trim()) },
            )
            cursor++
        }
        val header = headerCells.map { parseInline(it.trim()) }
        return ParsedTable(MdBlock.Table(header, alignments, rows), cursor)
    }

    /**
     * [tableAlignmentsAt] reads the delimiter row under the candidate header
     * row. A table needs a header row whose column count matches a delimiter
     * row of the same width, exactly as GitHub renders it.
     */
    private fun tableAlignmentsAt(lines: List<String>, index: Int): List<MdAlignment>? {
        if (index + 1 >= lines.size) return null
        if (!looksLikeTableRow(lines[index])) return null
        val headerCells = splitRow(lines[index].trim()) ?: return null
        if (headerCells.isEmpty()) return null
        val delimiterCells = splitRow(lines[index + 1].trim()) ?: return null
        if (delimiterCells.size != headerCells.size) return null
        return delimiterCells.map { cell ->
            val trimmed = cell.trim()
            if (!DELIMITER_CELL.matches(trimmed)) return null
            when {
                trimmed.startsWith(":") && trimmed.endsWith(":") -> MdAlignment.CENTER
                trimmed.endsWith(":") -> MdAlignment.RIGHT
                trimmed.startsWith(":") -> MdAlignment.LEFT
                else -> MdAlignment.NONE
            }
        }
    }

    private fun looksLikeTableRow(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (!containsUnescapedPipe(trimmed)) return false
        if (trimmed.startsWith(">")) return false
        if (FENCE.matchEntire(trimmed) != null) return false
        if (HEADING.matchEntire(trimmed) != null) return false
        return !isListLine(line)
    }

    private fun containsUnescapedPipe(trimmed: String): Boolean {
        var index = 0
        while (index < trimmed.length) {
            when {
                trimmed[index] == '\\' -> index += 2
                trimmed[index] == '|' -> return true
                else -> index++
            }
        }
        return false
    }

    /**
     * Splits a table row into cell source text. Pipes inside `code spans` and
     * escaped pipes (`\|`) do not split a cell; one leading and one trailing
     * padding pipe are dropped.
     */
    private fun splitRow(trimmed: String): List<String>? {
        if (trimmed.isEmpty()) return null
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var sawPipe = false
        var index = 0
        while (index < trimmed.length) {
            val char = trimmed[index]
            when {
                char == '\\' && index + 1 < trimmed.length && trimmed[index + 1] == '|' -> {
                    current.append("\\|")
                    index += 2
                }

                char == '`' -> {
                    val close = trimmed.indexOf('`', index + 1)
                    if (close > index + 1) {
                        current.append(trimmed, index, close + 1)
                        index = close + 1
                    } else {
                        current.append(char)
                        index++
                    }
                }

                char == '|' -> {
                    cells.add(current.toString())
                    current.setLength(0)
                    sawPipe = true
                    index++
                }

                else -> {
                    current.append(char)
                    index++
                }
            }
        }
        if (!sawPipe) return null
        cells.add(current.toString())
        if (trimmed.startsWith("|") && cells.isNotEmpty() && cells.first().isBlank()) cells.removeAt(0)
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|") && cells.isNotEmpty() && cells.last().isBlank()) {
            cells.removeAt(cells.size - 1)
        }
        return cells
    }

    /** True for block starts that cannot continue a table body. */
    private fun startsNonTableBlockAt(lines: List<String>, index: Int): Boolean {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) return true
        if (FENCE.matchEntire(trimmed) != null) return true
        if (HEADING.matchEntire(trimmed) != null) return true
        if (RULE.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        return isListLine(lines[index])
    }

    private fun startsBlockAt(lines: List<String>, index: Int): Boolean {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) return true
        if (FENCE.matchEntire(trimmed) != null) return true
        if (HEADING.matchEntire(trimmed) != null) return true
        if (RULE.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (isListLine(lines[index])) return true
        return tableAlignmentsAt(lines, index) != null
    }

    // ------------------------------------------------------------------ inline

    fun parseInline(source: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val text = StringBuilder()
        var index = 0

        fun flush() {
            if (text.isNotEmpty()) {
                spans.add(MdSpan.Text(text.toString()))
                text.setLength(0)
            }
        }

        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' && index + 1 < source.length -> {
                    text.append(source[index + 1])
                    index += 2
                }

                char == '`' -> {
                    val close = source.indexOf('`', index + 1)
                    if (close > index + 1) {
                        flush()
                        spans.add(MdSpan.Code(source.substring(index + 1, close)))
                        index = close + 1
                    } else {
                        text.append(char)
                        index++
                    }
                }

                isMarker(source, index, "**") || isMarker(source, index, "__") -> {
                    val marker = source.substring(index, index + 2)
                    val close = findCloser(source, index + 2, marker)
                    if (close > index + 2) {
                        flush()
                        spans.add(MdSpan.Bold(parseInline(source.substring(index + 2, close))))
                        index = close + 2
                    } else {
                        text.append(marker)
                        index += 2
                    }
                }

                isMarker(source, index, "~~") -> {
                    val close = findCloser(source, index + 2, "~~")
                    if (close > index + 2) {
                        flush()
                        spans.add(MdSpan.Strike(parseInline(source.substring(index + 2, close))))
                        index = close + 2
                    } else {
                        text.append("~~")
                        index += 2
                    }
                }

                char == '*' || char == '_' -> {
                    val close = findSingleCloser(source, index + 1, char)
                    if (close > index + 1) {
                        flush()
                        spans.add(MdSpan.Italic(parseInline(source.substring(index + 1, close))))
                        index = close + 1
                    } else {
                        text.append(char)
                        index++
                    }
                }

                char == '!' && index + 1 < source.length && source[index + 1] == '[' -> {
                    val image = parseLink(source, index + 1)
                    if (image != null) {
                        flush()
                        spans.add(MdSpan.Image(image.first.text, image.first.url))
                        index = image.second
                    } else {
                        text.append(char)
                        index++
                    }
                }

                char == '[' -> {
                    val link = parseLink(source, index)
                    if (link != null) {
                        flush()
                        spans.add(link.first)
                        index = link.second
                    } else {
                        text.append(char)
                        index++
                    }
                }

                else -> {
                    text.append(char)
                    index++
                }
            }
        }
        flush()
        return spans
    }

    private fun isMarker(source: String, index: Int, marker: String): Boolean =
        source.startsWith(marker, index)

    private fun findCloser(source: String, from: Int, marker: String): Int {
        var index = from
        while (index < source.length - marker.length + 1) {
            if (source.startsWith(marker, index)) return index
            index++
        }
        return -1
    }

    private fun findSingleCloser(source: String, from: Int, marker: Char): Int {
        var index = from
        while (index < source.length) {
            val current = source[index]
            if (current == '\\') {
                index += 2
                continue
            }
            if (current == marker) return index
            index++
        }
        return -1
    }

    /** `[text](url)` -> span + index after it, or null when malformed. */
    private fun parseLink(source: String, start: Int): Pair<MdSpan.Link, Int>? {
        val closeBracket = source.indexOf(']', start + 1)
        if (closeBracket < 0) return null
        if (closeBracket + 2 >= source.length || source[closeBracket + 1] != '(') return null
        val closeParen = source.indexOf(')', closeBracket + 2)
        if (closeParen <= closeBracket + 2) return null
        val label = source.substring(start + 1, closeBracket)
        val url = source.substring(closeBracket + 2, closeParen).trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        return MdSpan.Link(label, url) to closeParen + 1
    }

    /** Plain-text flattening, used for copy and accessibility labels. */
    fun plainText(spans: List<MdSpan>): String = buildString {
        for (span in spans) {
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Code -> append(span.text)
                is MdSpan.Bold -> append(plainText(span.spans))
                is MdSpan.Italic -> append(plainText(span.spans))
                is MdSpan.Strike -> append(plainText(span.spans))
                is MdSpan.Link -> append(span.text)
                is MdSpan.Image -> append(span.alt.ifBlank { span.url })
            }
        }
    }

    /** Convenience for tests/copy: the plain text of one parsed block. */
    fun plainText(block: MdBlock): String = when (block) {
        is MdBlock.Paragraph -> plainText(block.spans)
        is MdBlock.Heading -> plainText(block.spans)
        is MdBlock.CodeBlock -> block.code
        is MdBlock.ListBlock -> block.items.joinToString("\n") { plainText(it.spans) }
        is MdBlock.Quote -> plainText(block.spans)
        is MdBlock.Table -> (listOf(block.header) + block.rows)
            .joinToString("\n") { row -> row.joinToString(" | ") { plainText(it) } }
        MdBlock.Rule -> ""
    }
}
