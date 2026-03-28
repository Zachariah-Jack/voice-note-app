package app.voicenote.wizard

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
