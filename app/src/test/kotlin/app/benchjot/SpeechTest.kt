package app.benchjot

import android.speech.SpeechRecognizer
import app.benchjot.voice.Speech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Why the phone's speech recognition stopped, said the way the web's use-speech.ts says it. */
class SpeechTest {
    @Test fun eachStopIsSaidPlainly() {
        assertEquals(Speech.MIC_DENIED, Speech.problem(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(Speech.NEEDS_CONNECTION, Speech.problem(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(Speech.NEEDS_CONNECTION, Speech.problem(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(Speech.NO_SPEECH, Speech.problem(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(Speech.NO_SPEECH, Speech.problem(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(Speech.BUSY, Speech.problem(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(Speech.NO_LANGUAGE, Speech.problem(13))
        assertNull(Speech.problem(SpeechRecognizer.ERROR_CLIENT)) // stopped: not a problem
        assertEquals(Speech.CANT_LISTEN, Speech.problem(999))
    }
}
