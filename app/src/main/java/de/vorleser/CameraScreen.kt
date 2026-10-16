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
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.delay

@Composable
internal fun CameraScreen(createFile: () -> File, onPhoto: (File) -> Unit, onBack: () -> Unit, onError: (Int) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var cameraIndex by rememberSaveable { mutableIntStateOf(0) }
    val view = remember(context, cameraIndex) { PreviewView(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var ready by remember { mutableStateOf(false) }
    var takingPhoto by remember { mutableStateOf(false) }
    var captureGeneration by remember { mutableLongStateOf(0L) }
    var cameraCount by remember { mutableIntStateOf(0) }
    var activeCameraIndex by remember { mutableIntStateOf(0) }
    var activeCameraFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_UNKNOWN) }
    var bound by remember { mutableStateOf(false) }
    var noFrames by remember { mutableStateOf(false) }
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
    LaunchedEffect(cameraIndex, ready) {
        noFrames = false
        if (!ready) {
            delay(15_000)
            noFrames = true
        }
    }
    DisposableEffect(lifecycle, cameraIndex) {
        active = true
        ready = false
        bound = false
        val streamObserver = Observer<PreviewView.StreamState> { stream ->
            ready = stream == PreviewView.StreamState.STREAMING
        }
        view.previewStreamState.observe(lifecycle, streamObserver)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                val provider = future.get()
                boundProvider = provider
                // Do not discard cameras without a front/back orientation (for example USB cameras).
                val cameras = provider.availableCameraInfos.sortedBy { info ->
                    when (info.lensFacing) {
                        CameraSelector.LENS_FACING_BACK -> 0
                        CameraSelector.LENS_FACING_FRONT -> 1
                        else -> 2
                    }
                }
                val selected = cameras.getOrNull(cameraIndex) ?: cameras.firstOrNull()
                    ?: error("No camera")
                cameraCount = cameras.size
                activeCameraIndex = cameras.indexOf(selected)
                activeCameraFacing = selected.lensFacing
                val selector = selected.cameraSelector
                capture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                provider.bindToLifecycle(lifecycle, selector, preview, capture)
                bound = true
            } catch (e: Exception) {
                android.util.Log.w("Vorleser", "Camera initialization failed", e)
                latestError(R.string.camera_open_failed)
            }
        }, executor)
        onDispose {
            disposed = true
            active = false
            ready = false
            bound = false
            view.previewStreamState.removeObserver(streamObserver)
            boundProvider?.unbind(preview, capture)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.camera_title), fontSize = 30.sp)
        Text(stringResource(R.string.camera_hint), fontSize = 22.sp)
        key(cameraIndex) {
            AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant))
        }
        if (cameraCount > 0) {
            val facing = when (activeCameraFacing) {
                CameraSelector.LENS_FACING_BACK -> R.string.camera_back
                CameraSelector.LENS_FACING_FRONT -> R.string.camera_front
                else -> R.string.camera_other
            }
            Text(stringResource(R.string.camera_status, activeCameraIndex + 1, cameraCount, stringResource(facing)), fontSize = 20.sp)
        }
        if (!ready) Text(stringResource(if (noFrames) R.string.camera_no_frames else R.string.camera_opening), fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(stringResource(R.string.back), onBack, Modifier.weight(1f), enabled = !takingPhoto)
            if (cameraCount > 1) BigButton(stringResource(R.string.switch_camera), {
                cameraIndex = (activeCameraIndex + 1) % cameraCount
            }, Modifier.weight(1f), enabled = bound && !takingPhoto)
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
