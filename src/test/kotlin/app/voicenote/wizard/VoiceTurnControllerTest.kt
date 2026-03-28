package app.voicenote.wizard

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VoiceTurnControllerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start session speaks the initial assistant prompt and arms one listen cycle`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val recognizer = RecordingSpeechRecognizerGateway()
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val controller = VoiceTurnController(
            store = store,
            sessionLoopService = service,
            speechRecognizerGateway = recognizer,
            clock = StepClock(startAt = 50_000L),
        )

        val snapshot = controller.startSession("How can I help with your voice note?")

        assertEquals(SessionPhase.SPEAKING_ASSISTANT, snapshot.session.phase)
        assertEquals(
            "How can I help with your voice note?",
            snapshot.session.assistantSpeech.message,
        )
        assertEquals(
            listOf("How can I help with your voice note?"),
            speaker.spokenRequests.map { it.text },
        )
        assertEquals(0, recognizer.startListeningCalls)

        speaker.emit(AssistantSpeakerEventType.DONE)

        assertEquals(1, recognizer.startListeningCalls)
    }

    @Test
    fun `assistant done starts one listen cycle and final transcript submits next wizard turn`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val recognizer = RecordingSpeechRecognizerGateway()
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val controller = VoiceTurnController(
            store = store,
            sessionLoopService = service,
            speechRecognizerGateway = recognizer,
            clock = StepClock(startAt = 50_000L),
        )

        service.startNewSession()
        controller.armNextVoiceTurn()
        service.submitUserTurn("Kick off spoken loop")

        speaker.emit(AssistantSpeakerEventType.DONE)
        val afterListeningStart = JsonFileAppStateStore(stateFile).load()

        assertEquals(1, recognizer.startListeningCalls)
        assertEquals(SessionPhase.LISTENING_USER, afterListeningStart.session.phase)
        assertEquals(SpeechRecognitionStatus.LISTENING, afterListeningStart.session.speechRecognition.status)

        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.PARTIAL_TRANSCRIPT,
                transcript = "partial draft",
            ),
        )
        val afterPartial = JsonFileAppStateStore(stateFile).load()
        val draftAfterPartial = afterPartial.findDraft(afterPartial.session.draftId!!)
        assertEquals("partial draft", afterPartial.session.speechRecognition.partialTranscript)
        assertFalse(draftAfterPartial.transcript.any { it.text == "partial draft" })

        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                transcript = "final spoken note",
            ),
        )
        val afterFinal = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = afterFinal.findDraft(afterFinal.session.draftId!!)

        assertEquals(
            listOf(
                "Kick off spoken loop",
                "Local wizard reply #1: Kick off spoken loop",
                "final spoken note",
                "Local wizard reply #2: final spoken note",
            ),
            persistedDraft.transcript.map { it.text },
        )
        assertEquals(SpeechRecognitionStatus.IDLE, afterFinal.session.speechRecognition.status)
        assertEquals(1, recognizer.startListeningCalls)

        speaker.emit(AssistantSpeakerEventType.DONE)
        assertEquals(1, recognizer.startListeningCalls)
    }

    @Test
    fun `stop and cancel coordinate active recognition cleanly`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val recognizer = RecordingSpeechRecognizerGateway()
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val controller = VoiceTurnController(
            store = store,
            sessionLoopService = service,
            speechRecognizerGateway = recognizer,
            clock = StepClock(startAt = 50_000L),
        )

        service.startNewSession()
        controller.armNextVoiceTurn()
        service.submitUserTurn("Prepare to stop")
        speaker.emit(AssistantSpeakerEventType.DONE)

        controller.stop()
        val afterStop = JsonFileAppStateStore(stateFile).load()
        assertTrue(recognizer.stopListeningCalled)
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterStop.session.phase)
        assertEquals(SpeechRecognitionStatus.STOPPED, afterStop.session.speechRecognition.status)

        controller.armNextVoiceTurn()
        service.submitUserTurn("Prepare to cancel")
        speaker.emit(AssistantSpeakerEventType.DONE)
        controller.cancel()
        val afterCancel = JsonFileAppStateStore(stateFile).load()
        assertTrue(recognizer.cancelCalled)
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterCancel.session.phase)
        assertEquals(SpeechRecognitionStatus.CANCELLED, afterCancel.session.speechRecognition.status)
    }

    @Test
    fun `permission needed after assistant completion persists recognition error without starting listen`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val recognizer = RecordingSpeechRecognizerGateway(
            availability = SpeechRecognizerAvailability.PERMISSION_NEEDED,
        )
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val controller = VoiceTurnController(
            store = store,
            sessionLoopService = service,
            speechRecognizerGateway = recognizer,
            clock = StepClock(startAt = 50_000L),
        )

        service.startNewSession()
        controller.armNextVoiceTurn()
        service.submitUserTurn("Need permission handling")

        speaker.emit(AssistantSpeakerEventType.DONE)
        val persistedState = JsonFileAppStateStore(stateFile).load()

        assertEquals(0, recognizer.startListeningCalls)
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(SpeechRecognitionStatus.PERMISSION_NEEDED, persistedState.session.speechRecognition.status)
        assertEquals(
            SpeechRecognitionEventType.PERMISSION_REQUIRED,
            persistedState.session.speechRecognition.errorType,
        )
    }

    private class StepClock(
        private var startAt: Long = 1_000L,
        private val step: Long = 1_000L,
    ) : EpochClock {
        override fun nowEpochMillis(): Long = startAt.also { startAt += step }
    }

    private class SequentialIdGenerator(
        private var startAt: Int = 1,
    ) : IdGenerator {
        override fun nextId(prefix: String): String = "$prefix-${startAt++}"
    }

    private class RecordingAssistantSpeaker : AssistantSpeaker {
        private var listener: AssistantSpeakerEventListener? = null
        val spokenRequests = mutableListOf<AssistantSpeechRequest>()

        override fun setEventListener(listener: AssistantSpeakerEventListener?) {
            this.listener = listener
        }

        override fun speak(request: AssistantSpeechRequest) {
            spokenRequests += request
        }

        override fun stop() = Unit

        override fun release() = Unit

        fun emit(type: AssistantSpeakerEventType, errorCode: Int? = null) {
            val utteranceId = spokenRequests.last().utteranceId
            listener?.onEvent(
                AssistantSpeakerEvent(
                    utteranceId = utteranceId,
                    type = type,
                    errorCode = errorCode,
                ),
            )
        }
    }

    private class RecordingSpeechRecognizerGateway(
        private val availability: SpeechRecognizerAvailability = SpeechRecognizerAvailability.AVAILABLE,
    ) : SpeechRecognizerGateway {
        private var listener: SpeechRecognitionEventListener? = null
        var startListeningCalls = 0
        var stopListeningCalled = false
        var cancelCalled = false

        override fun setEventListener(listener: SpeechRecognitionEventListener?) {
            this.listener = listener
        }

        override fun checkAvailability(): SpeechRecognizerAvailability = availability

        override fun startListening() {
            startListeningCalls += 1
        }

        override fun stopListening() {
            stopListeningCalled = true
        }

        override fun cancel() {
            cancelCalled = true
        }

        override fun release() = Unit

        fun emit(event: SpeechRecognitionEvent) {
            listener?.onEvent(event)
        }
    }
}
