package app.voicenote.wizard

class SessionLoopService(
    private val store: AppStateStore,
    private val wizardTurnClient: WizardTurnClient,
    private val jobTreadLookupRepository: JobTreadLookupRepository? = null,
    private val jobTreadCreateTodoRepository: JobTreadCreateTodoRepository? = null,
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

        return resumeDraft(existingState, draft)
    }

    @Synchronized
    fun resumeDraft(draftId: String): SessionSnapshot? {
        val existingState = store.load()
        val draft = existingState.drafts
            .firstOrNull { it.id == draftId && it.status == DraftStatus.IN_PROGRESS }
            ?: return null

        return resumeDraft(existingState, draft)
    }

    private fun resumeDraft(
        existingState: WizardAppState,
        draft: WizardDraft,
    ): SessionSnapshot {
        val now = clock.nowEpochMillis()
        val recalculatedDraft = recalculateCreateTodoDraft(
            draft = draft,
            organizationSelection = existingState.jobTreadOrganizationSelection,
            nowEpochMillis = now,
        )
        val resumedSession = SessionState(
            draftId = recalculatedDraft.id,
            phase = SessionPhase.AWAITING_USER_TURN,
            updatedAtEpochMillis = now,
        )
        val updatedState = existingState
            .upsertDraft(recalculatedDraft)
            .copy(session = resumedSession)
        store.save(updatedState)
        return SessionSnapshot(draft = recalculatedDraft, session = resumedSession)
    }

    @Synchronized
    fun speakAssistantMessage(text: String): SessionSnapshot {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "Assistant speech text must not be blank." }

        val state = store.load()
        val activeDraftId = state.session.draftId
            ?: error("No active draft is available.")
        val currentDraft = state.findDraft(activeDraftId)
        return queueAssistantSpeech(
            state = state,
            draft = currentDraft,
            message = normalizedText,
        )
    }

    @Synchronized
    fun refreshJobTreadOrganizations(): JobTreadOrganizationSelectionState {
        val state = store.load()
        val refreshedSelection = try {
            jobTreadLookupRepository?.refreshOrganizationSelection(state.jobTreadOrganizationSelection)
                ?: state.jobTreadOrganizationSelection.copy(
                    status = JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION,
                    failureMessage = "JobTread lookup repository is not configured.",
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
        } catch (exception: Exception) {
            state.jobTreadOrganizationSelection.copy(
                status = JobTreadOrganizationSelectionStatus.FAILURE,
                failureMessage = exception.message ?: exception::class.java.simpleName,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )
        }
        val updatedState = recalculateCreateTodoDrafts(
            state = state.copy(jobTreadOrganizationSelection = refreshedSelection),
            nowEpochMillis = clock.nowEpochMillis(),
        )
        store.save(updatedState)
        return updatedState.jobTreadOrganizationSelection
    }

    @Synchronized
    fun saveDefaultJobTreadOrganization(organizationId: String): JobTreadOrganizationSelectionState {
        val state = store.load()
        val updatedSelection = state.jobTreadOrganizationSelection.saveDefaultOrganization(
            organizationId = organizationId,
            updatedAtEpochMillis = clock.nowEpochMillis(),
        )
        val updatedState = recalculateCreateTodoDrafts(
            state = state.copy(jobTreadOrganizationSelection = updatedSelection),
            nowEpochMillis = clock.nowEpochMillis(),
        )
        store.save(updatedState)
        return updatedState.jobTreadOrganizationSelection
    }

    @Synchronized
    fun updateCreateTodoConfirmation(
        draftId: String,
        confirmed: Boolean,
    ): WizardDraft? {
        val state = store.load()
        val draft = state.drafts.firstOrNull { it.id == draftId } ?: return null
        val updatedDraft = CreateTodoReviewStateCalculator.updateConfirmation(
            draft = draft,
            organizationSelection = state.jobTreadOrganizationSelection,
            confirmed = confirmed,
            nowEpochMillis = clock.nowEpochMillis(),
        )
        val updatedState = state.upsertDraft(updatedDraft)
        store.save(updatedState)
        return updatedDraft
    }

    @Synchronized
    fun executeConfirmedCreateTodo(draftId: String): WizardDraft? {
        val state = store.load()
        val draft = state.drafts.firstOrNull { it.id == draftId } ?: return null
        val now = clock.nowEpochMillis()
        val recalculatedDraft = recalculateCreateTodoDraft(
            draft = draft,
            organizationSelection = state.jobTreadOrganizationSelection,
            nowEpochMillis = now,
        )
        val preparedState = state.upsertDraft(recalculatedDraft)
        if (recalculatedDraft != draft) {
            store.save(preparedState)
        }

        when (recalculatedDraft.createTodo.execution.status) {
            CreateTodoExecutionStatus.SENDING ->
                error("A create_todo request is already being sent for this draft.")
            CreateTodoExecutionStatus.SUCCESS ->
                error("This create_todo draft has already been sent successfully.")
            CreateTodoExecutionStatus.IDLE,
            CreateTodoExecutionStatus.FAILURE,
            -> Unit
        }

        val input = CreateTodoExecutionInputFactory.build(recalculatedDraft)
            ?: error("Local create_todo review must be ready and confirmed before sending.")
        val summary = recalculatedDraft.createTodo.confirmationSummary
            ?: error("Local create_todo confirmation summary is missing.")

        val sendingDraft = recalculatedDraft.withCreateTodoExecution(
            execution = CreateTodoExecutionState(
                status = CreateTodoExecutionStatus.SENDING,
                requestSummary = summary,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            ),
            nowEpochMillis = clock.nowEpochMillis(),
        )
        val sendingState = preparedState.upsertDraft(sendingDraft)
        store.save(sendingState)

        return try {
            val createdTodo = jobTreadCreateTodoRepository?.createTodo(input)
                ?: throw JobTreadCreateTodoException("JobTread create_todo repository is not configured.")
            val successfulDraft = recalculateCreateTodoDraft(
                draft = sendingDraft.withCreateTodoExecution(
                    execution = CreateTodoExecutionState(
                        status = CreateTodoExecutionStatus.SUCCESS,
                        requestSummary = summary,
                        createdTodo = createdTodo,
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                    ),
                    nowEpochMillis = clock.nowEpochMillis(),
                ),
                organizationSelection = sendingState.jobTreadOrganizationSelection,
                nowEpochMillis = clock.nowEpochMillis(),
            )
            store.save(sendingState.upsertDraft(successfulDraft))
            successfulDraft
        } catch (exception: Exception) {
            val failedDraft = recalculateCreateTodoDraft(
                draft = sendingDraft.withCreateTodoExecution(
                    execution = CreateTodoExecutionState(
                        status = CreateTodoExecutionStatus.FAILURE,
                        requestSummary = summary,
                        failureMessage = exception.message ?: exception::class.java.simpleName,
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                    ),
                    nowEpochMillis = clock.nowEpochMillis(),
                ),
                organizationSelection = sendingState.jobTreadOrganizationSelection,
                nowEpochMillis = clock.nowEpochMillis(),
            )
            store.save(sendingState.upsertDraft(failedDraft))
            failedDraft
        }
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
            val recalculatedDraft = recalculateCreateTodoDraft(
                draft = appliedState.draft,
                organizationSelection = stateAfterUserTurn.jobTreadOrganizationSelection,
                nowEpochMillis = clock.nowEpochMillis(),
            )
            val stateAfterWizardTurn = stateAfterUserTurn
                .upsertDraft(recalculatedDraft)
                .copy(session = appliedState.session)
            store.save(stateAfterWizardTurn)

            val stateAfterLookup = applyJobTreadLookupIfRequested(
                state = stateAfterWizardTurn,
                draft = recalculatedDraft,
                requestedReferenceText = response.jobLookupQuery,
            )
            return queueAssistantSpeechIfPresent(stateAfterLookup)
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
        return queueAssistantSpeech(
            state = state,
            draft = currentDraft,
            message = latestWizardTurn.text,
        )
    }

    private fun queueAssistantSpeech(
        state: WizardAppState,
        draft: WizardDraft,
        message: String,
    ): SessionSnapshot {
        val speaker = assistantSpeaker ?: return SessionSnapshot(draft = draft, session = state.session)
        val utteranceId = idGenerator.nextId("utterance")
        val speechRequestedSession = state.session.requestAssistantSpeech(
            utteranceId = utteranceId,
            message = message,
            updatedAtEpochMillis = clock.nowEpochMillis(),
        )
        val speechRequestedState = state.copy(session = speechRequestedSession)
        store.save(speechRequestedState)

        return try {
            speaker.speak(
                AssistantSpeechRequest(
                    utteranceId = utteranceId,
                    text = message,
                ),
            )
            SessionSnapshot(draft = draft, session = speechRequestedSession)
        } catch (exception: Exception) {
            val recoveredSession = speechRequestedSession.completeAssistantSpeech(
                status = AssistantSpeechStatus.ERROR,
                updatedAtEpochMillis = clock.nowEpochMillis(),
                errorMessage = exception.message,
            )
            val recoveredState = speechRequestedState.copy(session = recoveredSession)
            store.save(recoveredState)
            SessionSnapshot(draft = draft, session = recoveredSession)
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

    private fun applyJobTreadLookupIfRequested(
        state: WizardAppState,
        draft: WizardDraft,
        requestedReferenceText: String?,
    ): WizardAppState {
        val normalizedReference = requestedReferenceText?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return state

        val lookupExecution = try {
            jobTreadLookupRepository?.resolveJobReference(
                referenceText = normalizedReference,
                currentSelection = state.jobTreadOrganizationSelection,
            ) ?: JobTreadLookupExecution(
                organizationSelection = state.jobTreadOrganizationSelection.copy(
                    status = JobTreadOrganizationSelectionStatus.MISSING_CONFIGURATION,
                    failureMessage = "JobTread lookup repository is not configured.",
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
                lookupState = JobTreadLookupState(
                    requestedReferenceText = normalizedReference,
                    snapshotStatus = JobTreadSnapshotStatus.CONFIG_MISSING,
                    resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                    failureMessage = "JobTread lookup repository is not configured.",
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
        } catch (exception: Exception) {
            JobTreadLookupExecution(
                organizationSelection = state.jobTreadOrganizationSelection.copy(
                    status = JobTreadOrganizationSelectionStatus.FAILURE,
                    failureMessage = exception.message ?: exception::class.java.simpleName,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
                lookupState = JobTreadLookupState(
                    requestedReferenceText = normalizedReference,
                    snapshotStatus = JobTreadSnapshotStatus.FAILED,
                    resolutionStatus = JobTreadResolutionStatus.UNRESOLVED,
                    failureMessage = exception.message ?: exception::class.java.simpleName,
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                ),
            )
        }

        val updatedDraft = draft.copy(
            jobTreadLookup = lookupExecution.lookupState,
            updatedAtEpochMillis = clock.nowEpochMillis(),
        )
        val recalculatedDraft = recalculateCreateTodoDraft(
            draft = updatedDraft,
            organizationSelection = lookupExecution.organizationSelection,
            nowEpochMillis = clock.nowEpochMillis(),
        )
        val updatedState = state
            .upsertDraft(recalculatedDraft)
            .copy(jobTreadOrganizationSelection = lookupExecution.organizationSelection)
        store.save(updatedState)
        return updatedState
    }

    private fun recalculateCreateTodoDrafts(
        state: WizardAppState,
        nowEpochMillis: Long,
    ): WizardAppState {
        val recalculatedDrafts = state.drafts.map { draft ->
            recalculateCreateTodoDraft(
                draft = draft,
                organizationSelection = state.jobTreadOrganizationSelection,
                nowEpochMillis = nowEpochMillis,
            )
        }
        return state.copy(drafts = recalculatedDrafts.sortedBy(WizardDraft::createdAtEpochMillis))
    }

    private fun recalculateCreateTodoDraft(
        draft: WizardDraft,
        organizationSelection: JobTreadOrganizationSelectionState,
        nowEpochMillis: Long,
    ): WizardDraft = CreateTodoReviewStateCalculator.recalculate(
        draft = draft,
        organizationSelection = organizationSelection,
        nowEpochMillis = nowEpochMillis,
    )
}

private fun WizardDraft.withCreateTodoExecution(
    execution: CreateTodoExecutionState,
    nowEpochMillis: Long,
): WizardDraft = copy(
    createTodo = createTodo.copy(
        execution = execution,
        updatedAtEpochMillis = nowEpochMillis,
    ),
    updatedAtEpochMillis = nowEpochMillis,
)

private fun SessionState.requestAssistantSpeech(
    utteranceId: String,
    message: String,
    updatedAtEpochMillis: Long,
): SessionState = copy(
    phase = SessionPhase.SPEAKING_ASSISTANT,
    assistantSpeech = AssistantSpeechState(
        status = AssistantSpeechStatus.REQUESTED,
        utteranceId = utteranceId,
        message = message,
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
        message = assistantSpeech.message,
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
