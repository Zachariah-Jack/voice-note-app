package app.voicenote.wizard

class VoiceTurnController(
    private val store: AppStateStore,
    private val sessionLoopService: SessionLoopService,
    private val speechRecognizerGateway: SpeechRecognizerGateway,
    private val clock: EpochClock = SystemEpochClock,
) {
    private val lock = Any()
    private var listenAfterAssistantCompletion = false

    init {
        sessionLoopService.setAssistantSpeakerEventObserver(::handleAssistantSpeakerEvent)
        speechRecognizerGateway.setEventListener(SpeechRecognitionEventListener(::handleSpeechRecognitionEvent))
    }

    fun armNextVoiceTurn() {
        synchronized(lock) {
            listenAfterAssistantCompletion = true
        }
    }

    fun stop() {
        synchronized(lock) {
            listenAfterAssistantCompletion = false
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
            listenAfterAssistantCompletion = false
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
            listenAfterAssistantCompletion = false
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
        val shouldStartListening = synchronized(lock) {
            when (event.type) {
                AssistantSpeakerEventType.DONE -> listenAfterAssistantCompletion
                AssistantSpeakerEventType.ERROR,
                AssistantSpeakerEventType.STOPPED,
                -> {
                    listenAfterAssistantCompletion = false
                    false
                }

                AssistantSpeakerEventType.STARTED -> false
            }
        }

        if (!shouldStartListening) {
            return
        }

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
                } catch (exception: Exception) {
                    synchronized(lock) {
                        listenAfterAssistantCompletion = false
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

            SpeechRecognizerAvailability.PERMISSION_NEEDED -> {
                synchronized(lock) {
                    listenAfterAssistantCompletion = false
                }
                persistRecognitionState { session ->
                    session.copy(
                        phase = SessionPhase.AWAITING_USER_TURN,
                        speechRecognition = SpeechRecognitionState(
                            status = SpeechRecognitionStatus.PERMISSION_NEEDED,
                            errorType = SpeechRecognitionEventType.PERMISSION_REQUIRED,
                        ),
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                    )
                }
            }

            SpeechRecognizerAvailability.UNAVAILABLE -> {
                synchronized(lock) {
                    listenAfterAssistantCompletion = false
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

    private fun handleSpeechRecognitionEvent(event: SpeechRecognitionEvent) {
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
                synchronized(lock) {
                    listenAfterAssistantCompletion = false
                }
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
                sessionLoopService.submitUserTurn(transcript)
            }

            SpeechRecognitionEventType.NO_MATCH -> finishRecognitionWithError(
                status = SpeechRecognitionStatus.NO_MATCH,
                eventType = SpeechRecognitionEventType.NO_MATCH,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.TIMEOUT -> finishRecognitionWithError(
                status = SpeechRecognitionStatus.TIMEOUT,
                eventType = SpeechRecognitionEventType.TIMEOUT,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.BUSY -> finishRecognitionWithError(
                status = SpeechRecognitionStatus.BUSY,
                eventType = SpeechRecognitionEventType.BUSY,
                errorCode = event.errorCode,
            )

            SpeechRecognitionEventType.PERMISSION_REQUIRED -> finishRecognitionWithError(
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

    private fun finishRecognitionWithError(
        status: SpeechRecognitionStatus,
        eventType: SpeechRecognitionEventType,
        errorCode: Int?,
    ) {
        synchronized(lock) {
            listenAfterAssistantCompletion = false
        }
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

    private fun persistRecognitionState(update: (SessionState) -> SessionState) {
        val state = store.load()
        val updatedSession = update(state.session)
        if (updatedSession != state.session) {
            store.save(state.copy(session = updatedSession))
        }
    }
}
