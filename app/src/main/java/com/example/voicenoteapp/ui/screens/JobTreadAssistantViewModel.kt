package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.assistant.CreateTodoIntent
import com.example.voicenoteapp.assistant.CreateTodoParserDescriptor
import com.example.voicenoteapp.assistant.CreateTodoParserMode
import com.example.voicenoteapp.assistant.CreateTodoParseResult
import com.example.voicenoteapp.assistant.CreateTodoParser
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

data class JobTreadAssistantUiState(
    val stage: JobTreadAssistantStage = JobTreadAssistantStage.IDLE,
    val prompt: String = "How can I help?",
    val transcript: String = "",
    val parsedIntent: CreateTodoIntent? = null,
    val parserMode: CreateTodoParserMode = CreateTodoParserMode.FALLBACK,
    val parserLabel: String = "",
    val settings: AssistantSettings = AssistantSettings(),
    val errorMessage: String? = null,
    val placeholderMessage: String? = null,
    val captureNonce: Int = 0
) {
    val canConfirmPlaceholder: Boolean
        get() = parsedIntent != null && parsedIntent.missingFields.isEmpty() && settings.hasJobTreadConfig
}

class JobTreadAssistantViewModel(
    credentialStore: CredentialStore,
    private val createTodoParser: CreateTodoParser
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
            placeholderMessage = null,
            captureNonce = _uiState.value.captureNonce + 1
        )
    }

    fun onListeningStarted() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.LISTENING,
            errorMessage = null,
            placeholderMessage = null
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
            placeholderMessage = null
        )

        viewModelScope.launch {
            when (val result = createTodoParser.parse(cleaned, currentSettings)) {
                is CreateTodoParseResult.Success -> {
                    applyParserResult(result.descriptor) {
                        copy(
                            stage = JobTreadAssistantStage.RESULT,
                            parsedIntent = result.intent,
                            errorMessage = null
                        )
                    }
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
            placeholderMessage = null
        )
    }

    fun onNoTranscript() {
        _uiState.value = _uiState.value.copy(
            stage = JobTreadAssistantStage.ERROR,
            errorMessage = "No transcript was captured. Try again.",
            transcript = "",
            parsedIntent = null,
            placeholderMessage = null
        )
    }

    fun onConfirmPlaceholder() {
        _uiState.value = _uiState.value.copy(
            placeholderMessage = if (_uiState.value.settings.hasJobTreadConfig) {
                "JobTread API hookup comes next. This confirmation is local-only for now."
            } else {
                "JobTread API settings are still missing. Save the base URL and API key in Settings before the next stage."
            }
        )
    }

    private fun applyParserResult(
        descriptor: CreateTodoParserDescriptor,
        update: JobTreadAssistantUiState.() -> JobTreadAssistantUiState
    ) {
        _uiState.update {
            with(
                it.copy(
                parserMode = descriptor.mode,
                parserLabel = descriptor.parserLabel
                ),
                update
            )
        }
    }
}
