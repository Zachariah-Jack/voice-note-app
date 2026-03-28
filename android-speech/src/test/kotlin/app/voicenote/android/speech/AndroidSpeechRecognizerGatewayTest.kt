package app.voicenote.android.speech

import android.speech.SpeechRecognizer
import app.voicenote.wizard.SpeechRecognitionEvent
import app.voicenote.wizard.SpeechRecognitionEventType
import app.voicenote.wizard.SpeechRecognizerAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSpeechRecognizerGatewayTest {
    @Test
    fun `availability check returns permission needed when record audio is missing`() {
        val gateway = AndroidSpeechRecognizerGateway(
            environment = FakeSpeechRecognizerEnvironment(
                hasPermission = false,
                recognitionAvailable = true,
            ),
            mainThreadChecker = NoOpMainThreadChecker,
        )

        val availability = gateway.checkAvailability()

        assertEquals(SpeechRecognizerAvailability.PERMISSION_NEEDED, availability)
    }

    @Test
    fun `start listening emits permission required when record audio is missing`() {
        val gateway = AndroidSpeechRecognizerGateway(
            environment = FakeSpeechRecognizerEnvironment(
                hasPermission = false,
                recognitionAvailable = true,
            ),
            mainThreadChecker = NoOpMainThreadChecker,
        )
        val events = mutableListOf<SpeechRecognitionEvent>()
        gateway.setEventListener { event -> events += event }

        gateway.startListening()

        assertEquals(
            listOf(SpeechRecognitionEvent(type = SpeechRecognitionEventType.PERMISSION_REQUIRED)),
            events,
        )
    }

    @Test
    fun `start listening emits listening partial and final transcript events`() {
        val environment = FakeSpeechRecognizerEnvironment(
            hasPermission = true,
            recognitionAvailable = true,
            onDeviceAvailable = true,
        )
        val gateway = AndroidSpeechRecognizerGateway(
            environment = environment,
            mainThreadChecker = NoOpMainThreadChecker,
        )
        val events = mutableListOf<SpeechRecognitionEvent>()
        gateway.setEventListener { event -> events += event }

        gateway.startListening()
        environment.callbacks.onListeningStarted()
        environment.callbacks.onPartialTranscript("partial note")
        environment.callbacks.onFinalTranscript("final note")

        assertTrue(environment.recognizer.startCalled)
        assertEquals(true, environment.recognizer.lastPreferOnDevice)
        assertEquals(
            listOf(
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.LISTENING_STARTED),
                SpeechRecognitionEvent(
                    type = SpeechRecognitionEventType.PARTIAL_TRANSCRIPT,
                    transcript = "partial note",
                ),
                SpeechRecognitionEvent(
                    type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                    transcript = "final note",
                ),
            ),
            events,
        )
    }

    @Test
    fun `gateway maps recognizer errors to focused event types`() {
        val environment = FakeSpeechRecognizerEnvironment(
            hasPermission = true,
            recognitionAvailable = true,
        )
        val gateway = AndroidSpeechRecognizerGateway(
            environment = environment,
            mainThreadChecker = NoOpMainThreadChecker,
        )
        val events = mutableListOf<SpeechRecognitionEvent>()
        gateway.setEventListener { event -> events += event }

        gateway.startListening()
        environment.callbacks.onError(SpeechRecognizer.ERROR_NO_MATCH)
        environment.callbacks.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        environment.callbacks.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        environment.callbacks.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        environment.callbacks.onError(SpeechRecognizer.ERROR_NETWORK)

        assertEquals(
            listOf(
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.NO_MATCH, errorCode = null),
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.TIMEOUT, errorCode = null),
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.BUSY, errorCode = null),
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.PERMISSION_REQUIRED, errorCode = null),
                SpeechRecognitionEvent(type = SpeechRecognitionEventType.ERROR, errorCode = SpeechRecognizer.ERROR_NETWORK),
            ),
            events,
        )
    }

    @Test
    fun `stop cancel and release delegate to recognizer and destroy it`() {
        val environment = FakeSpeechRecognizerEnvironment(
            hasPermission = true,
            recognitionAvailable = true,
        )
        val gateway = AndroidSpeechRecognizerGateway(
            environment = environment,
            mainThreadChecker = NoOpMainThreadChecker,
        )

        gateway.startListening()
        gateway.stopListening()
        gateway.cancel()
        gateway.release()

        assertTrue(environment.recognizer.stopCalled)
        assertTrue(environment.recognizer.cancelCalled)
        assertTrue(environment.recognizer.destroyCalled)
    }

    private class FakeSpeechRecognizerEnvironment(
        private val hasPermission: Boolean,
        private val recognitionAvailable: Boolean,
        private val onDeviceAvailable: Boolean = false,
    ) : AndroidSpeechRecognizerEnvironment {
        val recognizer = FakeSpeechRecognizer()
        lateinit var callbacks: AndroidSpeechRecognizerCallbacks

        override fun hasRecordAudioPermission(): Boolean = hasPermission

        override fun isRecognitionAvailable(): Boolean = recognitionAvailable

        override fun isOnDeviceRecognitionAvailable(): Boolean = onDeviceAvailable

        override fun createRecognizer(
            callbacks: AndroidSpeechRecognizerCallbacks,
            preferOnDevice: Boolean,
        ): AndroidSpeechRecognizer {
            this.callbacks = callbacks
            return recognizer
        }
    }

    private class FakeSpeechRecognizer : AndroidSpeechRecognizer {
        var startCalled = false
        var lastPreferOnDevice: Boolean? = null
        var stopCalled = false
        var cancelCalled = false
        var destroyCalled = false

        override fun startListening(preferOnDevice: Boolean) {
            startCalled = true
            lastPreferOnDevice = preferOnDevice
        }

        override fun stopListening() {
            stopCalled = true
        }

        override fun cancel() {
            cancelCalled = true
        }

        override fun destroy() {
            destroyCalled = true
        }
    }

    private object NoOpMainThreadChecker : MainThreadChecker {
        override fun checkMainThread() = Unit
    }
}
