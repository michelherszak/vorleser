package de.vorleser

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen
    data object Working : Screen
    data class Result(val text: String) : Screen
    data class Error(val message: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        setContent { MaterialTheme { App(speaker) } }
    }

    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 140.dp)) {
        Text(label, fontSize = 40.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun App(speaker: Speaker) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        screen = if (ok) Screen.Camera else Screen.Error("Ohne Erlaubnis für die Kamera kann ich keine Briefe fotografieren.")
    }

    fun analyze(photo: Bitmap) {
        screen = Screen.Working
        scope.launch {
            val key = prefs(ctx).getString("api_key", "").orEmpty()
            screen = try {
                if (key.isBlank()) error("kein Schlüssel")
                val text = withContext(Dispatchers.IO) { ClaudeClient.analyze(key, photo) }
                speaker.speak(text)
                Screen.Result(text)
            } catch (e: Exception) {
                Screen.Error("Das hat leider nicht geklappt. Bitte prüfen Sie das Internet und versuchen Sie es noch einmal.")
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when (val s = screen) {
                Screen.Home -> {
                    Text(
                        "Vorleser", fontSize = 56.sp,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { screen = Screen.Settings })
                    )
                    Spacer(Modifier.weight(1f))
                    BigButton("Brief fotografieren", { permission.launch(Manifest.permission.CAMERA) })
                    Spacer(Modifier.weight(1f))
                }
                Screen.Camera -> CameraScreen(onPhoto = ::analyze, onBack = { screen = Screen.Home })
                Screen.Working -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(96.dp))
                        Spacer(Modifier.height(24.dp))
                        Text("Ich lese den Brief …", fontSize = 40.sp)
                    }
                }
                is Screen.Result -> {
                    Text(s.text, fontSize = 32.sp, lineHeight = 44.sp,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        BigButton("Nochmal vorlesen", { speaker.speak(s.text) }, Modifier.weight(1f))
                        BigButton("Stopp", { speaker.stop() }, Modifier.weight(1f))
                    }
                    BigButton("Neuer Brief", { speaker.stop(); screen = Screen.Home })
                }
                is Screen.Error -> {
                    Spacer(Modifier.weight(1f))
                    Text(s.message, fontSize = 36.sp, lineHeight = 48.sp)
                    BigButton("Zurück", { screen = Screen.Home })
                    Spacer(Modifier.weight(1f))
                }
                Screen.Settings -> {
                    var key by remember { mutableStateOf(prefs(ctx).getString("api_key", "").orEmpty()) }
                    Text("Einstellungen (nur für die Familie)", fontSize = 28.sp)
                    OutlinedTextField(key, { key = it }, label = { Text("Anthropic-API-Schlüssel") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    BigButton("Speichern", { prefs(ctx).edit().putString("api_key", key.trim()).apply(); screen = Screen.Home })
                }
            }
        }
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("vorleser", Context.MODE_PRIVATE)

@Composable
private fun CameraScreen(onPhoto: (Bitmap) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val capture = remember { ImageCapture.Builder().build() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AndroidView(
            factory = { c -> PreviewView(c) },
            update = { view ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycle, selector, preview, capture)
                    } catch (_: Exception) {
                        // Fallback if selected camera is not available on device
                    }
                }, ContextCompat.getMainExecutor(ctx))
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("Zurück", onBack, Modifier.weight(1f))
            BigButton(
                if (lensFacing == CameraSelector.LENS_FACING_BACK) "Front" else "Rück",
                {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                Modifier.weight(1f)
            )
            BigButton("Foto aufnehmen", {
                capture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { onPhoto(image.toScaledBitmap()); image.close() }
                    override fun onError(e: ImageCaptureException) { onBack() }
                })
            }, Modifier.weight(2f))
        }
    }
}

/** JPEG aus der Kamera drehen und auf max. 1800 px Kantenlänge verkleinern. */
private fun ImageProxy.toScaledBitmap(): Bitmap {
    val bytes = planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val scale = minOf(1f, 1800f / maxOf(raw.width, raw.height))
    val m = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()); postScale(scale, scale) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}
