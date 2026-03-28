package app.voicenote.wizard

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VoiceTurnControllerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `assistant completion final transcript and next assistant reply restart listening for the next turn`() {
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
        assertEquals(1, recognizer.startListeningCalls)

        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                transcript = "Second turn",
            ),
        )
        val afterSecondReplyQueued = JsonFileAppStateStore(stateFile).load()
        val draftAfterSecondTurn = afterSecondReplyQueued.findDraft(afterSecondReplyQueued.session.draftId!!)

        assertEquals(
            listOf(
                "Kick off spoken loop",
                "Local wizard reply #1: Kick off spoken loop",
                "Second turn",
                "Local wizard reply #2: Second turn",
            ),
            draftAfterSecondTurn.transcript.map { it.text },
        )
        assertEquals(SessionPhase.SPEAKING_ASSISTANT, afterSecondReplyQueued.session.phase)

        speaker.emit(AssistantSpeakerEventType.DONE)
        val afterNextListenCycle = JsonFileAppStateStore(stateFile).load()

        assertEquals(2, recognizer.startListeningCalls)
        assertEquals(SessionPhase.LISTENING_USER, afterNextListenCycle.session.phase)
        assertEquals(SpeechRecognitionStatus.LISTENING, afterNextListenCycle.session.speechRecognition.status)
    }

    @Test
    fun `explicit stop and cancel prevent the loop from re arming`() {
        val stopStateFile = tempDir.resolve("stop-app-state.json")
        val stopStore = JsonFileAppStateStore(stopStateFile)
        val stopSpeaker = RecordingAssistantSpeaker()
        val stopRecognizer = RecordingSpeechRecognizerGateway()
        val stopService = SessionLoopService(
            store = stopStore,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = stopSpeaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val stopController = VoiceTurnController(
            store = stopStore,
            sessionLoopService = stopService,
            speechRecognizerGateway = stopRecognizer,
            clock = StepClock(startAt = 50_000L),
        )

        stopService.startNewSession()
        stopController.armNextVoiceTurn()
        stopService.submitUserTurn("Prepare to stop")
        stopController.stop()
        stopSpeaker.emit(AssistantSpeakerEventType.DONE)
        val afterStop = JsonFileAppStateStore(stopStateFile).load()

        assertTrue(stopRecognizer.stopListeningCalled)
        assertEquals(0, stopRecognizer.startListeningCalls)
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterStop.session.phase)
        assertEquals(SpeechRecognitionStatus.IDLE, afterStop.session.speechRecognition.status)

        val cancelStateFile = tempDir.resolve("cancel-app-state.json")
        val cancelStore = JsonFileAppStateStore(cancelStateFile)
        val cancelSpeaker = RecordingAssistantSpeaker()
        val cancelRecognizer = RecordingSpeechRecognizerGateway()
        val cancelService = SessionLoopService(
            store = cancelStore,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = cancelSpeaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )
        val cancelController = VoiceTurnController(
            store = cancelStore,
            sessionLoopService = cancelService,
            speechRecognizerGateway = cancelRecognizer,
            clock = StepClock(startAt = 50_000L),
        )

        cancelService.startNewSession()
        cancelController.armNextVoiceTurn()
        cancelService.submitUserTurn("Prepare to cancel")
        cancelSpeaker.emit(AssistantSpeakerEventType.DONE)
        assertEquals(1, cancelRecognizer.startListeningCalls)

        cancelController.cancel()
        cancelRecognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                transcript = "Late transcript should be ignored",
            ),
        )
        cancelSpeaker.emit(AssistantSpeakerEventType.DONE)
        val afterCancel = JsonFileAppStateStore(cancelStateFile).load()
        val draftAfterCancel = afterCancel.findDraft(afterCancel.session.draftId!!)

        assertTrue(cancelRecognizer.cancelCalled)
        assertEquals(1, cancelRecognizer.startListeningCalls)
        assertEquals(
            listOf(
                "Prepare to cancel",
                "Local wizard reply #1: Prepare to cancel",
            ),
            draftAfterCancel.transcript.map { it.text },
        )
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterCancel.session.phase)
        assertEquals(SpeechRecognitionStatus.CANCELLED, afterCancel.session.speechRecognition.status)
    }

    @Test
    fun `no match and timeout recover by starting a fresh listening cycle`() {
        val stateFile = tempDir.resolve("recovery-app-state.json")
        val store = RecordingAppStateStore(JsonFileAppStateStore(stateFile))
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
        service.submitUserTurn("Recoverable turn")
        speaker.emit(AssistantSpeakerEventType.DONE)

        recognizer.emit(SpeechRecognitionEvent(type = SpeechRecognitionEventType.NO_MATCH))
        recognizer.emit(SpeechRecognitionEvent(type = SpeechRecognitionEventType.TIMEOUT))
        val persistedState = JsonFileAppStateStore(stateFile).load()
        val draft = persistedState.findDraft(persistedState.session.draftId!!)

        assertEquals(3, recognizer.startListeningCalls)
        assertTrue(store.savedStates.any { it.session.speechRecognition.status == SpeechRecognitionStatus.NO_MATCH })
        assertTrue(store.savedStates.any { it.session.speechRecognition.status == SpeechRecognitionStatus.TIMEOUT })
        assertEquals(
            listOf(
                "Recoverable turn",
                "Local wizard reply #1: Recoverable turn",
            ),
            draft.transcript.map { it.text },
        )
        assertEquals(SessionPhase.LISTENING_USER, persistedState.session.phase)
        assertEquals(SpeechRecognitionStatus.LISTENING, persistedState.session.speechRecognition.status)
    }

    @Test
    fun `persistence survives multi turn flow while keeping partial transcript separate`() {
        val stateFile = tempDir.resolve("multi-turn-app-state.json")
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
        service.submitUserTurn("First turn")
        speaker.emit(AssistantSpeakerEventType.DONE)
        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                transcript = "Second turn",
            ),
        )
        speaker.emit(AssistantSpeakerEventType.DONE)
        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.PARTIAL_TRANSCRIPT,
                transcript = "Third turn partial",
            ),
        )

        val reloadedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = reloadedState.findDraft(reloadedState.session.draftId!!)

        assertEquals(
            listOf(
                "First turn",
                "Local wizard reply #1: First turn",
                "Second turn",
                "Local wizard reply #2: Second turn",
            ),
            persistedDraft.transcript.map { it.text },
        )
        assertEquals(SessionPhase.LISTENING_USER, reloadedState.session.phase)
        assertEquals("Third turn partial", reloadedState.session.speechRecognition.partialTranscript)
    }

    @Test
    fun `hard failure pauses the loop and leaves the session recoverable`() {
        val stateFile = tempDir.resolve("hard-failure-app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val recognizer = RecordingSpeechRecognizerGateway()
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = ThrowingWizardTurnClient(),
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

        controller.startSession("Continuous loop prompt")
        speaker.emit(AssistantSpeakerEventType.DONE)
        recognizer.emit(
            SpeechRecognitionEvent(
                type = SpeechRecognitionEventType.FINAL_TRANSCRIPT,
                transcript = "This should fail",
            ),
        )
        val persistedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = persistedState.findDraft(persistedState.session.draftId!!)

        assertEquals(listOf("This should fail"), persistedDraft.transcript.map { it.text })
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(SpeechRecognitionStatus.ERROR, persistedState.session.speechRecognition.status)
        assertEquals(
            SpeechRecognitionEventType.ERROR,
            persistedState.session.speechRecognition.errorType,
        )

        controller.resumeSession()
        val afterResume = JsonFileAppStateStore(stateFile).load()

        assertEquals(2, recognizer.startListeningCalls)
        assertEquals(SessionPhase.LISTENING_USER, afterResume.session.phase)
        assertEquals(SpeechRecognitionStatus.LISTENING, afterResume.session.speechRecognition.status)
    }

    private class RecordingAppStateStore(
        private val delegate: AppStateStore,
    ) : AppStateStore {
        val savedStates = mutableListOf<WizardAppState>()

        override fun load(): WizardAppState = delegate.load()

        override fun save(state: WizardAppState) {
            delegate.save(state)
            savedStates += state
        }
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

    private class ThrowingWizardTurnClient : WizardTurnClient {
        override fun runTurn(request: WizardTurnRequest): WizardTurnResponse {
            throw WizardTurnClientException("Simulated hard failure")
        }
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
