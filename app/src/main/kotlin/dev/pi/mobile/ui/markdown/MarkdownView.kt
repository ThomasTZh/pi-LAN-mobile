package dev.pi.mobile.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pi.mobile.ui.components.CopyGlyph
import dev.pi.mobile.ui.components.PiDivider
import dev.pi.mobile.ui.components.copyToClipboard
import dev.pi.mobile.ui.theme.codeTextStyle
import kotlinx.coroutines.delay

/**
 * Renders the [Markdown] subset with Pi's typography: prose 15sp/22sp, code
 * 12.5sp monospace in a language-labelled block with copy, GitHub-style tables
 * with content-sized columns that scroll sideways on a narrow phone. Unknown
 * syntax is shown literally and images are never loaded.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineMarkdown(
                    spans = block.spans,
                    modifier = Modifier.fillMaxWidth(),
                    onOpenLink = onOpenLink,
                )

                is MdBlock.Heading -> InlineMarkdown(
                    spans = block.spans,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    style = headingStyle(block.level),
                    onOpenLink = onOpenLink,
                )

                is MdBlock.CodeBlock -> MarkdownCodeBlock(block.language, block.code)
                is MdBlock.ListBlock -> MarkdownList(block, onOpenLink)
                is MdBlock.Quote -> MarkdownQuote(block.spans, onOpenLink)
                is MdBlock.Table -> MarkdownTable(block, onOpenLink)
                MdBlock.Rule -> PiDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

/** Link and inline-code styling, shared by prose and table cells. */
@Composable
private fun inlineStyles(): Pair<SpanStyle, SpanStyle> {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        color = MaterialTheme.colorScheme.onSurface,
    )
    return remember(linkStyle, codeStyle) { linkStyle to codeStyle }
}

@Composable
private fun InlineMarkdown(
    spans: List<MdSpan>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val (linkStyle, codeStyle) = inlineStyles()
    val currentHandler by rememberUpdatedState(onOpenLink)
    val annotated = remember(spans, linkStyle, codeStyle) {
        buildInlineText(spans, linkStyle, codeStyle) { url -> currentHandler?.invoke(url) }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private fun buildInlineText(
    spans: List<MdSpan>,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
    onOpenLink: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString { appendSpans(spans, linkStyle, codeStyle, onOpenLink) }

private fun AnnotatedString.Builder.appendSpans(
    spans: List<MdSpan>,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle,
    onOpenLink: ((String) -> Unit)?,
) {
    for (span in spans) {
        when (span) {
            is MdSpan.Text -> append(span.text)

            is MdSpan.Bold -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                appendSpans(span.spans, linkStyle, codeStyle, onOpenLink)
                pop()
            }

            is MdSpan.Italic -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendSpans(span.spans, linkStyle, codeStyle, onOpenLink)
                pop()
            }

            is MdSpan.Strike -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendSpans(span.spans, linkStyle, codeStyle, onOpenLink)
                pop()
            }

            is MdSpan.Code -> {
                pushStyle(codeStyle)
                append(span.text)
                pop()
            }

            is MdSpan.Link -> appendLinkSpan(span.text, span.url, linkStyle, onOpenLink)

            is MdSpan.Image -> appendLinkSpan(span.alt.ifBlank { span.url }, span.url, linkStyle, onOpenLink)
        }
    }
}

private fun AnnotatedString.Builder.appendLinkSpan(
    label: String,
    url: String,
    linkStyle: SpanStyle,
    onOpenLink: ((String) -> Unit)?,
) {
    if (onOpenLink == null) {
        pushStyle(linkStyle)
        append(label)
        pop()
    } else {
        withLink(
            LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(style = linkStyle),
                linkInteractionListener = { onOpenLink(url) },
            ),
        ) {
            append(label)
        }
    }
}

@Composable
private fun MarkdownList(block: MdBlock.ListBlock, onOpenLink: ((String) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.level * NESTED_INDENT).dp)
                    .taskTestTag(item.checked),
                verticalAlignment = Alignment.Top,
            ) {
                when (val checked = item.checked) {
                    null -> Text(
                        text = item.number?.let { "$it." } ?: bulletGlyph(block, item, index),
                        modifier = Modifier.widthIn(min = 22.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        text = if (checked) "☑" else "☐",
                        modifier = Modifier
                            .widthIn(min = 24.dp)
                            .semantics {
                                contentDescription = if (checked) "Checked task" else "Unchecked task"
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (checked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                InlineMarkdown(
                    spans = item.spans,
                    modifier = Modifier.weight(1f),
                    onOpenLink = onOpenLink,
                )
            }
        }
    }
}

/** Indent per nesting level; deeper input is clamped so rows stay readable. */
private const val NESTED_INDENT = 18

private fun Modifier.taskTestTag(checked: Boolean?): Modifier = when (checked) {
    null -> this
    true -> testTag("md_task_checked")
    false -> testTag("md_task_unchecked")
}

private fun bulletGlyph(block: MdBlock.ListBlock, item: MdBlock.ListItem, index: Int): String {
    if (block.ordered && item.number == null) return "${block.start + index}."
    return when (item.level.coerceIn(0, 2)) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }
}

@Composable
private fun MarkdownQuote(spans: List<MdSpan>, onOpenLink: ((String) -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            InlineMarkdown(
                spans = spans,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                onOpenLink = onOpenLink,
            )
        }
    }
}

// ------------------------------------------------------------------- tables

/** Narrowest/widest a table column is allowed to get on a phone. */
private val TableColumnMinWidth = 56.dp
private val TableColumnMaxWidth = 240.dp
private const val TableCellHorizontal = 8
private const val TableCellVertical = 6

/**
 * GitHub-style table: the header row is shaded, columns are sized from their
 * measured content (clamped so a phone keeps a readable line length) and the
 * whole grid scrolls horizontally when it does not fit.
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, onOpenLink: ((String) -> Unit)?) {
    val (linkStyle, codeStyle) = inlineStyles()
    val currentHandler by rememberUpdatedState(onOpenLink)
    val headerStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val cellStyle = MaterialTheme.typography.bodyMedium

    val headerText = remember(table.header, linkStyle, codeStyle) {
        table.header.map { spans ->
            buildInlineText(spans, linkStyle, codeStyle) { url -> currentHandler?.invoke(url) }
        }
    }
    val rowText = remember(table.rows, linkStyle, codeStyle) {
        table.rows.map { row ->
            row.map { spans ->
                buildInlineText(spans, linkStyle, codeStyle) { url -> currentHandler?.invoke(url) }
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(table, headerText, rowText, headerStyle, cellStyle, measurer, density) {
        (0 until table.header.size).map { column ->
            val headerWidth = measurer.measure(headerText[column], headerStyle).size.width
            val bodyWidth = rowText.maxOfOrNull { row ->
                measurer.measure(row.getOrElse(column) { AnnotatedString("") }, cellStyle).size.width
            } ?: 0
            with(density) {
                (maxOf(headerWidth, bodyWidth).toDp() + (TableCellHorizontal * 2).dp)
                    .coerceIn(TableColumnMinWidth, TableColumnMaxWidth)
            }
        }
    }
    val tableWidth = widths.fold(0.dp) { total, width -> total + width }
    val horizontal = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(horizontal)
                .testTag("md_table"),
        ) {
            TableRow(
                cells = headerText,
                widths = widths,
                tableWidth = tableWidth,
                style = headerStyle,
                alignments = table.alignments,
                tag = "md_table_header",
                header = true,
            )
            TableDivider(tableWidth)
            rowText.forEachIndexed { index, cells ->
                if (index > 0) TableDivider(tableWidth)
                TableRow(
                    cells = cells,
                    widths = widths,
                    tableWidth = tableWidth,
                    style = cellStyle,
                    alignments = table.alignments,
                    tag = "md_table_row",
                    header = false,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    tableWidth: Dp,
    style: TextStyle,
    alignments: List<MdAlignment>,
    tag: String,
    header: Boolean,
) {
    val background = if (header) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Row(
        modifier = Modifier
            .width(tableWidth)
            .background(background)
            .testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        widths.forEachIndexed { column, width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = TableCellHorizontal.dp, vertical = TableCellVertical.dp),
            ) {
                Text(
                    text = cells.getOrElse(column) { AnnotatedString("") },
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = when (alignments.getOrElse(column) { MdAlignment.NONE }) {
                        MdAlignment.CENTER -> TextAlign.Center
                        MdAlignment.RIGHT -> TextAlign.End
                        else -> TextAlign.Start
                    },
                )
            }
        }
    }
}

@Composable
private fun TableDivider(tableWidth: Dp) {
    Box(
        modifier = Modifier
            .width(tableWidth)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

// --------------------------------------------------------------------- code

@Composable
private fun MarkdownCodeBlock(language: String?, code: String) {
    val context = LocalContext.current
    val codeStyle = codeTextStyle()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language ?: "code",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        copyToClipboard(context, code, "Pi code")
                        copied = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    if (copied) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Copied",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        CopyGlyph()
                    }
                }
            }
            val horizontal = rememberScrollState()
            val vertical = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(vertical)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            ) {
                Text(
                    text = code.ifBlank { " " },
                    style = codeStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(horizontal),
                )
            }
        }
    }
}
