package de.vorleser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(apiKey: String, onSave: (String) -> Unit, onSpeechSettings: () -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf(apiKey) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.family_settings), fontSize = 30.sp) }
        item {
            OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.api_key)) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
        }
        item { Text(stringResource(R.string.key_storage_notice), fontSize = 20.sp) }
        item { BigButton(stringResource(R.string.save), { onSave(key) }) }
        item { BigButton(stringResource(R.string.setup_speech), onSpeechSettings) }
        item { BigButton(stringResource(R.string.back), onBack) }
    }
}
