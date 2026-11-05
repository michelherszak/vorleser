package de.vorleser

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val directory get() = File(context.noBackupFilesDir, "reading-session")

    @Before fun resetSession() {
        directory.deleteRecursively()
        context.getSharedPreferences("vorleser", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText(text).fetchSemanticsNode() }.isSuccess }
    }

    private fun seedPhoto(): String {
        val name = "page-123abc.jpg"
        directory.mkdirs()
        val bitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("Brief an Frau Müller", 50f, 100f, Paint().apply { color = Color.BLACK; textSize = 40f })
        SessionStore(directory).photo(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return name
    }

    @Test fun savedPagesSurviveActivityRecreationAndAppRelaunch() {
        SessionStore(directory).save(ReadingSession(listOf("Erste Testseite", "Zweite Testseite")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Ihr Brief · 2 Seiten")
            compose.onNodeWithText("Seite 1\n\nErste Testseite\n\nSeite 2\n\nZweite Testseite").assertIsDisplayed()
            scenario.recreate()
            waitForText("Ihr Brief · 2 Seiten")
            compose.onNodeWithText("Weitere Seite").assertIsDisplayed()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithText("Vorlesen").assertIsDisplayed()
            compose.onNodeWithText("Weitere Seite").assertIsDisplayed()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Ihr Brief · 2 Seiten")
            compose.onNodeWithText("Seite 1\n\nErste Testseite\n\nSeite 2\n\nZweite Testseite").assertIsDisplayed()
        }
    }

    @Test fun reviewRetainsPhotoAfterMissingKeyAndDoesNotLoseEarlierPages() {
        val photo = seedPhoto()
        SessionStore(directory).save(ReadingSession(listOf("Bereits gelesene Seite"), photo))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Seite 2 prüfen")
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Seite einlesen").assertIsEnabled() }.isSuccess }
            compose.onNodeWithContentDescription("Vorschau der aufgenommenen Seite").assertIsDisplayed()
            compose.onNodeWithText("Seite einlesen").performClick()
            waitForText("Bitte lassen Sie zuerst den API-Schlüssel in den Einstellungen eintragen.")
            compose.onNodeWithText("Zurück").performClick()
            waitForText("Seite 2 prüfen")
            scenario.recreate()
            waitForText("Seite 2 prüfen")
            assertEquals(ReadingSession(listOf("Bereits gelesene Seite"), photo), SessionStore(directory).load())
        }
    }

    @Test fun newLetterRequiresConfirmationAndClearsTheSavedSession() {
        SessionStore(directory).save(ReadingSession(listOf("Privater Testbrief")))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Ihr Brief")
            compose.onNodeWithText("Neuer Brief").performClick()
            compose.onNodeWithText("Behalten").performClick()
            compose.onNodeWithText("Privater Testbrief").assertIsDisplayed()
            compose.onNodeWithText("Neuer Brief").performClick()
            compose.onNodeWithText("Der bisherige Brief und das gespeicherte Foto werden von diesem Gerät gelöscht.").assertIsDisplayed()
            compose.onNode(hasText("Neuer Brief") and hasAnyAncestor(isDialog())).performClick()
            waitForText("Brief fotografieren")
            assertEquals(ReadingSession(), SessionStore(directory).load())
        }
    }

    @Test fun cameraCaptureOpensReviewAndKeepsDraftWhenReturningHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Brief fotografieren")
            compose.onNodeWithText("Brief fotografieren").performClick()
            waitForText("Eine Seite fotografieren")
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Foto aufnehmen").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("Foto aufnehmen").performClick()
            waitForText("Seite 1 prüfen")
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Seite einlesen").assertIsEnabled() }.isSuccess }
            assertNotNull(SessionStore(directory).load().draftPhoto)
            compose.onNodeWithText("Zurück").performClick()
            waitForText("Letzten Brief öffnen")
            // A new activity still opens the pending review without capturing or uploading again.
            scenario.recreate()
            waitForText("Letzten Brief öffnen")
            compose.onNodeWithText("Letzten Brief öffnen").performClick()
            waitForText("Seite 1 prüfen")
        }
    }

    @Test fun cameraSwitchUsesAnotherAvailableCameraAndSurvivesRecreation() {
        val count = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS).availableCameraInfos.size
        assumeTrue("This test requires two Android cameras", count > 1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Brief fotografieren")
            compose.onNodeWithText("Brief fotografieren").performClick()
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Kamera wechseln").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("Kamera 1 von", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Kamera wechseln").performClick()
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Foto aufnehmen").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("Kamera 2 von", substring = true).assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithText("Foto aufnehmen").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("Kamera 2 von", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Foto aufnehmen").performClick()
            waitForText("Seite 1 prüfen")
            assertNotNull(SessionStore(directory).load().draftPhoto)
        }
    }
}
