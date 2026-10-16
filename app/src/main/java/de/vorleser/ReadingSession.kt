package de.vorleser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ReadingSession(val pages: List<String> = emptyList(), val draftPhoto: String? = null) {
    val text: String get() = if (pages.size == 1) pages.first() else
        pages.mapIndexed { index, page -> "Seite ${index + 1}\n\n$page" }.joinToString("\n\n")
}

/** App-private, excluded from backups. Only the current letter and an optional retry photo. */
class SessionStore(private val directory: File) {
    private val metadata get() = File(directory, "session.json")

    fun photo(name: String): File {
        require(name.matches(Regex("page-[a-f0-9-]+\\.jpg"))) { "Invalid photo name" }
        return File(directory, name)
    }

    fun load(): ReadingSession {
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

    fun save(session: ReadingSession) {
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
        // A failed save must never delete the last successfully saved draft.
        directory.listFiles()?.filter { it.name.startsWith("page-") && it.name != session.draftPhoto }
            ?.forEach { it.delete() }
    }
}
