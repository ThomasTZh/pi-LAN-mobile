package dev.pi.mobile.ui.connect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.net.LocalNetworkPolicy
import dev.pi.mobile.net.PinRules
import dev.pi.mobile.ui.components.PiErrorLine
import dev.pi.mobile.ui.components.PiGlyph
import dev.pi.mobile.ui.theme.PiDims

/** Example address shown while the field is empty; the app never ships a real host. */
const val EXAMPLE_PI_ADDRESS = "192.168.1.50:7690"

/**
 * Connect screen: centered card, one server field, one PIN field, Remember on,
 * full-width 52dp Connect, and one inline error line. The field keeps the last
 * host prefilled and shows the address without the scheme so it matches the
 * `serve.sh` output.
 */
@Composable
fun ConnectScreen(
    initialAddress: String,
    remember: Boolean,
    connecting: Boolean,
    error: String?,
    onRememberChange: (Boolean) -> Unit,
    onConnect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var address by rememberSaveable(initialAddress) {
        mutableStateOf(initialAddress)
    }
    var pinDigits by rememberSaveable { mutableStateOf("") }
    // Hidden by default and never persisted: a PIN that grants code execution on
    // the desktop must not render in clear text (review F16).
    var pinRevealed by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val shown = localError ?: friendlyConnectError(error)

    fun submit() {
        val normalized = LocalNetworkPolicy.normalize(address)
        if (!normalized.ok) {
            localError = normalized.error ?: "Enter a private LAN address."
            return
        }
        val pinError = PinRules.validate(pinDigits)
        if (pinError != null) {
            localError = pinError
            return
        }
        localError = null
        focus.clearFocus()
        onConnect(address.trim(), pinDigits)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PiGlyph(size = 46.dp)
                        Spacer(Modifier.size(14.dp))
                        Text(
                            text = stringResource(R.string.connect_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it.take(80)
                            localError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_address"),
                        label = { Text(stringResource(R.string.connect_server)) },
                        placeholder = {
                            Text(EXAMPLE_PI_ADDRESS, fontFamily = FontFamily.Monospace)
                        },
                        singleLine = true,
                        enabled = !connecting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = PinRules.format(pinDigits),
                        onValueChange = { next ->
                            pinDigits = next.filter { it.isDigit() }.take(PinRules.LENGTH)
                            localError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_pin"),
                        label = { Text(stringResource(R.string.connect_pin)) },
                        placeholder = { Text("123 456", fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        enabled = !connecting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        visualTransformation = if (pinRevealed) {
                            VisualTransformation.None
                        } else {
                            PinMaskTransformation
                        },
                        trailingIcon = {
                            val label = stringResource(
                                if (pinRevealed) R.string.connect_pin_hide else R.string.connect_pin_show,
                            )
                            IconButton(
                                onClick = { pinRevealed = !pinRevealed },
                                enabled = !connecting,
                                modifier = Modifier.testTag("connect_pin_reveal"),
                            ) {
                                EyeGlyph(
                                    revealed = pinRevealed,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { contentDescription = label },
                                )
                            }
                        },
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PiDims.TouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.connect_remember),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = remember,
                            onCheckedChange = onRememberChange,
                            enabled = !connecting,
                            modifier = Modifier.testTag("connect_remember"),
                        )
                    }

                    if (shown != null) {
                        Spacer(Modifier.height(8.dp))
                        PiErrorLine(shown, Modifier.testTag("connect_error"))
                    }

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = { submit() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PiDims.ButtonHeight)
                            .testTag("connect_submit"),
                        enabled = !connecting,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(stringResource(R.string.connect_connecting), style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text(stringResource(R.string.connect_action), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.connect_hint),
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Maps the transport's technical messages to the four inline states PLAN.md 5.5
 * asks for: rejected PIN, locked PIN, unreachable host, not a Pi host.
 */
fun friendlyConnectError(raw: String?): String? {
    val message = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val lower = message.lowercase()
    return when {
        lower.contains("locked") || lower.contains("wait 30") ->
            "PIN locked. Wait 30 s, then try again."

        lower.contains("not accepted") || lower.contains("rejected") || lower.contains("401") ||
            lower.contains("not the pin") ->
            "PIN rejected. Check the six digits printed by serve.sh."

        lower.contains("refused") || lower.contains("timed out") || lower.contains("timeout") ||
            lower.contains("could not be reached") || lower.contains("unreachable") ||
            lower.contains("unknownhost") || lower.contains("could not be resolved") ->
            "Host unreachable. Check the desktop and that both are on the same Wi-Fi."

        lower.contains("websocket") || lower.contains("handshake") || lower.contains("hello") ||
            lower.contains("unexpected response") || lower.contains("no session cookie") ||
            lower.contains("not a pi host") || lower.contains("404") || lower.contains("refused the websocket") ->
            "Not a Pi host. Start serve.sh on the desktop and retry."

        else -> message
    }
}

/** Display form of a saved base URL: `192.168.1.50:7690`. */
fun displayAddress(baseUrl: String?): String? {
    val raw = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return raw.removePrefix("http://").removePrefix("https://").removeSuffix("/")
}

/**
 * Masks every digit of the PIN field while keeping the grouped layout
 * ("••• •••"), so the caret and field width do not jump (review F16).
 */
private object PinMaskTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = AnnotatedString(PinRules.maskDisplay(text.text))
        return TransformedText(masked, OffsetMapping.Identity)
    }
}

/**
 * A small hand-drawn eye, so the reveal toggle needs no icon dependency. The
 * slash marks the masked state; the caller supplies the content description.
 */
@Composable
private fun EyeGlyph(revealed: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx())
        val eye = Path().apply {
            moveTo(0f, size.height / 2f)
            quadraticBezierTo(size.width / 2f, 0f, size.width, size.height / 2f)
            quadraticBezierTo(size.width / 2f, size.height, 0f, size.height / 2f)
        }
        drawPath(eye, tint, style = stroke)
        if (revealed) {
            drawCircle(tint, radius = size.height * 0.16f, center = Offset(size.width / 2f, size.height / 2f))
        } else {
            drawLine(
                color = tint,
                start = Offset(size.width * 0.05f, size.height * 0.9f),
                end = Offset(size.width * 0.95f, size.height * 0.1f),
                strokeWidth = 1.8.dp.toPx(),
            )
        }
    }
}
