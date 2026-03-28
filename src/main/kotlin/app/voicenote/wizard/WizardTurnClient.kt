package app.voicenote.wizard

data class WizardTurnRequest(
    val draft: WizardDraft,
    val session: SessionState,
    val userTurn: TranscriptTurn,
)

data class WizardTurnResponse(
    val wizardMessage: String,
    val nextDraftStatus: DraftStatus = DraftStatus.IN_PROGRESS,
    val nextSessionPhase: SessionPhase = SessionPhase.AWAITING_USER_TURN,
)

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
