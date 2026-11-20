package app.benchjot.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Dictation with the phone's own speech recognition (android.speech; the web's use-speech.ts):
 * no account, no credits, nothing to connect. The words come in as they are heard ([onHeard]);
 * [finish] stops listening and hands them over ([onDone]), [cancel] throws them away. It also ends by
 * itself when the phone stops hearing speech. The microphone is on only while listening. The phone's
 * speech service does the work (Google's on most phones; on the phone itself when the service can).
 * Use from the main thread.
 */
class Speech(private val context: Context, private val onHeard: (String) -> Unit, private val onDone: (text: String, problem: String?) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var heard = ""

    val listening get() = recognizer != null

    /** Starts listening; a message when this phone can't. */
    fun start(): String? {
        if (recognizer != null) return null
        val r = try { create() } catch (e: Exception) { null } ?: return NO_RECOGNIZER
        heard = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle) { words(partial)?.let { heard = it; onHeard(it) } }
            override fun onResults(results: Bundle) = end(words(results) ?: heard, null)
            override fun onError(error: Int) = end(heard, problem(error))
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer = r
        return try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // A longer pause before it decides you're done; not every service honours it.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            })
            null
        } catch (e: Exception) {
            release()
            CANT_LISTEN
        }
    }

    /** Stops listening; what was heard is handed over. */
    fun finish() { recognizer?.stopListening() }

    /** Stops listening and throws the words away. */
    fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun end(text: String, problem: String?) {
        if (recognizer == null) return // cancelled
        release()
        val words = text.trim()
        if (words.isNotEmpty()) onDone(words, null) else onDone("", problem ?: NO_SPEECH)
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        if (SpeechRecognizer.isRecognitionAvailable(context)) return SpeechRecognizer.createSpeechRecognizer(context)
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return null
    }

    companion object {
        const val MIC_DENIED = "Benchjot can’t use the microphone. Allow it in Settings → Apps → Benchjot → Permissions, then try again."
        const val NO_SPEECH = "Nothing was heard. Tap Speak and try again."
        const val NEEDS_CONNECTION = "Speech to text needs a connection on this phone. Type your request instead."
        const val NO_LANGUAGE = "Speech to text doesn’t have your phone’s language yet. Type your request instead."
        const val BUSY = "The microphone is busy with another app. Try again in a moment."
        const val CANT_LISTEN = "Speech to text didn’t work just now. Try again, or type your request."
        const val NO_RECOGNIZER = "This phone has no speech recognition. Turn on Speech Recognition and Synthesis from Google (or another speech app), or type your request."

        /** What was heard: the service's best guess, spaces tidied. */
        fun words(b: Bundle): String? =
            b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

        /** Why listening ended, said plainly; null when nothing went wrong (it was stopped). */
        fun problem(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MIC_DENIED
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> NEEDS_CONNECTION
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NO_SPEECH
            12, 13 -> NO_LANGUAGE // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (Android 12)
            SpeechRecognizer.ERROR_CLIENT -> null
            else -> CANT_LISTEN
        }
    }
}
