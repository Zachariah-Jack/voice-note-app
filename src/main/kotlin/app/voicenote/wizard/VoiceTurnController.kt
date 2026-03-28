package app.voicenote.wizard

import java.util.concurrent.Executor

class VoiceTurnController(
    private val store: AppStateStore,
    private val sessionLoopService: SessionLoopService,
    private val speechRecognizerGateway: SpeechRecognizerGateway,
    private val clock: EpochClock = SystemEpochClock,
    private val eventExecutor: Executor = DirectExecutor,
    private val turnExecutor: Executor = DirectExecutor,
) {
    private val lock = Any()
    private var continuousLoopEnabled = false

    init {
        sessionLoopService.setAssistantSpeakerEventObserver { event ->
            eventExecutor.execute {
                handleAssistantSpeakerEvent(event)
            }
        }
        speechRecognizerGateway.setEventListener(
            SpeechRecognitionEventListener { event ->
                eventExecutor.execute {
                    handleSpeechRecognitionEvent(event)
                }
            },
        )
    }

    fun armNextVoiceTurn() {
        synchronized(lock) {
            continuousLoopEnabled = true
        }
    }

    fun startSession(initialAssistantMessage: String): SessionSnapshot {
        armNextVoiceTurn()
        sessionLoopService.startNewSession()
        return sessionLoopService.speakAssistantMessage(initialAssistantMessage)
    }

    fun resumeSession(): SessionSnapshot? {
        synchronized(lock) {
            continuousLoopEnabled = true
        }
        val state = store.load()
        val draftId = state.session.draftId ?: return null
        val draft = state.findDraft(draftId)

        when (state.session.phase) {
            SessionPhase.SPEAKING_ASSISTANT,
            SessionPhase.LISTENING_USER,
            SessionPhase.RUNNING_WIZARD_TURN,
            -> return SessionSnapshot(draft = draft, session = state.session)

            SessionPhase.IDLE,
            SessionPhase.AWAITING_USER_TURN,
            -> {
                startListeningCycle()
                val updatedState = store.load()
                return SessionSnapshot(
                    draft = updatedState.findDraft(draftId),
                    session = updatedState.session,
                )
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            continuousLoopEnabled = false
        }
        sessionLoopService.stopAssistantSpeech()
        speechRecognizerGateway.stopListening()
        persistRecognitionState { session ->
            if (session.phase != SessionPhase.LISTENING_USER) {
                session
            } else {
                session.copy(
                    phase = SessionPhase.AWAITING_USER_TURN,
                    speechRecognition = SpeechRecognitionState(
                        status = SpeechRecognitionStatus.STOPPED,
                    ),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            continuousLoopEnabled = false
        }
        sessionLoopService.stopAssistantSpeech()
        speechRecognizerGateway.cancel()
        persistRecognitionState { session ->
            if (session.phase != SessionPhase.LISTENING_USER) {
                session
            } else {
                session.copy(
                    phase = SessionPhase.AWAITING_USER_TURN,
                    speechRecognition = SpeechRecognitionState(
                        status = SpeechRecognitionStatus.CANCELLED,
                    ),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
            }
        }
    }

    fun release() {
        synchronized(lock) {
            continuousLoopEnabled = false
        }
        sessionLoopService.setAssistantSpeakerEventObserver(null)
        speechRecognizerGateway.setEventListener(null)
        speechRecognizerGateway.release()
        sessionLoopService.releaseAssistantSpeaker()
        persistRecognitionState { session ->
            if (session.speechRecognition == SpeechRecognitionState() && session.phase != SessionPhase.LISTENING_USER) {
                session
            } else {
                session.copy(
                    phase = if (session.phase == SessionPhase.LISTENING_USER) {
                        SessionPhase.AWAITING_USER_TURN
                    } else {
                        session.phase
                    },
                    speechRecognition = SpeechRecognitionState(),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
            }
        }
    }

    private fun handleAssistantSpeakerEvent(event: AssistantSpeakerEvent) {
        when (event.type) {
            AssistantSpeakerEventType.STARTED -> Unit
            AssistantSpeakerEventType.DONE -> if (isContinuousLoopEnabled()) {
                startListeningCycle()
            }
            AssistantSpeakerEventType.ERROR,
            AssistantSpeakerEventType.STOPPED,
            -> synchronized(lock) {
                continuousLoopEnabled = false
            }
        }
    }

    private fun handleSpeechRecognitionEvent(event: SpeechRecognitionEvent) {
        if (!shouldHandleRecognitionEvent(event.type)) {
            return
        }

        when (event.type) {
            SpeechRecognitionEventType.LISTENING_STARTED -> persistRecognitionState { session ->
                session.copy(
                    phase = SessionPhase.LISTENING_USER,
                    speechRecognition = session.speechRecognition.copy(
                        status = SpeechRecognitionStatus.LISTENING,
                    ),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
            }

            SpeechRecognitionEventType.PARTIAL_TRANSCRIPT -> persistRecognitionState { session ->
                session.copy(
                    phase = SessionPhase.LISTENING_USER,
                    speechRecognition = session.speechRecognition.copy(
                        status = SpeechRecognitionStatus.LISTENING,
                        partialTranscript = event.transcript,
                        finalTranscript = null,
                        errorType = null,
                        errorCode = null,
                    ),
                    updatedAtEpochMillis = clock.nowEpochMillis(),
                )
            }

            SpeechRecognitionEventType.FINAL_TRANSCRIPT -> {
                val transcript = event.transcript?.trim().takeUnless { it.isNullOrEmpty() } ?: return
                persistRecognitionState { session ->
                    session.copy(
                        phase = SessionPhase.LISTENING_USER,
                        speechRecognition = session.speechRecognition.copy(
                            status = SpeechRecognitionStatus.FINAL_RECEIVED,
                            partialTranscript = null,
                            finalTranscript = transcript,
                            errorType = null,
                            errorCode = null,
                        ),
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                    )
                }
                turnExecutor.execute {
                    try {
                        sessionLoopService.submitUserTurn(transcript)
                    } catch (_: Exception) {
                        synchronized(lock) {
                            continuousLoopEnabled = false
                        }
                        persistRecognitionState { session ->
                            session.copy(
                                phase = SessionPhase.AWAITING_USER_TURN,
                                speechRecognition = SpeechRecognitionState(
                                    status = SpeechRecognitionStatus.ERROR,
                                    errorType = SpeechRecognitionEventType.ERROR,
                                ),
                                updatedAtEpochMillis = clock.nowEpochMillis(),
                            )
                        }
                    }
                }
            }

            SpeechRecognitionEventType.NO_MATCH -> recoverRecognitionCycle(
                status = SpeechRecognitionStatus.NO_MATCH,
                eventType = SpeechRecognitionEventType.NO_MATCH,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.TIMEOUT -> recoverRecognitionCycle(
                status = SpeechRecognitionStatus.TIMEOUT,
                eventType = SpeechRecognitionEventType.TIMEOUT,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.BUSY -> recoverRecognitionCycle(
                status = SpeechRecognitionStatus.BUSY,
                eventType = SpeechRecognitionEventType.BUSY,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.PERMISSION_REQUIRED -> pauseForRecoverableRecognitionError(
                status = SpeechRecognitionStatus.PERMISSION_NEEDED,
                eventType = SpeechRecognitionEventType.PERMISSION_REQUIRED,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.ERROR -> finishRecognitionWithError(
                status = SpeechRecognitionStatus.ERROR,
                eventType = SpeechRecognitionEventType.ERROR,
                errorCode = event.errorCode,
            )
        }
    }

    private fun startListeningCycle() {
        when (speechRecognizerGateway.checkAvailability()) {
            SpeechRecognizerAvailability.AVAILABLE -> {
                persistRecognitionState { session ->
                    session.copy(
                        phase = SessionPhase.LISTENING_USER,
                        speechRecognition = SpeechRecognitionState(
                            status = SpeechRecognitionStatus.LISTENING,
                        ),
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                    )
                }
                try {
                    speechRecognizerGateway.startListening()
                } catch (_: Exception) {
                    finishRecognitionWithError(
                        status = SpeechRecognitionStatus.ERROR,
                        eventType = SpeechRecognitionEventType.ERROR,
                        errorCode = null,
                    )
                }
            }

            SpeechRecognizerAvailability.PERMISSION_NEEDED -> pauseForRecoverableRecognitionError(
                status = SpeechRecognitionStatus.PERMISSION_NEEDED,
                eventType = SpeechRecognitionEventType.PERMISSION_REQUIRED,
                errorCode = null,
            )

            SpeechRecognizerAvailability.UNAVAILABLE -> finishRecognitionWithError(
                status = SpeechRecognitionStatus.ERROR,
                eventType = SpeechRecognitionEventType.ERROR,
                errorCode = null,
            )
        }
    }

    private fun recoverRecognitionCycle(
        status: SpeechRecognitionStatus,
        eventType: SpeechRecognitionEventType,
        errorCode: Int?,
    ) {
        pauseForRecoverableRecognitionError(status, eventType, errorCode)
        if (isContinuousLoopEnabled()) {
            startListeningCycle()
        }
    }

    private fun pauseForRecoverableRecognitionError(
        status: SpeechRecognitionStatus,
        eventType: SpeechRecognitionEventType,
        errorCode: Int?,
    ) {
        persistRecognitionState { session ->
            session.copy(
                phase = SessionPhase.AWAITING_USER_TURN,
                speechRecognition = SpeechRecognitionState(
                    status = status,
                    errorType = eventType,
                    errorCode = errorCode,
                ),
                updatedAtEpochMillis = clock.nowEpochMillis(),
            )
        }
    }

    private fun finishRecognitionWithError(
        status: SpeechRecognitionStatus,
        eventType: SpeechRecognitionEventType,
        errorCode: Int?,
    ) {
        synchronized(lock) {
            continuousLoopEnabled = false
        }
        pauseForRecoverableRecognitionError(status, eventType, errorCode)
    }

    private fun shouldHandleRecognitionEvent(type: SpeechRecognitionEventType): Boolean {
        val session = store.load().session
        if (session.phase != SessionPhase.LISTENING_USER) {
            return false
        }
        return when (type) {
            SpeechRecognitionEventType.LISTENING_STARTED,
            SpeechRecognitionEventType.PARTIAL_TRANSCRIPT,
            SpeechRecognitionEventType.FINAL_TRANSCRIPT,
            SpeechRecognitionEventType.NO_MATCH,
            SpeechRecognitionEventType.TIMEOUT,
            SpeechRecognitionEventType.BUSY,
            SpeechRecognitionEventType.PERMISSION_REQUIRED,
            SpeechRecognitionEventType.ERROR,
            -> session.speechRecognition.status != SpeechRecognitionStatus.FINAL_RECEIVED
        }
    }

    private fun isContinuousLoopEnabled(): Boolean = synchronized(lock) { continuousLoopEnabled }

    private fun persistRecognitionState(update: (SessionState) -> SessionState) {
        val state = store.load()
        val updatedSession = update(state.session)
        if (updatedSession != state.session) {
            store.save(state.copy(session = updatedSession))
        }
    }
}
