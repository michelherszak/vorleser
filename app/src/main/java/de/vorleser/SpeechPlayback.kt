package de.vorleser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface SpeechOutput {
    val maxInputLength: Int
    fun stop()
    fun enqueue(text: String, id: String): Boolean
}

data class SpeechState(val initializing: Boolean = true, val speaking: Boolean = false, val error: String? = null)

/** Called on the main thread. Old engine callbacks cannot affect a newer reading. */
class SpeechPlayback(private val output: SpeechOutput) {
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    private var pending: String? = null
    private var generation = 0L
    private var lastId: String? = null

    fun beginInitialization() {
        stop()
        mutableState.value = SpeechState()
    }

    fun initialized(error: String? = null) {
        mutableState.value = SpeechState(initializing = false, error = error)
        val text = pending
        pending = null
        if (error == null && text != null) speak(text)
    }

    fun speak(text: String) {
        stop()
        if (mutableState.value.initializing) { pending = text; return }
        if (mutableState.value.error != null) return
        val chunks = speechChunks(text.trim(), output.maxInputLength)
        if (chunks.isEmpty()) return
        lastId = "$generation-${chunks.lastIndex}"
        mutableState.value = mutableState.value.copy(speaking = true)
        for ((index, chunk) in chunks.withIndex()) {
            // stop() has already cleared the old queue. Every chunk must be QUEUE_ADD.
            if (!output.enqueue(chunk, "$generation-$index")) {
                failed("$generation-$index")
                break
            }
        }
    }

    fun completed(id: String) {
        if (id == lastId) mutableState.value = mutableState.value.copy(speaking = false)
    }

    fun failed(id: String) {
        if (!id.startsWith("$generation-")) return
        stop()
        mutableState.value = SpeechState(initializing = false,
            error = "Die Sprachausgabe ist fehlgeschlagen. Bitte prüfen Sie die deutsche Offline-Stimme in den Spracheinstellungen.")
    }

    fun stop() {
        pending = null
        generation++
        lastId = null
        output.stop()
        mutableState.value = mutableState.value.copy(speaking = false)
    }
}

internal fun speechChunks(text: String, limit: Int): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val boundary = (end - 1 downTo start + limit / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
