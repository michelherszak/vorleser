package de.vorleser

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReaderRecoveryTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val models = ViewModelStore()
    @Volatile private var failWrites = false
    @Volatile private var allowDeletion = true
    private val uploads = AtomicInteger()
    private lateinit var disk: SessionStore

    @Before fun setup() {
        disk = SessionStore(temporary.newFolder("session")) { file -> allowDeletion && file.delete() }
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        disk.photo("page-aaa.jpg").outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        image.recycle()
        disk.save(ReadingSession(listOf("Erste Seite"), "page-aaa.jpg"))
        val store = object : ReadingStore by disk {
            override fun save(session: ReadingSession): SaveResult {
                if (failWrites) throw IOException("Disk full")
                return disk.save(session)
            }
        }
        val speaker = object : ReaderSpeaker {
            override val state = MutableStateFlow(SpeechState(initializing = false))
            override fun initialize() = Unit
            override fun speak(text: String) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val keys = object : ApiKeyStore {
            override fun load() = "test-key"
            override fun save(value: String) = true
        }
        val captures = object : CaptureFiles {
            override fun create(): File = error("Camera is not used in this test")
            override fun prepare(source: File, destination: File) = error("Camera is not used in this test")
            override fun clearCaptures() = Unit
        }
        lateinit var model: ReaderViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = ReaderViewModel(store, DocumentReader { _, _ -> uploads.incrementAndGet(); "Zweite Seite" }, speaker, keys, captures)
            models.put("reader", model)
        }
        compose.setContent { MaterialTheme { ReaderApp(model) } }
        waitForText("Seite 2 prüfen")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Seite einlesen").assertIsEnabled() }.isSuccess }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText(text).fetchSemanticsNode() }.isSuccess }
    }

    @Test fun retrySaveButtonPersistsTextWithoutAnotherUpload() {
        failWrites = true
        compose.onNodeWithText("Seite einlesen").performClick()
        waitForText("Speichern erneut versuchen")
        compose.onNodeWithText("Weitere Seite").assertIsNotEnabled()
        compose.onNodeWithText("Speichern erneut versuchen").performClick()
        waitForText("Speichern erneut versuchen")
        assertEquals(1, uploads.get())
        failWrites = false
        compose.onNodeWithText("Speichern erneut versuchen").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Weitere Seite").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("Speichern erneut versuchen").assertDoesNotExist()
        compose.onNodeWithText("Weitere Seite").assertIsEnabled()
        assertEquals(listOf("Erste Seite", "Zweite Seite"), disk.load().pages)
        assertEquals(1, uploads.get())
    }

    @Test fun newLetterOffersCleanupWhenPhotoDeletionFails() {
        allowDeletion = false
        compose.onNodeWithText("Zurück").performClick()
        waitForText("Ihr Brief")
        compose.onNodeWithText("Neuer Brief").performClick()
        compose.onNode(hasText("Neuer Brief") and hasAnyAncestor(isDialog())).performClick()
        waitForText("Fotos erneut löschen")
        compose.onNodeWithText("Fotos erneut löschen").assertIsDisplayed()
        assertEquals(ReadingSession(), disk.load())
        assertTrue(disk.photo("page-aaa.jpg").exists())
        allowDeletion = true
        compose.onNodeWithText("Fotos erneut löschen").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Fotos erneut löschen").assertDoesNotExist() }.isSuccess }
        compose.onNodeWithText("Brief fotografieren").assertIsDisplayed()
        compose.onNodeWithText("Fotos erneut löschen").assertDoesNotExist()
        assertEquals(0, uploads.get())
    }
}
