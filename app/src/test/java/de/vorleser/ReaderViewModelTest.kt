package de.vorleser

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var modelCount = 0
    private var allowDeletion = true
    private lateinit var disk: SessionStore
    private lateinit var store: FailingStore
    private val speaker = TestSpeaker()
    private var readCalls = 0
    private var read: suspend () -> String = { "Zweite Seite" }
    private val saved = ReadingSession(listOf("Erste Seite"), "page-aaa.jpg")

    private class FailingStore(private val disk: ReadingStore) : ReadingStore by disk {
        var failWrites = false
        override fun save(session: ReadingSession): SaveResult {
            if (failWrites) throw IOException("Disk full")
            return disk.save(session)
        }
    }

    private class TestSpeaker : ReaderSpeaker {
        override val state = MutableStateFlow(SpeechState(initializing = false))
        val spoken = mutableListOf<String>()
        var closed = false
        override fun initialize() = Unit
        override fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun shutdown() { closed = true }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        disk = SessionStore(temporary.newFolder("session")) { file -> allowDeletion && file.delete() }
        disk.photo(saved.draftPhoto!!).writeText("JPEG fixture")
        disk.save(saved)
        store = FailingStore(disk)
    }

    @After fun cleanup() {
        models.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(): ReaderViewModel {
        val model = ReaderViewModel(store, DocumentReader { _, _ -> readCalls++; read() }, speaker,
            object : ApiKeyStore {
                override fun load() = "test-key"
                override fun save(value: String) = true
            }, object : CaptureFiles {
                override fun create() = File(temporary.root, "capture.jpg")
                override fun prepare(source: File, destination: File) { source.copyTo(destination, overwrite = true) }
                override fun clearCaptures() = Unit
            }, dispatcher)
        models.put("reader-${modelCount++}", model)
        return model
    }

    @Test fun `successful read appends persists removes photo and speaks only the new page`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.analyze()
        runCurrent()
        assertEquals(ReadingSession(listOf("Erste Seite", "Zweite Seite")), disk.load())
        assertFalse(disk.photo(saved.draftPhoto!!).exists())
        assertEquals(disk.load(), model.state.value.session)
        assertEquals(Screen.RESULT, model.state.value.screen)
        assertFalse(model.state.value.unsavedChanges)
        assertEquals(listOf("Zweite Seite"), speaker.spoken)
        assertEquals(1, readCalls)
    }

    @Test fun `failed commit can be retried locally without another upload or duplicate page`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        store.failWrites = true
        model.analyze()
        runCurrent()
        assertEquals(saved, disk.load())
        assertTrue(model.state.value.unsavedChanges)
        assertEquals(listOf("Erste Seite", "Zweite Seite"), model.state.value.session.pages)
        // Pending text cannot be replaced by taking or analyzing another page.
        model.camera()
        model.analyze()
        assertEquals(Screen.RESULT, model.state.value.screen)
        model.retrySave()
        runCurrent()
        assertTrue(model.state.value.unsavedChanges)
        store.failWrites = false
        model.retrySave()
        model.retrySave()
        runCurrent()
        assertFalse(model.state.value.unsavedChanges)
        assertEquals(ReadingSession(listOf("Erste Seite", "Zweite Seite")), disk.load())
        assertEquals(1, readCalls)
        assertEquals(listOf("Zweite Seite"), speaker.spoken)
    }

    @Test fun `cleanup failure after a successful read is not treated as an unsaved page`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        allowDeletion = false
        model.analyze()
        runCurrent()
        assertFalse(model.state.value.unsavedChanges)
        assertTrue(model.state.value.cleanupPending)
        assertEquals(model.state.value.session, disk.load())
        assertTrue(disk.photo(saved.draftPhoto!!).exists())
        allowDeletion = true
        model.retryCleanup()
        runCurrent()
        assertFalse(model.state.value.cleanupPending)
        assertFalse(disk.photo(saved.draftPhoto!!).exists())
        assertEquals(1, readCalls)
    }

    @Test fun `new letter reports leftover photos and startup retries cleanup`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        allowDeletion = false
        model.newLetter()
        runCurrent()
        assertEquals(Screen.HOME, model.state.value.screen)
        assertEquals(ReadingSession(), disk.load())
        assertTrue(model.state.value.cleanupPending)
        assertTrue(disk.photo(saved.draftPhoto!!).exists())
        models.clear()
        allowDeletion = true
        val reopened = model()
        runCurrent()
        assertEquals(Screen.HOME, reopened.state.value.screen)
        assertFalse(reopened.state.value.cleanupPending)
        assertFalse(disk.photo(saved.draftPhoto!!).exists())
        assertEquals(0, readCalls)
    }

    @Test fun `startup cleanup preserves the current retry photo`() = runTest(dispatcher) {
        val obsolete = disk.photo("page-bbb.jpg").apply { writeText("obsolete") }
        allowDeletion = false
        val model = model()
        runCurrent()
        assertTrue(model.state.value.cleanupPending)
        assertEquals(saved, model.state.value.session)
        allowDeletion = true
        model.retryCleanup()
        runCurrent()
        assertFalse(obsolete.exists())
        assertTrue(disk.photo(saved.draftPhoto!!).exists())
        assertEquals(saved, disk.load())
    }

    @Test fun `cancel and immediate retry ignore the old request finishing later`() = runTest(dispatcher) {
        val finishOldCancellation = CompletableDeferred<Unit>()
        read = {
            if (readCalls == 1) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { finishOldCancellation.await() } }
            } else "Zweite Seite"
        }
        val model = model()
        runCurrent()
        model.analyze()
        runCurrent()
        model.cancelAnalysis()
        assertEquals(Screen.REVIEW, model.state.value.screen)
        assertEquals(saved, disk.load())
        model.analyze()
        runCurrent()
        assertEquals(Screen.RESULT, model.state.value.screen)
        finishOldCancellation.complete(Unit)
        runCurrent()
        assertEquals(Screen.RESULT, model.state.value.screen)
        assertNull(model.state.value.problem)
        assertEquals(ReadingSession(listOf("Erste Seite", "Zweite Seite")), disk.load())
        assertEquals(listOf("Zweite Seite"), speaker.spoken)
        assertEquals(2, readCalls)
    }

    @Test fun `camera recovery uses a problem kind independent of its text`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.cameraError(R.string.read_failed)
        assertEquals(ProblemKind.CAMERA, model.state.value.problem?.kind)
        assertEquals(Recovery.RETAKE, model.state.value.problem?.recovery)
    }

    @Test fun `failed new letter does not replace the recoverable session`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        store.failWrites = true
        model.newLetter()
        runCurrent()
        assertEquals(Screen.ERROR, model.state.value.screen)
        assertEquals(saved, model.state.value.session)
        assertEquals(saved, disk.load())
        assertTrue(disk.photo(saved.draftPhoto!!).exists())
    }

    @Test fun `corrupt metadata does not trigger cleanup of recoverable photos`() = runTest(dispatcher) {
        File(disk.photo(saved.draftPhoto!!).parentFile, "session.json").writeText("broken")
        val model = model()
        runCurrent()
        assertEquals(Screen.ERROR, model.state.value.screen)
        assertEquals(R.string.session_load_failed, model.state.value.problem?.message)
        assertTrue(disk.photo(saved.draftPhoto!!).exists())
        assertEquals(0, readCalls)
    }
}
