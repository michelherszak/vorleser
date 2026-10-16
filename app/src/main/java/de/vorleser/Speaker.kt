package de.vorleser

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Deutsche Sprachausgabe über die eingebaute Android-TTS, etwas langsamer für ältere Hörer. */
class Speaker(context: Context) {
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = tts.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
            tts.setSpeechRate(0.85f)
            pending?.let { speak(it); pending = null }
        }
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.stop()
        // Lange Texte in Absätze teilen (TTS-Limit pro Aufruf)
        text.split(Regex("\n+")).filter { it.isNotBlank() }.forEachIndexed { i, p ->
            p.chunked(3000).forEach { tts.speak(it, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "p$i") }
        }
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
