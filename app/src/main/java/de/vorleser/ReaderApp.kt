package de.vorleser

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun ReaderApp(model: ReaderViewModel = viewModel(
    factory = ReaderViewModelFactory(LocalContext.current.applicationContext as Application),
)) {
    val state by model.state.collectAsStateWithLifecycle()
    val speech by model.speaker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.camera() else model.cameraError(
            R.string.camera_permission_denied)
    }
    val speechSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.speaker.initialize()
    }
    val requestCamera = { permission.launch(Manifest.permission.CAMERA) }
    val openSpeechSettings = {
        try {
            speechSettings.launch(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            try { speechSettings.launch(Intent(Settings.ACTION_SETTINGS)) }
            catch (e: ActivityNotFoundException) { settingsMessage = context.getString(R.string.voice_settings_help) }
        }
    }
    BackHandler(enabled = state.screen != Screen.HOME) {
        if (state.screen == Screen.WORKING) model.cancelAnalysis() else model.back()
    }

    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text(stringResource(R.string.new_letter_title)) },
        text = { Text(stringResource(R.string.new_letter_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmNew = false; model.newLetter() }) { Text(stringResource(R.string.new_letter)) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.keep_letter)) } },
    )
    if (settingsMessage != null) AlertDialog(
        onDismissRequest = { settingsMessage = null },
        text = { Text(settingsMessage.orEmpty()) },
        confirmButton = { TextButton(onClick = { settingsMessage = null }) { Text(stringResource(R.string.understood)) } },
    )

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.screen != Screen.WORKING) PersistenceNotice(state, model::retrySave, model::retryCleanup)
            when (state.screen) {
                Screen.HOME -> LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item { Text(stringResource(R.string.app_name), fontSize = 48.sp) }
                    item { Text(stringResource(R.string.app_description), fontSize = 26.sp, lineHeight = 34.sp) }
                    item {
                        if (state.session.pages.isNotEmpty() || state.session.draftPhoto != null) {
                            BigButton(stringResource(R.string.open_letter), model::resumeSession)
                            Spacer(Modifier.height(12.dp))
                            BigButton(stringResource(R.string.new_letter), { confirmNew = true })
                        } else BigButton(stringResource(R.string.photograph_letter), requestCamera)
                    }
                    item { SpeechHelp(speech, openSpeechSettings, model.speaker::initialize) }
                    item { TextButton(onClick = model::settings) { Text(stringResource(R.string.settings), fontSize = 22.sp) } }
                }
                Screen.CAMERA -> CameraScreen(model::captureFile, model::captured, model::back, model::cameraError)
                Screen.REVIEW -> PhotoReview(model.photo(), state.session.pages.size + 1,
                    onRetake = requestCamera, onRead = model::analyze, onBack = model::back)
                Screen.WORKING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        CircularProgressIndicator(Modifier.size(72.dp))
                        Text(stringResource(state.workLabel, state.workPage), fontSize = 30.sp, textAlign = TextAlign.Center)
                        if (state.canCancel) BigButton(stringResource(R.string.cancel), model::cancelAnalysis)
                    }
                }
                Screen.RESULT -> {
                    Text(pluralStringResource(R.plurals.letter_pages_title, state.session.pages.size, state.session.pages.size), fontSize = 30.sp)
                    SpeechHelp(speech, openSpeechSettings, model.speaker::initialize)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton(stringResource(R.string.read_aloud), { model.speaker.speak(state.session.text) }, Modifier.weight(1f),
                            enabled = !speech.initializing && speech.error == null)
                        BigButton(stringResource(R.string.stop), model.speaker::stop, Modifier.weight(1f))
                    }
                    Text(state.session.text, fontSize = 28.sp, lineHeight = 38.sp,
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton(if (state.session.draftPhoto != null) stringResource(R.string.resume_photo) else stringResource(R.string.next_page),
                            { if (state.session.draftPhoto != null) model.resumeSession() else requestCamera() }, Modifier.weight(1f), enabled = !state.unsavedChanges)
                        BigButton(stringResource(R.string.new_letter), { confirmNew = true }, Modifier.weight(1f))
                    }
                }
                Screen.ERROR -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(state.problem?.let { stringResource(it.message) }.orEmpty(), fontSize = 28.sp, lineHeight = 38.sp) }
                    when (state.problem?.recovery) {
                        Recovery.RETRY -> item { BigButton(stringResource(R.string.retry), model::analyze) }
                        Recovery.SETTINGS -> item { BigButton(stringResource(R.string.settings), model::settings) }
                        Recovery.RETAKE -> item { BigButton(stringResource(R.string.retake), requestCamera) }
                        null -> Unit
                    }
                    item { BigButton(stringResource(R.string.back), model::back) }
                    if (state.problem?.kind == ProblemKind.CAMERA) item {
                        TextButton(onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))) }
                            catch (e: ActivityNotFoundException) { settingsMessage = context.getString(R.string.app_settings_help) }
                        }) { Text(stringResource(R.string.camera_permission_settings), fontSize = 20.sp) }
                    }
                    item { BigButton(stringResource(R.string.new_letter), { confirmNew = true }) }
                }
                Screen.SETTINGS -> {
                    SettingsScreen(model.apiKey(), model::saveApiKey, openSpeechSettings, model::back)
                }
            }
        }
    }
}
