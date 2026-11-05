package de.vorleser

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

data class ReadingSession(val pages: List<String> = emptyList(), val draftPhoto: String? = null) {
    val text: String get() = if (pages.size == 1) pages.first() else
        pages.mapIndexed { index, page -> "Seite ${index + 1}\n\n$page" }.joinToString("\n\n")
}

/** App-private, excluded from backups. Only the current letter and an optional retry photo. */
class SessionStore(
    private val directory: File,
    private val deletePhoto: (File) -> Boolean = { it.delete() },
) : ReadingStore {
    private val metadata get() = File(directory, "session.json")

    override fun photo(name: String): File {
        require(name.matches(Regex("page-[a-f0-9-]+\\.jpg"))) { "Invalid photo name" }
        return File(directory, name)
    }

    override fun load(): ReadingSession {
        if (!metadata.exists()) return ReadingSession()
        try {
            val json = JSONObject(metadata.readText())
            val pages = json.getJSONArray("pages").let { array -> List(array.length()) { array.getString(it) } }
            val draft = json.optString("draftPhoto").takeIf { it.isNotBlank() }
            return ReadingSession(pages, draft?.takeIf { photo(it).isFile })
        } catch (e: Exception) {
            throw IOException("Die gespeicherte Sitzung konnte nicht geladen werden.", e)
        }
    }

    override fun save(session: ReadingSession): SaveResult {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create session directory")
        session.draftPhoto?.let { if (!photo(it).isFile) throw IOException("Missing draft photo") }
        val json = JSONObject().put("pages", JSONArray(session.pages))
            .put("draftPhoto", session.draftPhoto.orEmpty()).toString()
        val temporary = File(directory, "session.tmp")
        try {
            temporary.outputStream().use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        // Cleanup must not turn an already committed save into a reported save failure.
        return SaveResult(cleanupPending = !cleanup(session))
    }

    override fun cleanup(session: ReadingSession): Boolean {
        try {
            if (!directory.exists()) return true
            val files = directory.listFiles() ?: return false
            var complete = true
            for (file in files.filter { it.name.startsWith("page-") && it.name != session.draftPhoto }) {
                val deleted = try { deletePhoto(file) } catch (_: SecurityException) { false }
                if (!deleted && file.exists()) complete = false
            }
            return complete
        } catch (_: SecurityException) {
            return false
        }
    }
}
