package de.vorleser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Text(label, fontSize = 26.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SpeechHelp(speech: SpeechState, onSettings: () -> Unit, onRetry: () -> Unit) {
    if (speech.error != null) {
        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(speech.error), fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onSettings) { Text(stringResource(R.string.setup_voice), fontSize = 20.sp) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.check_voice), fontSize = 20.sp) }
            }
        }
    } else if (speech.initializing) Text(stringResource(R.string.speech_initializing), fontSize = 20.sp)
    else if (speech.speaking) Text(stringResource(R.string.speech_playing), fontSize = 20.sp)
}


@Composable
internal fun PersistenceNotice(state: ReaderState, onSave: () -> Unit, onCleanup: () -> Unit) {
    if (state.unsavedChanges || state.cleanupPending) {
        Text(stringResource(if (state.unsavedChanges) R.string.unsaved_text else R.string.photo_cleanup_pending),
            fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = if (state.unsavedChanges) onSave else onCleanup) {
            Text(stringResource(if (state.unsavedChanges) R.string.retry_save else R.string.retry_cleanup), fontSize = 22.sp)
        }
    }
}
