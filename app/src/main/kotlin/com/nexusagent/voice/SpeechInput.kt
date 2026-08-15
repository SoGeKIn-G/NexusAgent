package com.nexusagent.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * One utterance from the microphone.
 *
 * Modelled as a stream rather than a suspend function returning a string, because the
 * interesting part is what happens *during* the utterance: partial transcripts to show
 * the user their words landing, and amplitude to drive the orb. A single return value
 * would throw both away.
 */
sealed interface SpeechEvent {
    data object Listening : SpeechEvent

    /** Normalised 0..1 microphone level, for the waveform. */
    data class Amplitude(val level: Float) : SpeechEvent

    /** Interim guess. Changes as the recogniser revises itself; never final. */
    data class Partial(val text: String) : SpeechEvent

    data class Final(val text: String) : SpeechEvent

    data class Failed(val message: String, val recoverable: Boolean) : SpeechEvent
}

/**
 * Wraps Android's [SpeechRecognizer] as a Flow.
 *
 * Prefers on-device recognition ([RecognizerIntent.EXTRA_PREFER_OFFLINE]): it works on a
 * plane, adds no network latency to the front of every task, and keeps spoken commands
 * off Google's servers - which matters for an app whose whole job is reading your screen.
 * The system silently falls back to network recognition when no offline model is
 * installed for the locale.
 */
class SpeechInput(private val context: Context) {

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(SpeechEvent.Failed("Speech recognition isn't available on this device.", false))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB is roughly -2..10 in practice, not a true dB scale. Normalised
                // here rather than in the UI so the orb doesn't have to know that.
                trySend(SpeechEvent.Amplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstTranscript()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstTranscript()
                if (text.isNullOrBlank()) {
                    trySend(SpeechEvent.Failed("Didn't catch that.", recoverable = true))
                } else {
                    trySend(SpeechEvent.Final(text))
                }
                close()
            }

            override fun onError(error: Int) {
                val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                trySend(SpeechEvent.Failed(describe(error), recoverable))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onEndOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(buildIntent())

        awaitClose {
            // Both, in this order: cancel abandons the in-flight utterance, destroy frees
            // the binder to the recognition service. Skipping destroy leaks a service
            // connection per utterance, which the system eventually complains about.
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Not a hard requirement - the system falls back to network recognition when no
        // offline model is installed for the current locale.
        if (Build.VERSION.SDK_INT >= 23) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun Bundle?.firstTranscript(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognition client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK -> "Network error, and no offline model is installed."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Recognition timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser is busy."
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
        else -> {
            Log.w("NexusVoice", "Unmapped recognition error $error")
            "Speech recognition failed."
        }
    }
}
