package app.voicenote.demo

import app.voicenote.wizard.DraftStatus
import app.voicenote.wizard.SessionPhase
import app.voicenote.wizard.SessionState
import app.voicenote.wizard.SpeechRecognitionState
import app.voicenote.wizard.SpeechRecognitionStatus
import app.voicenote.wizard.TranscriptSpeaker
import app.voicenote.wizard.TranscriptTurn
import app.voicenote.wizard.WizardAppState
import app.voicenote.wizard.WizardDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraftInboxViewStateFactoryTest {
    @Test
    fun `draft list is sorted by most recently updated first`() {
        val state = WizardAppState(
            drafts = listOf(
                draft(id = "draft-oldest", updatedAt = 10L, transcriptText = "oldest"),
                draft(id = "draft-newest", updatedAt = 30L, transcriptText = "newest"),
                draft(id = "draft-middle", updatedAt = 20L, transcriptText = "middle"),
            ),
        )

        val viewState = DraftInboxViewStateFactory.create(state)

        assertEquals(
            listOf("draft-newest", "draft-middle", "draft-oldest"),
            viewState.drafts.map { item -> item.draftId },
        )
    }

    @Test
    fun `new session is surfaced as the active session at the top`() {
        val activeDraft = draft(id = "draft-active", updatedAt = 40L, transcriptText = null)
        val state = WizardAppState(
            drafts = listOf(activeDraft),
            session = SessionState(
                draftId = activeDraft.id,
                phase = SessionPhase.AWAITING_USER_TURN,
                updatedAtEpochMillis = 40L,
            ),
        )

        val viewState = DraftInboxViewStateFactory.create(state)
        val activeSession = assertNotNull(viewState.activeSession)

        assertEquals(activeDraft.id, activeSession.draftId)
        assertEquals(SessionPhase.AWAITING_USER_TURN, activeSession.phase)
        assertEquals("No transcript yet.", activeSession.lastSnippet)
        assertEquals(0, activeSession.transcriptCount)
        assertTrue(!activeSession.isRecoverablePaused)
    }

    @Test
    fun `recoverable paused session is clearly surfaced at the top`() {
        val pausedDraft = draft(id = "draft-paused", updatedAt = 60L, transcriptText = "Saved partial")
        val state = WizardAppState(
            drafts = listOf(pausedDraft),
            session = SessionState(
                draftId = pausedDraft.id,
                phase = SessionPhase.AWAITING_USER_TURN,
                speechRecognition = SpeechRecognitionState(
                    status = SpeechRecognitionStatus.NO_MATCH,
                ),
                updatedAtEpochMillis = 60L,
            ),
        )

        val viewState = DraftInboxViewStateFactory.create(state)
        val activeSession = assertNotNull(viewState.activeSession)

        assertEquals(pausedDraft.id, activeSession.draftId)
        assertTrue(activeSession.isRecoverablePaused)
        assertEquals("Saved partial", activeSession.lastSnippet)
    }

    private fun draft(
        id: String,
        updatedAt: Long,
        transcriptText: String?,
    ): WizardDraft = WizardDraft(
        id = id,
        status = DraftStatus.IN_PROGRESS,
        transcript = transcriptText?.let { text ->
            listOf(
                TranscriptTurn(
                    id = "$id-turn-1",
                    speaker = TranscriptSpeaker.USER,
                    text = text,
                    createdAtEpochMillis = updatedAt,
                ),
            )
        } ?: emptyList(),
        createdAtEpochMillis = updatedAt - 1L,
        updatedAtEpochMillis = updatedAt,
    )
}
