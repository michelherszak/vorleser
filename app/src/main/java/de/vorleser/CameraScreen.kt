package de.vorleser

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.delay

@Composable
internal fun CameraScreen(createFile: () -> File, onPhoto: (File) -> Unit, onBack: () -> Unit, onError: (Int) -> Unit) {
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
            latestError(R.string.camera_timeout)
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
                latestError(R.string.camera_open_failed)
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
        Text(stringResource(R.string.camera_title), fontSize = 30.sp)
        Text(stringResource(R.string.camera_hint), fontSize = 22.sp)
        AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant))
        if (!ready) Text(stringResource(R.string.camera_opening), fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(stringResource(R.string.back), onBack, Modifier.weight(1f), enabled = !takingPhoto)
            if (canSwitch) BigButton(stringResource(R.string.switch_camera), {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }, Modifier.weight(1f), enabled = ready && !takingPhoto)
            BigButton(if (takingPhoto) stringResource(R.string.camera_capturing) else stringResource(R.string.take_photo), {
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
                                    latestError(R.string.camera_capture_failed)
                                }
                            }
                        })
                } catch (e: Exception) {
                    takingPhoto = false
                    file.delete()
                    latestError(R.string.camera_capture_failed)
                }
            }, Modifier.weight(2f), enabled = ready && !takingPhoto)
        }
    }
}
