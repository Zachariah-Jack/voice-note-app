package app.voicenote.wizard

data class AppliedWizardTurn(
    val draft: WizardDraft,
    val session: SessionState,
)

object WizardTurnResponseApplier {
    fun apply(
        draft: WizardDraft,
        session: SessionState,
        response: WizardTurnResponse,
        wizardTurnId: String,
        nowEpochMillis: Long,
    ): AppliedWizardTurn {
        val wizardTurn = TranscriptTurn(
            id = wizardTurnId,
            speaker = TranscriptSpeaker.WIZARD,
            text = response.wizardMessage,
            createdAtEpochMillis = nowEpochMillis,
        )
        val updatedCreateTodo = draft.createTodo.copy(
            title = response.todoTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: draft.createTodo.title,
            executionRequested = response.createTodoRequested ?: draft.createTodo.executionRequested,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val updatedDraft = draft.copy(
            status = response.nextDraftStatus,
            transcript = draft.transcript + wizardTurn,
            createTodo = updatedCreateTodo,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val updatedSession = session.copy(
            draftId = updatedDraft.id,
            phase = response.nextSessionPhase,
            updatedAtEpochMillis = nowEpochMillis,
        )
        return AppliedWizardTurn(
            draft = updatedDraft,
            session = updatedSession,
        )
    }
}
