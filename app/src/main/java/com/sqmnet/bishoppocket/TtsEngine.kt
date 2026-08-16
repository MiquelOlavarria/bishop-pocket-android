package com.sqmnet.bishoppocket

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TTS nativo (es-ES) + tonos de aviso. */
class TtsEngine(ctx: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("es", "ES"))
                ready = res != TextToSpeech.LANG_MISSING_DATA &&
                    res != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /** Lee el texto en voz alta (bloqueante hasta terminar). Espera a que el TTS esté listo. */
    suspend fun speak(text: String) = withContext(Dispatchers.Main) {
        val t = tts ?: return@withContext
        // esperar a que el motor esté listo (init asíncrono), máx 3s
        var waited = 0
        while (!ready && waited < 3000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        if (!ready) return@withContext
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pocket")
        // esperar a que termine para no solapar
        var done = false
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done = true }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done = true }
        })
        while (!done) {
            kotlinx.coroutines.delay(100)
        }
    }

    /** Doble tono corto (fin de respuesta). */
    fun beep() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        Thread.sleep(90)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
    }

    fun shutdown() {
        tts?.shutdown()
        tone.release()
    }
}
