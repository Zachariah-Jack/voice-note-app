package app.voicenote.android.tts

import android.speech.tts.TextToSpeech
import app.voicenote.wizard.AssistantSpeakerEvent
import app.voicenote.wizard.AssistantSpeakerEventType
import app.voicenote.wizard.AssistantSpeechRequest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidTextToSpeechAssistantSpeakerTest {
    @Test
    fun `speak emits started and done events from engine callbacks`() {
        val engine = FakeAndroidTextToSpeechEngine()
        val speaker = AndroidTextToSpeechAssistantSpeaker(
            engineFactory = AndroidTextToSpeechEngineFactory { callbacks ->
                engine.callbacks = callbacks
                engine
            },
            locale = Locale.US,
        )
        val events = mutableListOf<AssistantSpeakerEvent>()
        speaker.setEventListener { event -> events += event }

        engine.callbacks.onInit(TextToSpeech.SUCCESS)
        speaker.speak(AssistantSpeechRequest(utteranceId = "utt-1", text = "Hello there"))
        engine.callbacks.onStart("utt-1")
        engine.callbacks.onDone("utt-1")

        assertEquals(listOf("Hello there"), engine.spokenTexts)
        assertEquals(
            listOf(
                AssistantSpeakerEvent("utt-1", AssistantSpeakerEventType.STARTED),
                AssistantSpeakerEvent("utt-1", AssistantSpeakerEventType.DONE),
            ),
            events,
        )
    }

    @Test
    fun `stop delegates to the underlying engine after initialization`() {
        val engine = FakeAndroidTextToSpeechEngine()
        val speaker = AndroidTextToSpeechAssistantSpeaker(
            engineFactory = AndroidTextToSpeechEngineFactory { callbacks ->
                engine.callbacks = callbacks
                engine
            },
            locale = Locale.US,
        )

        engine.callbacks.onInit(TextToSpeech.SUCCESS)
        speaker.speak(AssistantSpeechRequest(utteranceId = "utt-2", text = "Stop me"))
        speaker.stop()

        assertTrue(engine.stopCalled)
    }

    @Test
    fun `release shuts down the engine and prevents future speak calls`() {
        val engine = FakeAndroidTextToSpeechEngine()
        val speaker = AndroidTextToSpeechAssistantSpeaker(
            engineFactory = AndroidTextToSpeechEngineFactory { callbacks ->
                engine.callbacks = callbacks
                engine
            },
            locale = Locale.US,
        )

        speaker.release()

        assertTrue(engine.shutdownCalled)
        assertFailsWith<IllegalStateException> {
            speaker.speak(AssistantSpeechRequest(utteranceId = "utt-3", text = "After release"))
        }
    }

    private class FakeAndroidTextToSpeechEngine : AndroidTextToSpeechEngine {
        lateinit var callbacks: AndroidTextToSpeechEngineCallbacks
        val spokenTexts = mutableListOf<String>()
        var stopCalled = false
        var shutdownCalled = false

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun speak(text: String, utteranceId: String): Int {
            spokenTexts += text
            return TextToSpeech.SUCCESS
        }

        override fun stop(): Int {
            stopCalled = true
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }
}
