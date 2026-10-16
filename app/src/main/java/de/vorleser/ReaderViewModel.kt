package de.vorleser

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Screen { HOME, CAMERA, REVIEW, WORKING, RESULT, ERROR, SETTINGS }
internal enum class ProblemKind { GENERAL, CAMERA }
internal data class ReaderProblem(
    @StringRes val message: Int,
    val recovery: Recovery? = null,
    val kind: ProblemKind = ProblemKind.GENERAL,
)
internal data class ReaderState(
    val screen: Screen = Screen.WORKING,
    val session: ReadingSession = ReadingSession(),
    val problem: ReaderProblem? = null,
    @StringRes val workLabel: Int = R.string.opening_letter,
    val workPage: Int = 0,
    val canCancel: Boolean = false,
    val unsavedChanges: Boolean = false,
    val cleanupPending: Boolean = false,
)

class ReaderViewModel(
    private val store: ReadingStore,
    private val client: DocumentReader,
    val speaker: ReaderSpeaker,
    private val keys: ApiKeyStore,
    private val captures: CaptureFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderState())
    internal val state = mutableState.asStateFlow()
    private var analysis: Job? = null

    init {
        viewModelScope.launch {
            try {
                val (session, clean) = withContext(ioDispatcher) {
                    captures.clearCaptures()
                    val session = store.load()
                    session to store.cleanup(session)
                }
                mutableState.value = ReaderState(screen = session.screen(), session = session, cleanupPending = !clean)
            } catch (e: IOException) {
                fail(R.string.session_load_failed)
            }
        }
    }

    fun apiKey(): String = keys.load()
    fun saveApiKey(value: String) {
        if (mutableState.value.screen == Screen.WORKING) return
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, workLabel = R.string.saving_key)
        viewModelScope.launch {
            if (!withContext(ioDispatcher) { keys.save(value.trim()) }) {
                fail(R.string.key_save_failed, Recovery.SETTINGS)
            } else {
                mutableState.value = mutableState.value.copy(screen = mutableState.value.session.screen(), problem = null)
            }
        }
    }

    fun photo(): File? = mutableState.value.session.draftPhoto?.let(store::photo)
    fun captureFile(): File = captures.create()

    internal fun camera() {
        if (mutableState.value.screen == Screen.WORKING) return
        if (mutableState.value.unsavedChanges) { resumeSession(); return }
        speaker.stop()
        mutableState.value = mutableState.value.copy(screen = Screen.CAMERA, problem = null)
    }
    internal fun settings() {
        if (mutableState.value.screen != Screen.WORKING) {
            mutableState.value = mutableState.value.copy(screen = Screen.SETTINGS)
        }
    }
    internal fun resumeSession() {
        if (mutableState.value.screen == Screen.WORKING) return
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
    internal fun cameraError(@StringRes message: Int) { fail(message, Recovery.RETAKE, ProblemKind.CAMERA) }

    fun captured(source: File) {
        if (mutableState.value.screen != Screen.CAMERA) { source.delete(); return }
        val previous = mutableState.value.session
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, workLabel = R.string.preparing_photo, canCancel = false)
        viewModelScope.launch {
            val name = "page-${UUID.randomUUID()}.jpg"
            val next = previous.copy(draftPhoto = name)
            try {
                val result = withContext(ioDispatcher) {
                    captures.prepare(source, store.photo(name))
                    store.save(next)
                }
                mutableState.value = ReaderState(screen = Screen.REVIEW, session = next, cleanupPending = result.cleanupPending)
            } catch (e: IOException) {
                store.photo(name).delete()
                fail(R.string.photo_save_failed, Recovery.RETAKE)
            } finally { source.delete() }
        }
    }

    fun analyze() {
        if (mutableState.value.screen == Screen.WORKING || analysis?.isActive == true || mutableState.value.unsavedChanges) return
        val photo = photo() ?: run { camera(); return }
        val key = apiKey()
        if (key.isBlank()) {
            fail(R.string.missing_key, Recovery.SETTINGS)
            return
        }
        val previous = mutableState.value.session
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, problem = null,
            workLabel = R.string.reading_page, workPage = previous.pages.size + 1, canCancel = true)
        analysis = viewModelScope.launch {
            try {
                val text = client.analyze(key, photo)
                ensureActive()
                mutableState.value = mutableState.value.copy(workLabel = R.string.saving_text, canCancel = false)
                val next = ReadingSession(pages = previous.pages + text)
                // Keep a recognized result available even if the local commit fails.
                val result = try {
                    withContext(NonCancellable + ioDispatcher) { store.save(next) }
                } catch (e: IOException) { null }
                mutableState.value = ReaderState(screen = Screen.RESULT, session = next,
                    unsavedChanges = result == null, cleanupPending = result?.cleanupPending ?: false)
                speaker.speak(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReadingException) {
                fail(e.userMessage, e.recovery)
            } catch (e: IOException) {
                fail(R.string.photo_read_failed, Recovery.RETAKE)
            } catch (e: Exception) {
                fail(R.string.read_failed, Recovery.RETRY)
            }
        }
    }

    fun retrySave() {
        val previous = mutableState.value
        if (!previous.unsavedChanges || previous.screen == Screen.WORKING) return
        mutableState.value = previous.copy(screen = Screen.WORKING, workLabel = R.string.saving_text, canCancel = false)
        viewModelScope.launch {
            val result = try {
                withContext(NonCancellable + ioDispatcher) { store.save(previous.session) }
            } catch (e: IOException) { null }
            mutableState.value = previous.copy(screen = Screen.RESULT, problem = null,
                unsavedChanges = result == null, cleanupPending = result?.cleanupPending ?: false)
        }
    }

    fun retryCleanup() {
        val previous = mutableState.value
        if (!previous.cleanupPending || previous.unsavedChanges || previous.screen == Screen.WORKING) return
        mutableState.value = previous.copy(screen = Screen.WORKING, workLabel = R.string.cleaning_photos, canCancel = false)
        viewModelScope.launch {
            val clean = withContext(ioDispatcher) { store.cleanup(previous.session) }
            mutableState.value = previous.copy(cleanupPending = !clean)
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
        mutableState.value = mutableState.value.copy(screen = Screen.WORKING, workLabel = R.string.closing_letter, canCancel = false)
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { store.save(ReadingSession()) }
                mutableState.value = ReaderState(screen = Screen.HOME, cleanupPending = result.cleanupPending)
            } catch (e: IOException) {
                fail(R.string.letter_clear_failed)
            }
        }
    }

    private fun fail(@StringRes message: Int, recovery: Recovery? = null, kind: ProblemKind = ProblemKind.GENERAL) {
        mutableState.value = mutableState.value.copy(screen = Screen.ERROR,
            problem = ReaderProblem(message, recovery, kind), canCancel = false)
    }

    override fun onCleared() { speaker.shutdown(); super.onCleared() }
}

private fun ReadingSession.screen(): Screen = when {
    draftPhoto != null -> Screen.REVIEW
    pages.isNotEmpty() -> Screen.RESULT
    else -> Screen.HOME
}
