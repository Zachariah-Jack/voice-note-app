package app.voicenote.wizard

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SessionLoopServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `start session creates and persists a new draft`() {
        val store = JsonFileAppStateStore(tempDir.resolve("app-state.json"))
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val snapshot = service.startNewSession()
        val persistedState = store.load()

        assertEquals("draft-1", snapshot.draft.id)
        assertEquals(SessionPhase.AWAITING_USER_TURN, snapshot.session.phase)
        assertTrue(snapshot.draft.transcript.isEmpty())
        assertEquals(listOf(snapshot.draft), persistedState.drafts)
        assertEquals(snapshot.session, persistedState.session)
    }

    @Test
    fun `resume session loads the most recent unfinished draft`() {
        val store = JsonFileAppStateStore(tempDir.resolve("app-state.json"))
        store.save(
            WizardAppState(
                drafts = listOf(
                    WizardDraft(
                        id = "draft-older",
                        createdAtEpochMillis = 1L,
                        updatedAtEpochMillis = 5L,
                    ),
                    WizardDraft(
                        id = "draft-finished",
                        status = DraftStatus.FINISHED,
                        createdAtEpochMillis = 2L,
                        updatedAtEpochMillis = 100L,
                    ),
                    WizardDraft(
                        id = "draft-latest",
                        transcript = listOf(
                            TranscriptTurn(
                                id = "turn-1",
                                speaker = TranscriptSpeaker.USER,
                                text = "Saved local draft",
                                createdAtEpochMillis = 10L,
                            ),
                        ),
                        createdAtEpochMillis = 3L,
                        updatedAtEpochMillis = 99L,
                    ),
                ),
            ),
        )
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(startAt = 500L),
        )

        val snapshot = service.resumeMostRecentUnfinishedDraft()
        val persistedState = store.load()

        assertNotNull(snapshot)
        assertEquals("draft-latest", snapshot.draft.id)
        assertEquals("draft-latest", persistedState.session.draftId)
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(500L, persistedState.session.updatedAtEpochMillis)
    }

    @Test
    fun `fake turn application appends user and wizard turns deterministically`() {
        val store = JsonFileAppStateStore(tempDir.resolve("app-state.json"))
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        service.startNewSession()
        val snapshot = service.submitUserTurn("Need framing details")

        assertEquals(
            listOf(TranscriptSpeaker.USER, TranscriptSpeaker.WIZARD),
            snapshot.draft.transcript.map { it.speaker },
        )
        assertEquals("Need framing details", snapshot.draft.transcript[0].text)
        assertEquals("Local wizard reply #1: Need framing details", snapshot.draft.transcript[1].text)
        assertEquals(SessionPhase.AWAITING_USER_TURN, snapshot.session.phase)
    }

    @Test
    fun `assistant speaker lifecycle updates persisted session state`() {
        val stateFile = tempDir.resolve("app-state.json")
        val speaker = RecordingAssistantSpeaker()
        val service = SessionLoopService(
            store = JsonFileAppStateStore(stateFile),
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        service.startNewSession()
        val queued = service.submitUserTurn("Speak this back")
        val afterQueue = JsonFileAppStateStore(stateFile).load()

        assertEquals(SessionPhase.SPEAKING_ASSISTANT, queued.session.phase)
        assertEquals(AssistantSpeechStatus.REQUESTED, queued.session.assistantSpeech.status)
        assertEquals("Local wizard reply #1: Speak this back", speaker.spokenRequests.single().text)
        assertEquals(SessionPhase.SPEAKING_ASSISTANT, afterQueue.session.phase)

        speaker.emit(AssistantSpeakerEventType.STARTED)
        val afterStart = JsonFileAppStateStore(stateFile).load()
        assertEquals(AssistantSpeechStatus.SPEAKING, afterStart.session.assistantSpeech.status)
        assertEquals(SessionPhase.SPEAKING_ASSISTANT, afterStart.session.phase)

        speaker.emit(AssistantSpeakerEventType.DONE)
        val afterDone = JsonFileAppStateStore(stateFile).load()
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterDone.session.phase)
        assertEquals(AssistantSpeechStatus.IDLE, afterDone.session.assistantSpeech.status)
    }

    @Test
    fun `stop assistant speech persists stop request and stopped state`() {
        val stateFile = tempDir.resolve("app-state.json")
        val speaker = RecordingAssistantSpeaker()
        val service = SessionLoopService(
            store = JsonFileAppStateStore(stateFile),
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        service.startNewSession()
        service.submitUserTurn("Need to stop")

        val stopping = service.stopAssistantSpeech()
        val afterStopRequest = JsonFileAppStateStore(stateFile).load()

        assertNotNull(stopping)
        assertTrue(speaker.stopCalled)
        assertEquals(AssistantSpeechStatus.STOPPING, stopping.session.assistantSpeech.status)
        assertEquals(AssistantSpeechStatus.STOPPING, afterStopRequest.session.assistantSpeech.status)

        speaker.emit(AssistantSpeakerEventType.STOPPED)
        val afterStopped = JsonFileAppStateStore(stateFile).load()
        assertEquals(SessionPhase.AWAITING_USER_TURN, afterStopped.session.phase)
        assertEquals(AssistantSpeechStatus.STOPPED, afterStopped.session.assistantSpeech.status)
    }

    @Test
    fun `persistence survives reloads across multiple turns`() {
        val stateFile = tempDir.resolve("app-state.json")
        val firstService = SessionLoopService(
            store = JsonFileAppStateStore(stateFile),
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val started = firstService.startNewSession()
        firstService.submitUserTurn("First turn")

        val secondService = SessionLoopService(
            store = JsonFileAppStateStore(stateFile),
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(startAt = 10),
            clock = StepClock(startAt = 10_000L),
        )

        val resumed = secondService.resumeMostRecentUnfinishedDraft()
        val afterSecondTurn = secondService.submitUserTurn("Second turn")
        val persistedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = persistedState.findDraft(started.draft.id)

        assertNotNull(resumed)
        assertEquals(started.draft.id, resumed.draft.id)
        assertEquals(
            listOf(
                "First turn",
                "Local wizard reply #1: First turn",
                "Second turn",
                "Local wizard reply #2: Second turn",
            ),
            persistedDraft.transcript.map { it.text },
        )
        assertEquals(afterSecondTurn.session, persistedState.session)
    }

    @Test
    fun `safe failure behavior keeps persisted state consistent when wizard turn fails`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = OpenAiWizardTurnClient(
                config = OpenAiWizardClientConfig(apiKey = "test-key", model = "test-model"),
                transport = MissingStructuredOutputTransport(),
                assets = WizardTurnContractAssets.loadDefault(),
            ),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val started = service.startNewSession()

        assertFailsWith<WizardTurnClientException> {
            service.submitUserTurn("This should stay local")
        }

        val persistedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = persistedState.findDraft(started.draft.id)

        assertEquals(
            listOf("This should stay local"),
            persistedDraft.transcript.map { it.text },
        )
        assertEquals(
            listOf(TranscriptSpeaker.USER),
            persistedDraft.transcript.map { it.speaker },
        )
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(started.draft.id, persistedState.session.draftId)
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
        var stopCalled = false

        override fun setEventListener(listener: AssistantSpeakerEventListener?) {
            this.listener = listener
        }

        override fun speak(request: AssistantSpeechRequest) {
            spokenRequests += request
        }

        override fun stop() {
            stopCalled = true
        }

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

    private class MissingStructuredOutputTransport : OpenAiResponsesTransport {
        override fun createResponse(requestBody: String, config: OpenAiWizardClientConfig): String =
            """{"output":[]}"""
    }
}
