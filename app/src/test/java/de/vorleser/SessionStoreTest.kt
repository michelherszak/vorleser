package de.vorleser

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = File(temporary.root, "session")

    @Test fun `a fresh store restores pages and retry photo after process restart`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        store.photo("page-a123.jpg").writeText("photo")
        val session = ReadingSession(listOf("Seite eins: 123,45 €", "Seite zwei\nmit Absätzen"), "page-a123.jpg")
        store.save(session)
        assertEquals(session, SessionStore(directory()).load())
    }

    @Test fun `successful next page preserves order and removes the retry photo`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        val photo = store.photo("page-123.jpg").apply { writeText("photo") }
        store.save(ReadingSession(listOf("Erste Seite"), photo.name))
        val next = store.load().let { ReadingSession(it.pages + "Zweite Seite") }
        store.save(next)
        assertEquals(listOf("Erste Seite", "Zweite Seite"), SessionStore(directory()).load().pages)
        assertFalse(photo.exists())
        assertEquals("Seite 1\n\nErste Seite\n\nSeite 2\n\nZweite Seite", next.text)
    }

    @Test fun `a missing new photo cannot overwrite the recoverable session`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        store.photo("page-aaa.jpg").writeText("photo")
        val saved = ReadingSession(listOf("Brief"), "page-aaa.jpg")
        store.save(saved)
        assertThrows(IOException::class.java) { store.save(saved.copy(draftPhoto = "page-bbb.jpg")) }
        assertEquals(saved, SessionStore(directory()).load())
        assertTrue(store.photo("page-aaa.jpg").exists())
    }

    @Test fun `retake only replaces draft and preserves already read pages`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        store.photo("page-aaa.jpg").writeText("old")
        store.save(ReadingSession(listOf("Erste Seite"), "page-aaa.jpg"))
        store.photo("page-bbb.jpg").writeText("new")
        store.save(store.load().copy(draftPhoto = "page-bbb.jpg"))
        assertFalse(store.photo("page-aaa.jpg").exists())
        assertEquals(ReadingSession(listOf("Erste Seite"), "page-bbb.jpg"), SessionStore(directory()).load())
    }

    @Test fun `new letter removes saved text and photos`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        store.photo("page-123.jpg").writeText("photo")
        store.save(ReadingSession(listOf("Private Daten"), "page-123.jpg"))
        store.save(ReadingSession())
        assertEquals(ReadingSession(), SessionStore(directory()).load())
        assertFalse(store.photo("page-123.jpg").exists())
        assertFalse(File(directory(), "session.json").readText().contains("Private Daten"))
    }

    @Test fun `corrupt metadata is reported instead of silently replacing a letter`() {
        directory().mkdirs()
        File(directory(), "session.json").writeText("broken")
        assertThrows(IOException::class.java) { SessionStore(directory()).load() }
    }

    @Test fun `missing retry photo still preserves previously read pages`() {
        val store = SessionStore(directory())
        directory().mkdirs()
        store.photo("page-123.jpg").writeText("photo")
        store.save(ReadingSession(listOf("Brief"), "page-123.jpg"))
        store.photo("page-123.jpg").delete()
        assertEquals(ReadingSession(listOf("Brief")), SessionStore(directory()).load())
    }

    @Test fun `photo names cannot escape the session directory`() {
        assertThrows(IllegalArgumentException::class.java) { SessionStore(directory()).photo("../secret") }
    }
}
