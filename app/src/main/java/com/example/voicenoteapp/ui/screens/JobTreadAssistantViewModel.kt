package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.CreateTodoParseResult
import com.example.voicenoteapp.assistant.CreateTodoParser
import com.example.voicenoteapp.assistant.CreateTodoParserDescriptor
import com.example.voicenoteapp.assistant.CreateTodoParserMode
import com.example.voicenoteapp.jobtread.JobTreadCreateReadiness
import com.example.voicenoteapp.jobtread.JobTreadCreatedTodo
import com.example.voicenoteapp.jobtread.JobTreadLookupLoadResult
import com.example.voicenoteapp.jobtread.JobTreadLookupRepository
import com.example.voicenoteapp.jobtread.JobTreadLookupSnapshot
import com.example.voicenoteapp.jobtread.JobTreadOrganization
import com.example.voicenoteapp.jobtread.JobTreadResolutionSummary
import com.example.voicenoteapp.jobtread.JobTreadResolvers
import com.example.voicenoteapp.jobtread.JobTreadTodoCreateResult
import com.example.voicenoteapp.jobtread.JobTreadTodoRepository
import com.example.voicenoteapp.jobtread.toJobTreadTodoCreateInput
import com.example.voicenoteapp.settings.AssistantSettings
import com.example.voicenoteapp.settings.CredentialStore
import com.example.voicenoteapp.voice.SpeechParsing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class JobTreadAssistantStage {
    IDLE,
    PROMPTING,
    LISTENING,
    RESULT,
    ERROR
}

enum class JobTreadLookupStage {
    IDLE,
    LOADING,
    READY,
    ERROR
}

enum class JobTreadCreateStage {
    IDLE,
    SENDING,
    SUCCESS,
    ERROR
}

data class JobTreadAssistantUiState(
    val stage: JobTreadAssistantStage = JobTreadAssistantStage.IDLE,
    val prompt: String = "How can I help?",
    val transcript: String = "",
    val parsedIntent: CreateTodoIntent? = null,
    val parserMode: CreateTodoParserMode = CreateTodoParserMode.FALLBACK,
    val parserLabel: String = "",
    val settings: AssistantSettings = AssistantSettings(),
    val errorMessage: String? = null,
    val lookupStage: JobTreadLookupStage = JobTreadLookupStage.IDLE,
    val lookupSummary: JobTreadResolutionSummary? = null,
    val lookupErrorMessage: String? = null,
    val activeOrganization: JobTreadOrganization? = null,
    val availableOrganizations: List<JobTreadOrganization> = emptyList(),
    val organizationSelectionMessage: String? = null,
    val createStage: JobTreadCreateStage = JobTreadCreateStage.IDLE,
    val createErrorMessage: String? = null,
    val createdTodo: JobTreadCreatedTodo? = null,
    val captureNonce: Int = 0
) {
    val createReadiness: JobTreadCreateReadiness
        get() = JobTreadResolvers.determineCreateReadiness(
            intent = parsedIntent,
            hasJobTreadConfig = settings.hasJobTreadConfig,
            lookupInFlight = lookupStage == JobTreadLookupStage.LOADING,
            lookupErrorMessage = lookupErrorMessage,
            summary = lookupSummary,
            organizationSelectionRequired = organizationSelectionMessage != null
        )

    val canSubmitCreate: Boolean
        get() = parsedIntent != null &&
            createReadiness == JobTreadCreateReadiness.READY &&
            createStage != JobTreadCreateStage.SENDING &&
            createStage != JobTreadCreateStage.SUCCESS
}

class JobTreadAssistantViewModel(
    private val credentialStore: CredentialStore,
    private val createTodoParser: CreateTodoParser,
    private val jobTreadLookupRepository: JobTreadLookupRepository,
    private val jobTreadTodoRepository: JobTreadTodoRepository
) : ViewModel() {
    private val initialDescriptor = createTodoParser.describe(AssistantSettings())
    private val _uiState = MutableStateFlow(
        JobTreadAssistantUiState(
            parserMode = initialDescriptor.mode,
            parserLabel = initialDescriptor.parserLabel
        )
    )
    val uiState = _uiState.asStateFlow()
    private var currentSettings = AssistantSettings()

    init {
        viewModelScope.launch {
            credentialStore.settings.collect { settings ->
                currentSettings = settings
                val descriptor = createTodoParser.describe(settings)
                _uiState.update {
                    it.copy(
                        settings = settings,
                        parserMode = descriptor.mode,
                        parserLabel = descriptor.parserLabel
                    )
                }
            }
        }
    }

    fun startCapture() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.PROMPTING,
            transcript = "",
            parsedIntent = null,
            errorMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null,
            activeOrganization = null,
            availableOrganizations = emptyList(),
            organizationSelectionMessage = null,
            createStage = JobTreadCreateStage.IDLE,
            createErrorMessage = null,
            createdTodo = null,
            captureNonce = _uiState.value.captureNonce + 1
        )
    }

    fun onListeningStarted() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.LISTENING,
            errorMessage = null,
            createErrorMessage = null
        )
    }

    fun onSpeechResult(transcript: String) {
        val cleaned = SpeechParsing.clean(transcript)
        if (cleaned.isBlank()) {
            onNoTranscript()
            return
        }

        _uiState.value = _uiState.value.copy(
            transcript = cleaned,
            parsedIntent = null,
            errorMessage = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null,
            activeOrganization = null,
            availableOrganizations = emptyList(),
            organizationSelectionMessage = null,
            createStage = JobTreadCreateStage.IDLE,
            createErrorMessage = null,
            createdTodo = null
        )

        viewModelScope.launch {
            when (val result = createTodoParser.parse(cleaned, currentSettings)) {
                is CreateTodoParseResult.Success -> {
                    val parsedIntent = result.intent
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.RESULT,
                            parsedIntent = parsedIntent,
                            errorMessage = null,
                            lookupStage = if (currentSettings.hasJobTreadConfig) {
                                JobTreadLookupStage.LOADING
                            } else {
                                JobTreadLookupStage.IDLE
                            }
                        )
                    }
                    resolveLookups(parsedIntent)
                }

                is CreateTodoParseResult.MissingConfiguration -> {
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message
                        )
                    }
                }

                is CreateTodoParseResult.Failure -> {
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.ERROR,
                            parsedIntent = null,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun onSpeechError(message: String) {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = message,
            transcript = "",
            parsedIntent = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null,
            activeOrganization = null,
            availableOrganizations = emptyList(),
            organizationSelectionMessage = null,
            createStage = JobTreadCreateStage.IDLE,
            createErrorMessage = null,
            createdTodo = null
        )
    }

    fun onNoTranscript() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = "No transcript was captured. Try again.",
            transcript = "",
            parsedIntent = null,
            lookupStage = JobTreadLookupStage.IDLE,
            lookupSummary = null,
            lookupErrorMessage = null,
            activeOrganization = null,
            availableOrganizations = emptyList(),
            organizationSelectionMessage = null,
            createStage = JobTreadCreateStage.IDLE,
            createErrorMessage = null,
            createdTodo = null
        )
    }

    fun onConfirmCreate() {
        val state = _uiState.value
        val parsedIntent = state.parsedIntent ?: run {
            _uiState.update {
                it.copy(
                    createStage = JobTreadCreateStage.ERROR,
                    createErrorMessage = "Nothing is ready to send yet."
                )
            }
            return
        }

        if (state.createReadiness != JobTreadCreateReadiness.READY) {
            _uiState.update {
                it.copy(
                    createStage = JobTreadCreateStage.ERROR,
                    createErrorMessage = when (state.createReadiness) {
                        JobTreadCreateReadiness.BLOCKED_ORGANIZATION_SELECTION_REQUIRED ->
                            state.organizationSelectionMessage ?: state.createReadiness.label

                        else -> state.createReadiness.label
                    }
                )
            }
            return
        }

        val createInput = parsedIntent.toJobTreadTodoCreateInput(state.lookupSummary)
        if (createInput == null) {
            _uiState.update {
                it.copy(
                    createStage = JobTreadCreateStage.ERROR,
                    createErrorMessage = "The parsed request is still missing a title, so it cannot be sent."
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                createStage = JobTreadCreateStage.SENDING,
                createErrorMessage = null,
                createdTodo = null
            )
        }

        viewModelScope.launch {
            when (val result = jobTreadTodoRepository.createTodo(createInput, currentSettings)) {
                is JobTreadTodoCreateResult.Success -> {
                    _uiState.update {
                        it.copy(
                            createStage = JobTreadCreateStage.SUCCESS,
                            createErrorMessage = null,
                            createdTodo = result.todo
                        )
                    }
                }

                is JobTreadTodoCreateResult.MissingConfiguration -> {
                    _uiState.update {
                        it.copy(
                            createStage = JobTreadCreateStage.ERROR,
                            createErrorMessage = result.message,
                            createdTodo = null
                        )
                    }
                }

                is JobTreadTodoCreateResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            createStage = JobTreadCreateStage.ERROR,
                            createErrorMessage = result.message,
                            createdTodo = null
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveLookups(intent: CreateTodoIntent) {
        if (!currentSettings.hasJobTreadConfig) {
            return
        }

        _uiState.update {
            it.copy(
                lookupStage = JobTreadLookupStage.LOADING,
                lookupSummary = null,
                lookupErrorMessage = null,
                activeOrganization = null,
                availableOrganizations = emptyList(),
                organizationSelectionMessage = null
            )
        }

        when (val result = jobTreadLookupRepository.loadLookupSnapshot(currentSettings)) {
            is JobTreadLookupLoadResult.Success -> {
                applyLookupSuccess(intent, result.snapshot)
                if (result.shouldPersistSelection) {
                    persistSelectedOrganization(result.snapshot.organization)
                }
            }

            is JobTreadLookupLoadResult.MissingConfiguration -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.ERROR,
                        lookupSummary = null,
                        lookupErrorMessage = result.message,
                        activeOrganization = null,
                        availableOrganizations = emptyList(),
                        organizationSelectionMessage = null
                    )
                }
            }

            is JobTreadLookupLoadResult.SelectionRequired -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.READY,
                        lookupSummary = null,
                        lookupErrorMessage = null,
                        activeOrganization = null,
                        availableOrganizations = result.organizations,
                        organizationSelectionMessage = result.message
                    )
                }
            }

            is JobTreadLookupLoadResult.Failure -> {
                _uiState.update {
                    it.copy(
                        lookupStage = JobTreadLookupStage.ERROR,
                        lookupSummary = null,
                        lookupErrorMessage = result.message,
                        activeOrganization = null,
                        availableOrganizations = emptyList(),
                        organizationSelectionMessage = null
                    )
                }
            }
        }
    }

    private fun applyLookupSuccess(
        intent: CreateTodoIntent,
        snapshot: JobTreadLookupSnapshot
    ) {
        _uiState.update {
            it.copy(
                lookupStage = JobTreadLookupStage.READY,
                lookupSummary = JobTreadResolvers.resolve(intent, snapshot),
                lookupErrorMessage = null,
                activeOrganization = snapshot.organization,
                availableOrganizations = snapshot.availableOrganizations,
                organizationSelectionMessage = null
            )
        }
    }

    private fun persistSelectedOrganization(organization: JobTreadOrganization) {
        viewModelScope.launch {
            val latestSettings = currentSettings
            if (
                latestSettings.jobTreadOrganizationId == organization.id &&
                latestSettings.jobTreadOrganizationName == organization.name
            ) {
                return@launch
            }

            credentialStore.save(
                latestSettings.copy(
                    jobTreadOrganizationId = organization.id,
                    jobTreadOrganizationName = organization.name
                )
            )
        }
    }

    private fun applyParserResult(
        descriptor: CreateTodoParserDescriptor,
        update: JobTreadAssistantUiState.() -> JobTreadAssistantUiState
    ) {
        _uiState.update { current ->
            with(
                current.copy(
                    parserMode = descriptor.mode,
                    parserLabel = descriptor.parserLabel
                ),
                update
            )
        }
    }
}
