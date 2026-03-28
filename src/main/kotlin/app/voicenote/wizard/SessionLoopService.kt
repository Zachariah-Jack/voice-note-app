package app.voicenote.wizard

class SessionLoopService(
    private val store: AppStateStore,
    private val wizardTurnClient: WizardTurnClient,
    private val idGenerator: IdGenerator = UuidIdGenerator,
    private val clock: EpochClock = SystemEpochClock,
) {
    fun startNewSession(): SessionSnapshot {
        val existingState = store.load()
        val now = clock.nowEpochMillis()
        val draft = WizardDraft(
            id = idGenerator.nextId("draft"),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val session = SessionState(
            draftId = draft.id,
            phase = SessionPhase.AWAITING_USER_TURN,
            updatedAtEpochMillis = now,
        )
        val updatedState = existingState.upsertDraft(draft).copy(session = session)
        store.save(updatedState)
        return SessionSnapshot(draft = draft, session = session)
    }

    fun resumeMostRecentUnfinishedDraft(): SessionSnapshot? {
        val existingState = store.load()
        val draft = existingState.drafts
            .filter { it.status == DraftStatus.IN_PROGRESS }
            .maxByOrNull { it.updatedAtEpochMillis }
            ?: return null

        val now = clock.nowEpochMillis()
        val resumedSession = SessionState(
            draftId = draft.id,
            phase = SessionPhase.AWAITING_USER_TURN,
            updatedAtEpochMillis = now,
        )
        store.save(existingState.copy(session = resumedSession))
        return SessionSnapshot(draft = draft, session = resumedSession)
    }

    fun submitUserTurn(text: String): SessionSnapshot {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "User turn text must not be blank." }

        val existingState = store.load()
        val activeDraftId = existingState.session.draftId
            ?: error("No active draft to append a turn to.")
        check(existingState.session.phase == SessionPhase.AWAITING_USER_TURN) {
            "Session is not ready for a user turn."
        }

        val draft = existingState.findDraft(activeDraftId)
        val userTurnTime = clock.nowEpochMillis()
        val userTurn = TranscriptTurn(
            id = idGenerator.nextId("turn"),
            speaker = TranscriptSpeaker.USER,
            text = normalizedText,
            createdAtEpochMillis = userTurnTime,
        )
        val draftWithUserTurn = draft.appendTurn(userTurn, userTurnTime)
        val generatingSession = existingState.session.copy(
            draftId = draft.id,
            phase = SessionPhase.RUNNING_WIZARD_TURN,
            updatedAtEpochMillis = userTurnTime,
        )
        val stateAfterUserTurn = existingState
            .upsertDraft(draftWithUserTurn)
            .copy(session = generatingSession)
        store.save(stateAfterUserTurn)

        val response = wizardTurnClient.runTurn(
            WizardTurnRequest(
                draft = draftWithUserTurn,
                session = generatingSession,
                userTurn = userTurn,
            ),
        )

        val appliedState = WizardTurnResponseApplier.apply(
            draft = draftWithUserTurn,
            session = generatingSession,
            response = response,
            wizardTurnId = idGenerator.nextId("turn"),
            nowEpochMillis = clock.nowEpochMillis(),
        )
        val finalState = stateAfterUserTurn
            .upsertDraft(appliedState.draft)
            .copy(session = appliedState.session)
        store.save(finalState)
        return SessionSnapshot(draft = appliedState.draft, session = appliedState.session)
    }
}
