package de.vorleser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { MaterialTheme { App() } }
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Text(label, fontSize = 26.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun App(model: ReaderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val speech by model.speaker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.camera() else model.cameraError(
            "Die Kamera ist nicht freigegeben. Bitte erlauben Sie den Kamerazugriff in den App-Einstellungen.")
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
            catch (e: ActivityNotFoundException) { settingsMessage = "Bitte öffnen Sie die Android-Einstellungen und installieren Sie unter Sprachausgabe eine deutsche Offline-Stimme." }
        }
    }
    BackHandler(enabled = state.screen != Screen.HOME) {
        if (state.screen == Screen.WORKING) model.cancelAnalysis() else model.back()
    }

    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text("Neuen Brief beginnen?") },
        text = { Text("Der bisherige Brief und das gespeicherte Foto werden von diesem Gerät gelöscht.") },
        confirmButton = { TextButton(onClick = { confirmNew = false; model.newLetter() }) { Text("Neuer Brief") } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Behalten") } },
    )
    if (settingsMessage != null) AlertDialog(
        onDismissRequest = { settingsMessage = null },
        text = { Text(settingsMessage.orEmpty()) },
        confirmButton = { TextButton(onClick = { settingsMessage = null }) { Text("Verstanden") } },
    )

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.screen) {
                Screen.HOME -> LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item { Text("Vorleser", fontSize = 48.sp) }
                    item { Text("Briefe fotografieren und auf Deutsch vorlesen lassen.", fontSize = 26.sp, lineHeight = 34.sp) }
                    item {
                        if (state.session.pages.isNotEmpty() || state.session.draftPhoto != null) {
                            BigButton("Letzten Brief öffnen", model::resumeSession)
                            Spacer(Modifier.height(12.dp))
                            BigButton("Neuer Brief", { confirmNew = true })
                        } else BigButton("Brief fotografieren", requestCamera)
                    }
                    item { SpeechHelp(speech, openSpeechSettings, model.speaker::initialize) }
                    item { TextButton(onClick = model::settings) { Text("Einstellungen", fontSize = 22.sp) } }
                }
                Screen.CAMERA -> CameraScreen(model::captureFile, model::captured, model::back, model::cameraError)
                Screen.REVIEW -> PhotoReview(model.photo(), state.session.pages.size + 1,
                    onRetake = requestCamera, onRead = model::analyze, onBack = model::back)
                Screen.WORKING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        CircularProgressIndicator(Modifier.size(72.dp))
                        Text(state.workLabel, fontSize = 30.sp, textAlign = TextAlign.Center)
                        if (state.canCancel) BigButton("Abbrechen", model::cancelAnalysis)
                    }
                }
                Screen.RESULT -> {
                    Text(if (state.session.pages.size == 1) "Ihr Brief" else "Ihr Brief · ${state.session.pages.size} Seiten", fontSize = 30.sp)
                    state.notice?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.error) }
                    SpeechHelp(speech, openSpeechSettings, model.speaker::initialize)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("Vorlesen", { model.speaker.speak(state.session.text) }, Modifier.weight(1f),
                            enabled = !speech.initializing && speech.error == null)
                        BigButton("Stopp", model.speaker::stop, Modifier.weight(1f))
                    }
                    Text(state.session.text, fontSize = 28.sp, lineHeight = 38.sp,
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton(if (state.session.draftPhoto != null) "Foto fortsetzen" else "Weitere Seite",
                            { if (state.session.draftPhoto != null) model.resumeSession() else requestCamera() }, Modifier.weight(1f))
                        BigButton("Neuer Brief", { confirmNew = true }, Modifier.weight(1f))
                    }
                }
                Screen.ERROR -> LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text(state.problem?.message.orEmpty(), fontSize = 28.sp, lineHeight = 38.sp) }
                    when (state.problem?.recovery) {
                        Recovery.RETRY -> item { BigButton("Erneut versuchen", model::analyze) }
                        Recovery.SETTINGS -> item { BigButton("Einstellungen", model::settings) }
                        Recovery.RETAKE -> item { BigButton("Neu fotografieren", requestCamera) }
                        null -> Unit
                    }
                    item { BigButton("Zurück", model::back) }
                    if (state.problem?.message?.contains("Kamera") == true) item {
                        TextButton(onClick = {
                            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))) }
                            catch (e: ActivityNotFoundException) { settingsMessage = "Bitte öffnen Sie die App-Einstellungen von Vorleser in Android." }
                        }) { Text("Kameraberechtigung in Android prüfen", fontSize = 20.sp) }
                    }
                    item { BigButton("Neuer Brief", { confirmNew = true }) }
                }
                Screen.SETTINGS -> {
                    var key by remember { mutableStateOf(model.apiKey()) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { Text("Einstellungen für die Familie", fontSize = 30.sp) }
                        item {
                            OutlinedTextField(key, { key = it }, label = { Text("Anthropic-API-Schlüssel") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth())
                        }
                        item { Text("Der Schlüssel wird auf diesem Gerät gespeichert. Das Einlesen eines Fotos nutzt den kostenpflichtigen Anthropic-Dienst.", fontSize = 20.sp) }
                        item { BigButton("Speichern", { model.saveApiKey(key) }) }
                        item { BigButton("Sprachausgabe einrichten", openSpeechSettings) }
                        item { BigButton("Zurück", model::back) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechHelp(speech: SpeechState, onSettings: () -> Unit, onRetry: () -> Unit) {
    if (speech.error != null) {
        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(speech.error, fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onSettings) { Text("Stimme einrichten", fontSize = 20.sp) }
                TextButton(onClick = onRetry) { Text("Stimme erneut prüfen", fontSize = 20.sp) }
            }
        }
    } else if (speech.initializing) Text("Die Sprachausgabe wird vorbereitet …", fontSize = 20.sp)
    else if (speech.speaking) Text("Ich lese vor …", fontSize = 20.sp)
}

@Composable
private fun ColumnScope.PhotoReview(photo: File?, page: Int, onRetake: () -> Unit, onRead: () -> Unit, onBack: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo?.path) {
        value = withContext(Dispatchers.IO) {
            photo?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }
    }
    Text("Seite $page prüfen", fontSize = 30.sp)
    Text("Sind alle Ränder sichtbar und die Schrift scharf?", fontSize = 22.sp)
    Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "Vorschau der aufgenommenen Seite", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text("Die Vorschau wird geladen. Falls sie leer bleibt, fotografieren Sie die Seite erneut.", fontSize = 22.sp)
    }
    Text("Mit „Seite einlesen“ wird dieses Foto an Anthropic gesendet. Der aktuelle Brief bleibt bis „Neuer Brief“ auf diesem Gerät gespeichert.", fontSize = 18.sp, lineHeight = 24.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("Neu fotografieren", onRetake, Modifier.weight(1f))
        BigButton("Seite einlesen", onRead, Modifier.weight(1f), enabled = bitmap != null)
    }
    TextButton(onClick = onBack) { Text("Zurück", fontSize = 20.sp) }
}

@Composable
private fun CameraScreen(createFile: () -> File, onPhoto: (File) -> Unit, onBack: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val view = remember(context) { PreviewView(context) }
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var takingPhoto by remember { mutableStateOf(false) }
    var captureGeneration by remember { mutableLongStateOf(0L) }
    var canSwitch by remember { mutableStateOf(false) }
    val latestPhoto by rememberUpdatedState(onPhoto)
    val latestError by rememberUpdatedState(onError)
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(takingPhoto) {
        if (takingPhoto) {
            delay(20_000)
            captureGeneration++
            takingPhoto = false
            latestError("Die Kamera hat kein Foto zurückgegeben. Bitte versuchen Sie es erneut oder starten Sie die App neu.")
        }
    }
    DisposableEffect(lifecycle, lensFacing) {
        active = true
        ready = false
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                val provider = future.get()
                boundProvider = provider
                val back = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                canSwitch = back && front
                val desired = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val selector = if (provider.hasCamera(desired)) desired else when {
                    back -> CameraSelector.DEFAULT_BACK_CAMERA
                    front -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("No camera")
                }
                capture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                provider.bindToLifecycle(lifecycle, selector, preview, capture)
                ready = true
            } catch (e: Exception) {
                android.util.Log.w("Vorleser", "Camera initialization failed", e)
                latestError("Die Kamera konnte nicht geöffnet werden. Bitte prüfen Sie die Kameraberechtigung und schließen Sie andere Kamera-Apps.")
            }
        }, executor)
        onDispose {
            disposed = true
            active = false
            ready = false
            boundProvider?.unbind(preview, capture)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Eine Seite fotografieren", fontSize = 30.sp)
        Text("Alle vier Ecken ins Bild nehmen. Gut beleuchten und das Tablet ruhig halten.", fontSize = 22.sp)
        AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant))
        if (!ready) Text("Die Kamera wird geöffnet …", fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Zurück", onBack, Modifier.weight(1f), enabled = !takingPhoto)
            if (canSwitch) BigButton("Kamera wechseln", {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }, Modifier.weight(1f), enabled = ready && !takingPhoto)
            BigButton(if (takingPhoto) "Aufnahme …" else "Foto aufnehmen", {
                takingPhoto = true
                val currentCapture = ++captureGeneration
                capture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                val file = createFile()
                try {
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                if (active && currentCapture == captureGeneration) {
                                    takingPhoto = false
                                    latestPhoto(file)
                                } else file.delete()
                            }
                            override fun onError(e: ImageCaptureException) {
                                file.delete()
                                if (active && currentCapture == captureGeneration) {
                                    takingPhoto = false
                                    latestError("Das Foto konnte nicht aufgenommen werden. Bitte versuchen Sie es erneut.")
                                }
                            }
                        })
                } catch (e: Exception) {
                    takingPhoto = false
                    file.delete()
                    latestError("Das Foto konnte nicht aufgenommen werden. Bitte versuchen Sie es erneut.")
                }
            }, Modifier.weight(2f), enabled = ready && !takingPhoto)
        }
    }
}
