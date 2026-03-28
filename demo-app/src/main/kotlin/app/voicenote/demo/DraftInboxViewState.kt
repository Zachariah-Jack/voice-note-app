package app.voicenote.demo

import app.voicenote.wizard.AssistantSpeechStatus
import app.voicenote.wizard.DraftStatus
import app.voicenote.wizard.SessionPhase
import app.voicenote.wizard.SpeechRecognitionStatus
import app.voicenote.wizard.WizardAppState
import app.voicenote.wizard.WizardDraft

data class DraftInboxViewState(
    val activeSession: ActiveSessionSummary? = null,
    val drafts: List<DraftInboxItem> = emptyList(),
)

data class ActiveSessionSummary(
    val draftId: String,
    val status: DraftStatus,
    val phase: SessionPhase,
    val lastUpdatedEpochMillis: Long,
    val lastSnippet: String,
    val transcriptCount: Int,
    val isRecoverablePaused: Boolean,
)

data class DraftInboxItem(
    val draftId: String,
    val status: DraftStatus,
    val lastUpdatedEpochMillis: Long,
    val lastSnippet: String,
    val transcriptCount: Int,
    val sessionPhase: SessionPhase? = null,
    val isActive: Boolean = false,
)

object DraftInboxViewStateFactory {
    fun create(state: WizardAppState): DraftInboxViewState {
        val activeDraft = state.session.draftId?.let { draftId ->
            state.drafts.firstOrNull { draft -> draft.id == draftId }
        }

        return DraftInboxViewState(
            activeSession = activeDraft
                ?.takeIf { state.shouldSurfaceActiveSession() }
                ?.toActiveSessionSummary(state),
            drafts = state.drafts
                .sortedByDescending { draft -> draft.updatedAtEpochMillis }
                .map { draft ->
                    val isActive = draft.id == state.session.draftId
                    DraftInboxItem(
                        draftId = draft.id,
                        status = draft.status,
                        lastUpdatedEpochMillis = draft.updatedAtEpochMillis,
                        lastSnippet = draft.lastTranscriptSnippet(),
                        transcriptCount = draft.transcript.size,
                        sessionPhase = state.session.phase.takeIf { isActive },
                        isActive = isActive,
                    )
                },
        )
    }
}

private fun WizardAppState.shouldSurfaceActiveSession(): Boolean =
    session.draftId != null &&
        (
            session.phase != SessionPhase.IDLE ||
                session.assistantSpeech.status != AssistantSpeechStatus.IDLE ||
                session.speechRecognition.status != SpeechRecognitionStatus.IDLE
            )

private fun WizardDraft.toActiveSessionSummary(state: WizardAppState): ActiveSessionSummary =
    ActiveSessionSummary(
        draftId = id,
        status = status,
        phase = state.session.phase,
        lastUpdatedEpochMillis = updatedAtEpochMillis,
        lastSnippet = lastTranscriptSnippet(),
        transcriptCount = transcript.size,
        isRecoverablePaused = state.session.phase == SessionPhase.AWAITING_USER_TURN &&
            (
                state.session.speechRecognition.status != SpeechRecognitionStatus.IDLE ||
                    state.session.assistantSpeech.status == AssistantSpeechStatus.ERROR ||
                    state.session.assistantSpeech.status == AssistantSpeechStatus.STOPPED
                ),
    )

private fun WizardDraft.lastTranscriptSnippet(): String =
    transcript.lastOrNull()?.text?.trim()?.takeIf { snippet -> snippet.isNotEmpty() }?.truncate(80)
        ?: "No transcript yet."

private fun String.truncate(maxLength: Int): String =
    if (length <= maxLength) {
        this
    } else {
        take(maxLength - 3) + "..."
    }
