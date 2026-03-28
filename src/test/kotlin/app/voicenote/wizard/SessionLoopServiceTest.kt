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
    fun `manual assistant message queues speech without changing the committed transcript`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val speaker = RecordingAssistantSpeaker()
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            assistantSpeaker = speaker,
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val started = service.startNewSession()
        val queued = service.speakAssistantMessage("Welcome to the demo")
        val persistedState = JsonFileAppStateStore(stateFile).load()

        assertEquals(started.draft.id, queued.draft.id)
        assertTrue(queued.draft.transcript.isEmpty())
        assertEquals(SessionPhase.SPEAKING_ASSISTANT, queued.session.phase)
        assertEquals(AssistantSpeechStatus.REQUESTED, queued.session.assistantSpeech.status)
        assertEquals("Welcome to the demo", queued.session.assistantSpeech.message)
        assertEquals("Welcome to the demo", speaker.spokenRequests.single().text)
        assertEquals("Welcome to the demo", persistedState.session.assistantSpeech.message)
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
    fun `resume selected draft loads the requested unfinished draft`() {
        val store = JsonFileAppStateStore(tempDir.resolve("app-state.json"))
        store.save(
            WizardAppState(
                drafts = listOf(
                    WizardDraft(
                        id = "draft-first",
                        createdAtEpochMillis = 1L,
                        updatedAtEpochMillis = 10L,
                    ),
                    WizardDraft(
                        id = "draft-selected",
                        transcript = listOf(
                            TranscriptTurn(
                                id = "turn-1",
                                speaker = TranscriptSpeaker.USER,
                                text = "Resume me",
                                createdAtEpochMillis = 20L,
                            ),
                        ),
                        createdAtEpochMillis = 2L,
                        updatedAtEpochMillis = 20L,
                    ),
                ),
            ),
        )
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(startAt = 900L),
        )

        val snapshot = service.resumeDraft("draft-selected")
        val persistedState = store.load()

        assertNotNull(snapshot)
        assertEquals("draft-selected", snapshot.draft.id)
        assertEquals("draft-selected", persistedState.session.draftId)
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(900L, persistedState.session.updatedAtEpochMillis)
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
    fun `persisted recovery keeps the saved organization selection`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = FakeWizardTurnClient(),
            jobTreadLookupRepository = FixedJobTreadLookupRepository(
                refreshedSelection = JobTreadOrganizationSelectionState(
                    status = JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED,
                    organizations = listOf(
                        JobTreadOrganization(id = "org-1", name = "Northwind Builders"),
                        JobTreadOrganization(id = "org-2", name = "Southwind Renovations"),
                    ),
                    updatedAtEpochMillis = 2_000L,
                ),
            ),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        service.refreshJobTreadOrganizations()
        service.saveDefaultJobTreadOrganization("org-2")

        val reloadedService = SessionLoopService(
            store = JsonFileAppStateStore(stateFile),
            wizardTurnClient = FakeWizardTurnClient(),
            jobTreadLookupRepository = FixedJobTreadLookupRepository(
                refreshedSelection = JobTreadOrganizationSelectionState(
                    status = JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT,
                    organizations = listOf(
                        JobTreadOrganization(id = "org-1", name = "Northwind Builders"),
                        JobTreadOrganization(id = "org-2", name = "Southwind Renovations"),
                    ),
                    selectedOrganizationId = "org-2",
                    defaultOrganizationId = "org-2",
                    updatedAtEpochMillis = 10_000L,
                ),
            ),
            idGenerator = SequentialIdGenerator(startAt = 20),
            clock = StepClock(startAt = 10_000L),
        )
        val persistedSelection = JsonFileAppStateStore(stateFile).load().jobTreadOrganizationSelection
        val refreshedSelection = reloadedService.refreshJobTreadOrganizations()

        assertEquals(JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT, persistedSelection.status)
        assertEquals("org-2", persistedSelection.defaultOrganizationId)
        assertEquals("org-2", persistedSelection.selectedOrganizationId)
        assertEquals(JobTreadOrganizationSelectionStatus.SELECTED_FROM_SAVED_DEFAULT, refreshedSelection.status)
        assertEquals("Southwind Renovations", refreshedSelection.selectedOrganization()?.name)
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

    @Test
    fun `safe lookup failure behavior does not corrupt persisted draft or session`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = LookupRequestWizardTurnClient(
                wizardMessage = "I could not confirm the job yet.",
                jobLookupQuery = "Maple Street remodel",
            ),
            jobTreadLookupRepository = ThrowingJobTreadLookupRepository(),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val started = service.startNewSession()
        val snapshot = service.submitUserTurn("Find the Maple Street remodel")
        val persistedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = persistedState.findDraft(started.draft.id)

        assertEquals(
            listOf(
                "Find the Maple Street remodel",
                "I could not confirm the job yet.",
            ),
            persistedDraft.transcript.map { it.text },
        )
        assertEquals(SessionPhase.AWAITING_USER_TURN, snapshot.session.phase)
        assertEquals(SessionPhase.AWAITING_USER_TURN, persistedState.session.phase)
        assertEquals(JobTreadSnapshotStatus.FAILED, persistedDraft.jobTreadLookup.snapshotStatus)
        assertEquals(JobTreadResolutionStatus.UNRESOLVED, persistedDraft.jobTreadLookup.resolutionStatus)
    }

    @Test
    fun `safe blocking behavior keeps session recoverable when organization selection is unresolved`() {
        val stateFile = tempDir.resolve("app-state.json")
        val store = JsonFileAppStateStore(stateFile)
        val service = SessionLoopService(
            store = store,
            wizardTurnClient = LookupRequestWizardTurnClient(
                wizardMessage = "I need the JobTread job before I can confirm details.",
                jobLookupQuery = "Main Street remodel",
            ),
            jobTreadLookupRepository = FixedJobTreadLookupRepository(
                execution = JobTreadLookupExecution(
                    organizationSelection = JobTreadOrganizationSelectionState(
                        status = JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED,
                        organizations = listOf(
                            JobTreadOrganization(id = "org-1", name = "Northwind Builders"),
                            JobTreadOrganization(id = "org-2", name = "Southwind Renovations"),
                        ),
                        updatedAtEpochMillis = 3_000L,
                    ),
                    lookupState = JobTreadLookupState(
                        requestedReferenceText = "Main Street remodel",
                        snapshotStatus = JobTreadSnapshotStatus.SELECTION_REQUIRED,
                        resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                        failureMessage = "Select a JobTread organization before lookup can run.",
                        updatedAtEpochMillis = 3_000L,
                    ),
                ),
            ),
            idGenerator = SequentialIdGenerator(),
            clock = StepClock(),
        )

        val started = service.startNewSession()
        val snapshot = service.submitUserTurn("Find the Main Street remodel")
        val persistedState = JsonFileAppStateStore(stateFile).load()
        val persistedDraft = persistedState.findDraft(started.draft.id)

        assertEquals(SessionPhase.AWAITING_USER_TURN, snapshot.session.phase)
        assertEquals(JobTreadSnapshotStatus.SELECTION_REQUIRED, persistedDraft.jobTreadLookup.snapshotStatus)
        assertEquals(JobTreadOrganizationSelectionStatus.SELECTION_REQUIRED, persistedState.jobTreadOrganizationSelection.status)
        assertEquals(listOf("org-1", "org-2"), persistedState.jobTreadOrganizationSelection.organizations.map(JobTreadOrganization::id))
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

    private class LookupRequestWizardTurnClient(
        private val wizardMessage: String,
        private val jobLookupQuery: String,
    ) : WizardTurnClient {
        override fun runTurn(request: WizardTurnRequest): WizardTurnResponse = WizardTurnResponse(
            wizardMessage = wizardMessage,
            jobLookupQuery = jobLookupQuery,
        )
    }

    private class FixedJobTreadLookupRepository(
        private val refreshedSelection: JobTreadOrganizationSelectionState = JobTreadOrganizationSelectionState(),
        private val execution: JobTreadLookupExecution = JobTreadLookupExecution(
            organizationSelection = refreshedSelection,
            lookupState = JobTreadLookupState(),
        ),
    ) : JobTreadLookupRepository {
        override fun refreshOrganizationSelection(
            currentSelection: JobTreadOrganizationSelectionState,
        ): JobTreadOrganizationSelectionState = refreshedSelection

        override fun resolveJobReference(
            referenceText: String?,
            currentSelection: JobTreadOrganizationSelectionState,
        ): JobTreadLookupExecution = execution
    }

    private class ThrowingJobTreadLookupRepository : JobTreadLookupRepository {
        override fun refreshOrganizationSelection(
            currentSelection: JobTreadOrganizationSelectionState,
        ): JobTreadOrganizationSelectionState {
            error("Lookup service unavailable")
        }

        override fun resolveJobReference(
            referenceText: String?,
            currentSelection: JobTreadOrganizationSelectionState,
        ): JobTreadLookupExecution {
            error("Lookup service unavailable")
        }
    }
}
