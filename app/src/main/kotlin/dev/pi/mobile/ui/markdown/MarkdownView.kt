package dev.pi.mobile.ui.markdown

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.pi.mobile.ui.components.CopyGlyph
import dev.pi.mobile.ui.components.PiDivider
import dev.pi.mobile.ui.components.copyToClipboard
import dev.pi.mobile.ui.theme.codeTextStyle
import kotlinx.coroutines.delay

/**
 * Renders the [Markdown] subset with Pi's typography: prose 15sp/22sp, code
 * 12.5sp monospace in a language-labelled block with copy. Unknown syntax is
 * shown literally.
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
                is MdBlock.Paragraph -> InlineMarkdown(block.spans, onOpenLink = onOpenLink)
                is MdBlock.Heading -> InlineMarkdown(
                    spans = block.spans,
                    style = headingStyle(block.level),
                    onOpenLink = onOpenLink,
                    modifier = Modifier.padding(top = 2.dp),
                )

                is MdBlock.CodeBlock -> MarkdownCodeBlock(block.language, block.code)
                is MdBlock.ListBlock -> MarkdownList(block, onOpenLink)
                is MdBlock.Quote -> MarkdownQuote(block.spans, onOpenLink)
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

@Composable
private fun InlineMarkdown(
    spans: List<MdSpan>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val currentHandler by androidx.compose.runtime.rememberUpdatedState(onOpenLink)
    val annotated = remember(spans, linkStyle, codeStyle) {
        buildAnnotatedString { appendSpans(spans, linkStyle, codeStyle) { url -> currentHandler?.invoke(url) } }
    }
    Text(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

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

            is MdSpan.Code -> {
                pushStyle(codeStyle)
                append(span.text)
                pop()
            }

            is MdSpan.Link -> {
                if (onOpenLink == null) {
                    pushStyle(linkStyle)
                    append(span.text)
                    pop()
                } else {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = span.url,
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener = { onOpenLink(span.url) },
                        ),
                    ) {
                        append(span.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownList(block: MdBlock.ListBlock, onOpenLink: ((String) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (block.ordered) "${block.start + index}." else "•",
                    modifier = Modifier.widthIn(min = 22.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InlineMarkdown(
                    spans = item,
                    modifier = Modifier.weight(1f),
                    onOpenLink = onOpenLink,
                )
            }
        }
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
                onOpenLink = onOpenLink,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

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
