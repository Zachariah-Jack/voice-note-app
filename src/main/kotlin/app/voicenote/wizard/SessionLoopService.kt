package app.voicenote.wizard

class SessionLoopService(
    private val store: AppStateStore,
    private val wizardTurnClient: WizardTurnClient,
    private val assistantSpeaker: AssistantSpeaker? = null,
    private val idGenerator: IdGenerator = UuidIdGenerator,
    private val clock: EpochClock = SystemEpochClock,
) {
    private var assistantSpeakerEventObserver: ((AssistantSpeakerEvent) -> Unit)? = null

    init {
        assistantSpeaker?.setEventListener(AssistantSpeakerEventListener(::handleAssistantSpeakerEvent))
    }

    @Synchronized
    fun setAssistantSpeakerEventObserver(observer: ((AssistantSpeakerEvent) -> Unit)?) {
        assistantSpeakerEventObserver = observer
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
    fun submitUserTurn(text: String): SessionSnapshot {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "User turn text must not be blank." }

        val existingState = store.load()
        val activeDraftId = existingState.session.draftId
            ?: error("No active draft to append a turn to.")
        check(
            existingState.session.phase == SessionPhase.AWAITING_USER_TURN ||
                existingState.session.phase == SessionPhase.LISTENING_USER,
        ) {
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
            speechRecognition = SpeechRecognitionState(),
            updatedAtEpochMillis = userTurnTime,
        )
        val stateAfterUserTurn = existingState
            .upsertDraft(draftWithUserTurn)
            .copy(session = generatingSession)
        store.save(stateAfterUserTurn)

        try {
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
            return queueAssistantSpeechIfPresent(finalState)
        } catch (exception: Exception) {
            val recoveredSession = generatingSession.copy(
                phase = SessionPhase.AWAITING_USER_TURN,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )
            store.save(stateAfterUserTurn.copy(session = recoveredSession))
            throw exception
        }
    }

    @Synchronized
    fun stopAssistantSpeech(): SessionSnapshot? {
        val currentState = store.load()
        val draftId = currentState.session.draftId ?: return null
        val currentDraft = currentState.findDraft(draftId)
        val currentSpeech = currentState.session.assistantSpeech
        val activeUtteranceId = currentSpeech.utteranceId
            ?: return SessionSnapshot(draft = currentDraft, session = currentState.session)

        val stopRequestedSession = currentState.session.copy(
            phase = SessionPhase.SPEAKING_ASSISTANT,
            assistantSpeech = currentSpeech.copy(
                status = AssistantSpeechStatus.STOPPING,
                utteranceId = activeUtteranceId,
                errorCode = null,
                errorMessage = null,
            ),
            updatedAtEpochMillis = clock.nowEpochMillis(),
        )
        store.save(currentState.copy(session = stopRequestedSession))

        try {
            assistantSpeaker?.stop()
        } catch (exception: Exception) {
            val recoveredSession = stopRequestedSession.completeAssistantSpeech(
                status = AssistantSpeechStatus.ERROR,
                updatedAtEpochMillis = clock.nowEpochMillis(),
                errorMessage = exception.message,
            )
            store.save(currentState.copy(session = recoveredSession))
            return SessionSnapshot(draft = currentDraft, session = recoveredSession)
        }

        return SessionSnapshot(draft = currentDraft, session = stopRequestedSession)
    }

    @Synchronized
    fun releaseAssistantSpeaker() {
        val currentState = store.load()
        try {
            assistantSpeaker?.release()
        } finally {
            assistantSpeaker?.setEventListener(null)
        }

        val draftId = currentState.session.draftId ?: return
        val currentDraft = currentState.findDraft(draftId)
        val clearedSession = currentState.session.clearAssistantSpeech(clock.nowEpochMillis())
        if (clearedSession != currentState.session) {
            store.save(currentState.copy(session = clearedSession))
        }
        SessionSnapshot(draft = currentDraft, session = clearedSession)
    }

    private fun queueAssistantSpeechIfPresent(state: WizardAppState): SessionSnapshot {
        val draftId = state.session.draftId ?: return snapshotFrom(state)
        val currentDraft = state.findDraft(draftId)
        val latestWizardTurn = currentDraft.transcript.lastOrNull()
            ?.takeIf { it.speaker == TranscriptSpeaker.WIZARD }
            ?: return snapshotFrom(state)
        val speaker = assistantSpeaker ?: return snapshotFrom(state)

        val utteranceId = idGenerator.nextId("utterance")
        val speechRequestedSession = state.session.requestAssistantSpeech(
            utteranceId = utteranceId,
            updatedAtEpochMillis = clock.nowEpochMillis(),
        )
        val speechRequestedState = state.copy(session = speechRequestedSession)
        store.save(speechRequestedState)

        return try {
            speaker.speak(
                AssistantSpeechRequest(
                    utteranceId = utteranceId,
                    text = latestWizardTurn.text,
                ),
            )
            SessionSnapshot(draft = currentDraft, session = speechRequestedSession)
        } catch (exception: Exception) {
            val recoveredSession = speechRequestedSession.completeAssistantSpeech(
                status = AssistantSpeechStatus.ERROR,
                updatedAtEpochMillis = clock.nowEpochMillis(),
                errorMessage = exception.message,
            )
            val recoveredState = speechRequestedState.copy(session = recoveredSession)
            store.save(recoveredState)
            SessionSnapshot(draft = currentDraft, session = recoveredSession)
        }
    }

    private fun handleAssistantSpeakerEvent(event: AssistantSpeakerEvent) {
        synchronized(this) {
            val currentState = store.load()
            val expectedUtteranceId = currentState.session.assistantSpeech.utteranceId ?: return
            if (expectedUtteranceId != event.utteranceId) {
                return
            }

            val updatedSession = when (event.type) {
                AssistantSpeakerEventType.STARTED -> currentState.session.copy(
                    phase = SessionPhase.SPEAKING_ASSISTANT,
                    assistantSpeech = currentState.session.assistantSpeech.copy(
                        status = AssistantSpeechStatus.SPEAKING,
                        errorCode = null,
                        errorMessage = null,
                    ),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )

                AssistantSpeakerEventType.DONE -> currentState.session.completeAssistantSpeech(
                    status = AssistantSpeechStatus.IDLE,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )

                AssistantSpeakerEventType.STOPPED -> currentState.session.completeAssistantSpeech(
                    status = AssistantSpeechStatus.STOPPED,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )

                AssistantSpeakerEventType.ERROR -> currentState.session.completeAssistantSpeech(
                    status = AssistantSpeechStatus.ERROR,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                    errorCode = event.errorCode,
                )
            }
            store.save(currentState.copy(session = updatedSession))
            assistantSpeakerEventObserver?.invoke(event)
        }
    }

    private fun snapshotFrom(state: WizardAppState): SessionSnapshot {
        val draftId = state.session.draftId ?: error("No active draft is available.")
        return SessionSnapshot(
            draft = state.findDraft(draftId),
            session = state.session,
        )
    }
}

private fun SessionState.requestAssistantSpeech(
    utteranceId: String,
    updatedAtEpochMillis: Long,
): SessionState = copy(
    phase = SessionPhase.SPEAKING_ASSISTANT,
    assistantSpeech = AssistantSpeechState(
        status = AssistantSpeechStatus.REQUESTED,
        utteranceId = utteranceId,
        nextPhase = phase,
    ),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SessionState.completeAssistantSpeech(
    status: AssistantSpeechStatus,
    updatedAtEpochMillis: Long,
    errorCode: Int? = null,
    errorMessage: String? = null,
): SessionState = copy(
    phase = assistantSpeech.nextPhase,
    assistantSpeech = AssistantSpeechState(
        status = status,
        nextPhase = assistantSpeech.nextPhase,
        errorCode = errorCode,
        errorMessage = errorMessage,
    ),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SessionState.clearAssistantSpeech(updatedAtEpochMillis: Long): SessionState =
    if (assistantSpeech == AssistantSpeechState() && phase != SessionPhase.SPEAKING_ASSISTANT) {
        this
    } else {
        copy(
            phase = if (phase == SessionPhase.SPEAKING_ASSISTANT) {
            assistantSpeech.nextPhase
        } else {
            phase
        },
        assistantSpeech = AssistantSpeechState(),
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
    }
