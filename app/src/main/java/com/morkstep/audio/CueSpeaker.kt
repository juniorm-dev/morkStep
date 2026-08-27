package com.morkstep.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Plays interval audio cues: transition beeps and spoken guidance. */
class CueSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val tts: TextToSpeech = TextToSpeech(appContext, this)
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.US
    }

    fun beep() {
        if (!ready) return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
    }

    fun doubleBeep() {
        if (!ready) return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
    }

    /** Speak a guidance cue. Fail-silent if TTS not yet ready. */
    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        tone.release()
    }
}