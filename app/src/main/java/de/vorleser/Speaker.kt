package de.vorleser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Uses an installed German offline voice; no document text is sent to a cloud speech service. */
class Speaker(private val context: Context) : ReaderSpeaker {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var closed = false
    private var initialization = 0L
    private val playback = SpeechPlayback(object : SpeechOutput {
        override val maxInputLength get() = TextToSpeech.getMaxSpeechInputLength()
        override fun stop() { tts?.stop() }
        override fun enqueue(text: String, id: String): Boolean =
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
    })
    override val state = playback.state

    init { initialize() }

    override fun initialize() {
        if (closed) return
        playback.beginInitialization()
        val currentInitialization = ++initialization
        tts?.shutdown()
        tts = null
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (closed || currentInitialization != initialization) return@post
                val engine = tts ?: return@post
                val voice = if (status == TextToSpeech.SUCCESS) engine.voices
                    ?.filter { it.locale.language == "de" && !it.isNetworkConnectionRequired }
                    ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == Locale.GERMANY }
                        .thenByDescending { it.quality })?.firstOrNull() else null
                val ready = voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS
                if (ready) engine.setSpeechRate(0.85f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) = Unit
                    override fun onDone(id: String) { main.post { if (!closed) playback.completed(id) } }
                    @Deprecated("Required by the Android interface")
                    override fun onError(id: String) { main.post { if (!closed) playback.failed(id) } }
                    override fun onError(id: String, errorCode: Int) { onError(id) }
                })
                playback.initialized(if (ready) null else
                    R.string.voice_missing)
            }
        }
    }

    override fun speak(text: String) = playback.speak(text)
    override fun stop() = playback.stop()
    override fun shutdown() {
        closed = true
        playback.stop()
        tts?.shutdown()
        tts = null
        main.removeCallbacksAndMessages(null)
    }
}
