package app.voicenote.wizard

import kotlinx.serialization.Serializable

data class WizardTurnRequest(
    val draft: WizardDraft,
    val session: SessionState,
    val userTurn: TranscriptTurn,
)

@Serializable
data class WizardTurnResponse(
    val wizardMessage: String,
    val nextDraftStatus: DraftStatus = DraftStatus.IN_PROGRESS,
    val nextSessionPhase: SessionPhase = SessionPhase.AWAITING_USER_TURN,
    val jobLookupQuery: String? = null,
)

open class WizardTurnClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface WizardTurnClient {
    fun runTurn(request: WizardTurnRequest): WizardTurnResponse
}

class FakeWizardTurnClient : WizardTurnClient {
    override fun runTurn(request: WizardTurnRequest): WizardTurnResponse {
        val userTurnCount = request.draft.transcript.count { it.speaker == TranscriptSpeaker.USER }
        return WizardTurnResponse(
            wizardMessage = "Local wizard reply #$userTurnCount: ${request.userTurn.text}",
        )
    }
}
