package de.vorleser

import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackTest {
    private class Output : SpeechOutput {
        override val maxInputLength = 3000
        val queued = mutableListOf<Pair<String, String>>()
        var accept = true
        var stops = 0
        override fun stop() { stops++; queued.clear() }
        override fun enqueue(text: String, id: String): Boolean {
            if (!accept) return false
            queued += text to id
            return true
        }
    }

    @Test fun `long first paragraph and later paragraphs are all queued in order`() {
        val output = Output()
        val playback = SpeechPlayback(output)
        playback.initialized()
        val text = "Ein langer Absatz. ".repeat(500) + "\n\nZweiter Absatz mit 123,45 Euro."
        playback.speak(text)
        assertTrue(output.queued.size > 2)
        assertEquals(text, output.queued.joinToString("") { it.first })
        assertTrue(output.queued.all { it.first.length <= output.maxInputLength })
        assertEquals(output.queued.size, output.queued.map { it.second }.distinct().size)
        assertTrue(playback.state.value.speaking)
    }

    @Test fun `stop before engine initialization prevents delayed speech`() {
        val output = Output()
        val playback = SpeechPlayback(output)
        playback.speak("Dieser Text wurde gestoppt.")
        playback.stop()
        playback.initialized()
        assertTrue(output.queued.isEmpty())
        assertFalse(playback.state.value.speaking)
    }

    @Test fun `only the latest pending text is spoken on initialization`() {
        val output = Output()
        val playback = SpeechPlayback(output)
        playback.speak("Alt")
        playback.speak("Neu")
        playback.initialized()
        assertEquals(listOf("Neu"), output.queued.map { it.first })
    }

    @Test fun `old completion and error callbacks cannot stop a new reading`() {
        val output = Output()
        val playback = SpeechPlayback(output)
        playback.initialized()
        playback.speak("Alt")
        val oldId = output.queued.single().second
        playback.speak("Neu")
        playback.completed(oldId)
        playback.failed(oldId)
        assertTrue(playback.state.value.speaking)
        assertNull(playback.state.value.error)
        playback.completed(output.queued.single().second)
        assertFalse(playback.state.value.speaking)
    }

    @Test fun `missing voice reports an error and does not retain old pending text`() {
        val output = Output()
        val playback = SpeechPlayback(output)
        playback.speak("Vorheriger Brief")
        playback.initialized("Keine Stimme")
        assertEquals("Keine Stimme", playback.state.value.error)
        assertTrue(output.queued.isEmpty())
        playback.beginInitialization()
        playback.initialized()
        assertTrue(output.queued.isEmpty())
        playback.speak("Neuer Brief")
        assertEquals("Neuer Brief", output.queued.single().first)
    }

    @Test fun `engine enqueue failure clears playback and exposes error`() {
        val output = Output().apply { accept = false }
        val playback = SpeechPlayback(output)
        playback.initialized()
        playback.speak("Absatz ".repeat(1000))
        assertFalse(playback.state.value.speaking)
        assertNotNull(playback.state.value.error)
        assertTrue(output.queued.isEmpty())
    }

    @Test fun `chunk boundaries preserve unicode and do not split surrogate pairs`() {
        val text = "a".repeat(2999) + "\uD83D\uDE00" + "b".repeat(4000)
        val chunks = speechChunks(text, 3000)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 3000 })
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
}
