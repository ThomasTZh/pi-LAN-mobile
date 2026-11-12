package dev.pi.mobile.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.pi.mobile.R
import dev.pi.mobile.session.NewChatFolder
import dev.pi.mobile.ui.components.PiErrorLine
import dev.pi.mobile.ui.theme.PiDims
import kotlinx.coroutines.launch

/**
 * New-chat sheet: the user picks the workspace folder Pi should run in. The
 * current folder is prefilled, recent project folders from the session store
 * are one tap away, and the host checks the path with `meta project.check`
 * before the new chat opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheetContent(
    currentFolder: String?,
    knownFolders: List<String>,
    onClose: () -> Unit,
    onCheck: suspend (String) -> String?,
    onStart: (String) -> Unit,
) {
    var folder by rememberSaveable { mutableStateOf(currentFolder.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (checking) return
        val candidate = NewChatFolder.normalize(folder)
        NewChatFolder.validate(folder)?.let {
            problem = it
            return
        }
        problem = null
        checking = true
        scope.launch {
            val error = runCatching { onCheck(candidate) }.getOrElse { "Could not check the folder." }
            checking = false
            if (error == null) {
                onStart(candidate)
            } else {
                problem = error
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 6.dp, bottom = 18.dp),
    ) {
        Text(
            text = stringResource(R.string.newchat_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.newchat_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = folder,
            onValueChange = {
                folder = it.take(NewChatFolder.MAX_LENGTH)
                problem = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("newchat_folder"),
            label = { Text(stringResource(R.string.newchat_folder)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )

        problem?.let {
            Spacer(Modifier.height(10.dp))
            PiErrorLine(it, Modifier.testTag("newchat_error"))
        }

        if (knownFolders.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.newchat_recent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            knownFolders.forEachIndexed { index, path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PiDims.TouchTarget)
                        .clickable {
                            folder = path
                            problem = null
                        }
                        .testTag("newchat_known_$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.chat_cancel))
            }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = { submit() },
                enabled = folder.isNotBlank() && !checking,
                modifier = Modifier
                    .widthIn(min = 132.dp)
                    .heightIn(min = PiDims.ButtonHeight)
                    .testTag("newchat_start"),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.newchat_start))
                }
            }
        }
    }
}
