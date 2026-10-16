package de.vorleser

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class Screen { HOME, CAMERA, REVIEW, WORKING, RESULT, ERROR, SETTINGS }
internal data class ReaderProblem(val message: String, val recovery: Recovery? = null)
internal data class ReaderState(
    val screen: Screen = Screen.WORKING,
    val session: ReadingSession = ReadingSession(),
    val problem: ReaderProblem? = null,
    val workLabel: String = "Ich öffne den letzten Brief …",
    val canCancel: Boolean = false,
    val notice: String? = null,
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(File(application.noBackupFilesDir, "reading-session"))
    private val client = ClaudeClient()
    val speaker = Speaker(application)
    private val mutableState = MutableStateFlow(ReaderState())
    internal val state = mutableState.asStateFlow()
    private var analysis: Job? = null
    private val prefs = application.getSharedPreferences("vorleser", Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    application.cacheDir.listFiles()?.filter { it.name.startsWith("capture-") }?.forEach { it.delete() }
                    store.load()
                }
                mutableState.value = ReaderState(screen = session.screen(), session = session)
            } catch (e: IOException) {
                fail("Der letzte Brief konnte nicht geöffnet werden. Sie können mit „Neuer Brief“ eine neue Sitzung beginnen.")
            }
        }
    }

    fun apiKey(): String = prefs.getString("api_key", "").orEmpty()
    fun saveApiKey(value: String) {
        if (!prefs.edit().putString("api_key", value.trim()).commit()) {
            fail("Der API-Schlüssel konnte nicht gespeichert werden. Bitte prüfen Sie den freien Speicherplatz.", Recovery.SETTINGS)
            return
        }
        back()
    }

    fun photo(): File? = mutableState.value.session.draftPhoto?.let(store::photo)
    fun captureFile(): File = File(getApplication<Application>().cacheDir, "capture-${UUID.randomUUID()}.jpg")

    internal fun camera() {
        if (mutableState.value.screen == Screen.WORKING) return
        speaker.stop()
        mutableState.value = mutableState.value.copy(screen = Screen.CAMERA, problem = null)
    }
    internal fun settings() { mutableState.value = mutableState.value.copy(screen = Screen.SETTINGS) }
    internal fun resumeSession() {
        mutableState.value = mutableState.value.copy(screen = mutableState.value.session.screen(), problem = null)
    }
    internal fun back() {
        if (mutableState.value.screen == Screen.WORKING) return
        val state = mutableState.value
        val screen = when (state.screen) {
            Screen.RESULT -> Screen.HOME
            Screen.REVIEW -> if (state.session.pages.isNotEmpty()) Screen.RESULT else Screen.HOME
            else -> state.session.screen()
        }
        mutableState.value = state.copy(screen = screen, problem = null)
    }
    internal fun cameraError(message: String) { fail(message, Recovery.RETAKE) }

    fun captured(source: File) {
        if (mutableState.value.screen != Screen.CAMERA) { source.delete(); return }
        val previous = mutableState.value.session
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, workLabel = "Ich bereite das Foto vor …", canCancel = false)
        viewModelScope.launch {
            val name = "page-${UUID.randomUUID()}.jpg"
            val next = previous.copy(draftPhoto = name)
            try {
                withContext(Dispatchers.IO) {
                    PhotoFiles.prepare(source, store.photo(name))
                    store.save(next)
                }
                mutableState.value = ReaderState(screen = Screen.REVIEW, session = next)
            } catch (e: IOException) {
                store.photo(name).delete()
                fail("Das Foto konnte nicht gespeichert werden. Bitte prüfen Sie den freien Speicherplatz und fotografieren Sie die Seite erneut.", Recovery.RETAKE)
            } finally { source.delete() }
        }
    }

    fun analyze() {
        if (mutableState.value.screen == Screen.WORKING || analysis?.isActive == true) return
        val photo = photo() ?: run { camera(); return }
        val key = apiKey()
        if (key.isBlank()) {
            fail("Bitte lassen Sie zuerst den API-Schlüssel in den Einstellungen eintragen.", Recovery.SETTINGS)
            return
        }
        val previous = mutableState.value.session
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, problem = null,
            workLabel = "Ich lese Seite ${previous.pages.size + 1} …", canCancel = true)
        analysis = viewModelScope.launch {
            try {
                val text = client.analyze(key, photo)
                ensureActive()
                // Once a result exists, finish its atomic save before allowing another operation.
                analysis = null
                mutableState.value = mutableState.value.copy(workLabel = "Ich sichere den Text …", canCancel = false)
                val next = ReadingSession(pages = previous.pages + text)
                val notice = try {
                    withContext(NonCancellable + Dispatchers.IO) { store.save(next) }
                    null
                } catch (e: IOException) {
                    "Der Text ist nur bis zum Schließen der App verfügbar, weil er nicht gespeichert werden konnte. Bitte prüfen Sie den freien Speicherplatz."
                }
                mutableState.value = ReaderState(screen = Screen.RESULT, session = next, notice = notice)
                speaker.speak(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReadingException) {
                fail(e.userMessage, e.recovery)
            } catch (e: IOException) {
                fail("Das gespeicherte Foto konnte nicht gelesen werden. Bitte fotografieren Sie die Seite erneut.", Recovery.RETAKE)
            } catch (e: Exception) {
                fail("Die Seite konnte nicht gelesen werden. Ihr Foto bleibt für einen erneuten Versuch erhalten.", Recovery.RETRY)
            }
        }
    }

    fun cancelAnalysis() {
        if (!mutableState.value.canCancel) return
        analysis?.cancel()
        analysis = null
        mutableState.value = mutableState.value.copy(screen = Screen.REVIEW, canCancel = false)
    }

    fun newLetter() {
        if (mutableState.value.screen == Screen.WORKING) return
        speaker.stop()
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, workLabel = "Ich schließe den Brief …", canCancel = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(ReadingSession()) }
                mutableState.value = ReaderState(screen = Screen.HOME)
            } catch (e: IOException) {
                fail("Der Brief konnte nicht gelöscht werden. Bitte prüfen Sie den freien Speicherplatz und versuchen Sie „Neuer Brief“ erneut.")
            }
        }
    }

    private fun fail(message: String, recovery: Recovery? = null) {
        mutableState.value = mutableState.value.copy(screen = Screen.ERROR, problem = ReaderProblem(message, recovery), canCancel = false)
    }

    override fun onCleared() { speaker.shutdown(); super.onCleared() }
}

private fun ReadingSession.screen(): Screen = when {
    draftPhoto != null -> Screen.REVIEW
    pages.isNotEmpty() -> Screen.RESULT
    else -> Screen.HOME
}
