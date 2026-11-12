package dev.pi.mobile.ui.markdown

/**
 * The markdown subset Pi renders in assistant prose (PLAN.md 5.5): headings,
 * bold/italic, inline code, fenced code with a language label, ordered and
 * unordered lists, blockquotes and links. The parser is pure Kotlin so it can
 * be tested from JVM/instrumented tests without Android.
 *
 * Anything the subset does not understand stays literal text: unknown markdown
 * must never swallow content or throw.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<List<MdSpan>>,
    ) : MdBlock

    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val spans: List<MdSpan>) : MdSpan
    data class Italic(val spans: List<MdSpan>) : MdSpan
    data class Code(val text: String) : MdSpan

    /** [text] defaults to the URL itself (markdown autolink style). */
    data class Link(val text: String, val url: String) : MdSpan
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UNORDERED = Regex("^([ \\t]*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^([ \\t]*)(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE = Regex("^([-*_])(?:\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^(```+|~~~+)\\s*(.*)$")

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

            val unordered = UNORDERED.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            if (unordered != null || ordered != null) {
                val listOrdered = unordered == null
                val start = if (listOrdered) ordered!!.groupValues[2].toIntOrNull() ?: 1 else 1
                val items = ArrayList<List<MdSpan>>()
                while (index < lines.size) {
                    val current = lines[index]
                    val nextUnordered = UNORDERED.matchEntire(current)
                    val nextOrdered = ORDERED.matchEntire(current)
                    val text = when {
                        !listOrdered && nextUnordered != null -> nextUnordered.groupValues[2]
                        listOrdered && nextOrdered != null -> nextOrdered.groupValues[3]
                        else -> null
                    } ?: break
                    items.add(parseInline(text.trim()))
                    index++
                }
                blocks.add(MdBlock.ListBlock(listOrdered, start, items))
                continue
            }

            // Paragraph: consume until a blank line or another block start.
            val paragraph = ArrayList<String>()
            while (index < lines.size) {
                val candidate = lines[index]
                val candidateTrimmed = candidate.trim()
                if (candidateTrimmed.isEmpty()) break
                if (startsBlock(candidate)) break
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

    private fun startsBlock(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (FENCE.matches(trimmed)) return true
        if (HEADING.matches(trimmed)) return true
        if (RULE.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        return UNORDERED.matches(line) || ORDERED.matches(line)
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
        if (closeBracket <= start + 1) return null
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
                is MdSpan.Link -> append(span.text)
            }
        }
    }
}
