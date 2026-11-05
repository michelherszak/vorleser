package de.vorleser

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

interface ReadingStore {
    fun photo(name: String): File
    fun load(): ReadingSession
    /** A successful metadata commit can still leave obsolete photos to clean up. */
    fun save(session: ReadingSession): SaveResult
    fun cleanup(session: ReadingSession): Boolean
}

data class SaveResult(val cleanupPending: Boolean = false)

fun interface DocumentReader {
    suspend fun analyze(apiKey: String, photo: File): String
}

interface ReaderSpeaker {
    val state: StateFlow<SpeechState>
    fun initialize()
    fun speak(text: String)
    fun stop()
    fun shutdown()
}

interface ApiKeyStore {
    fun load(): String
    fun save(value: String): Boolean
}

interface CaptureFiles {
    fun create(): File
    fun prepare(source: File, destination: File)
    fun clearCaptures()
}

private class AndroidApiKeyStore(application: Application) : ApiKeyStore {
    private val prefs = application.getSharedPreferences("vorleser", Context.MODE_PRIVATE)
    override fun load(): String = prefs.getString("api_key", "").orEmpty()
    override fun save(value: String): Boolean = prefs.edit().putString("api_key", value).commit()
}

private class AndroidCaptureFiles(private val application: Application) : CaptureFiles {
    override fun create(): File = File(application.cacheDir, "capture-${UUID.randomUUID()}.jpg")
    override fun prepare(source: File, destination: File) = PhotoFiles.prepare(source, destination)
    override fun clearCaptures() {
        application.cacheDir.listFiles()?.filter { it.name.startsWith("capture-") }?.forEach { it.delete() }
    }
}

class ReaderViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return ReaderViewModel(
            store = SessionStore(File(application.noBackupFilesDir, "reading-session")),
            client = ClaudeClient(),
            speaker = Speaker(application),
            keys = AndroidApiKeyStore(application),
            captures = AndroidCaptureFiles(application),
        ) as T
    }
}
