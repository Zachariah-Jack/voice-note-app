package app.voicenote.wizard

import kotlinx.serialization.Serializable

@Serializable
data class WizardAppState(
    val drafts: List<WizardDraft> = emptyList(),
    val session: SessionState = SessionState(),
) {
    fun findDraft(draftId: String): WizardDraft =
        drafts.firstOrNull { it.id == draftId }
            ?: error("Draft $draftId was not found.")

    fun upsertDraft(draft: WizardDraft): WizardAppState {
        val updatedDrafts = drafts.filterNot { it.id == draft.id } + draft
        return copy(drafts = updatedDrafts.sortedBy { it.createdAtEpochMillis })
    }
}

@Serializable
data class WizardDraft(
    val id: String,
    val status: DraftStatus = DraftStatus.IN_PROGRESS,
    val transcript: List<TranscriptTurn> = emptyList(),
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun appendTurn(turn: TranscriptTurn, updatedAtEpochMillis: Long): WizardDraft =
        copy(
            transcript = transcript + turn,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
}

@Serializable
data class TranscriptTurn(
    val id: String,
    val speaker: TranscriptSpeaker,
    val text: String,
    val createdAtEpochMillis: Long,
)

@Serializable
data class SessionState(
    val draftId: String? = null,
    val phase: SessionPhase = SessionPhase.IDLE,
    val assistantSpeech: AssistantSpeechState = AssistantSpeechState(),
    val speechRecognition: SpeechRecognitionState = SpeechRecognitionState(),
    val updatedAtEpochMillis: Long = 0L,
)

@Serializable
data class AssistantSpeechState(
    val status: AssistantSpeechStatus = AssistantSpeechStatus.IDLE,
    val utteranceId: String? = null,
    val message: String? = null,
    val nextPhase: SessionPhase = SessionPhase.AWAITING_USER_TURN,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@Serializable
data class SpeechRecognitionState(
    val status: SpeechRecognitionStatus = SpeechRecognitionStatus.IDLE,
    val partialTranscript: String? = null,
    val finalTranscript: String? = null,
    val errorType: SpeechRecognitionEventType? = null,
    val errorCode: Int? = null,
)

data class SessionSnapshot(
    val draft: WizardDraft,
    val session: SessionState,
)

enum class DraftStatus {
    IN_PROGRESS,
    FINISHED,
}

enum class TranscriptSpeaker {
    USER,
    WIZARD,
}

enum class SessionPhase {
    IDLE,
    AWAITING_USER_TURN,
    LISTENING_USER,
    RUNNING_WIZARD_TURN,
    SPEAKING_ASSISTANT,
}

enum class AssistantSpeechStatus {
    IDLE,
    REQUESTED,
    SPEAKING,
    STOPPING,
    STOPPED,
    ERROR,
}

enum class SpeechRecognitionStatus {
    IDLE,
    LISTENING,
    FINAL_RECEIVED,
    STOPPED,
    CANCELLED,
    NO_MATCH,
    TIMEOUT,
    BUSY,
    PERMISSION_NEEDED,
    ERROR,
}
