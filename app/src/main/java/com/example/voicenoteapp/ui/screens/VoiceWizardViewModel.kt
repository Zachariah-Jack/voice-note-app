package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.data.db.VoiceDraftEntity
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import com.example.voicenoteapp.voice.BinaryResponse
import com.example.voicenoteapp.voice.EditChoice
import com.example.voicenoteapp.voice.SpeechParsing
import com.example.voicenoteapp.voice.TagSuggestionEngine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class WizardStep {
    JOB,
    TITLE,
    NOTE,
    TAGS,
    EDIT_CONFIRM,
    EDIT_CHOICE,
    EDIT_JOB,
    EDIT_TITLE,
    EDIT_NOTE,
    COMPLETE
}

data class VoiceWizardUiState(
    val isLoading: Boolean = true,
    val draftId: Long? = null,
    val existingNoteId: Long? = null,
    val currentStep: WizardStep = WizardStep.JOB,
    val currentPrompt: String = "",
    val promptVersion: Int = 0,
    val voiceStatus: String = "Ready",
    val errorMessage: String? = null,
    val jobName: String = "",
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val suggestedTags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val shouldExit: Boolean = false,
    val completionMessage: String? = null
) {
    val hasAnyContent: Boolean
        get() = jobName.isNotBlank() || title.isNotBlank() || body.isNotBlank() || tags.isNotEmpty()
}

class VoiceWizardViewModel(
    private val repository: VoiceNotesRepository,
    private val initialDraftId: Long?,
    private val initialExistingNoteId: Long?
) : ViewModel() {
    private var _uiState = kotlinx.coroutines.flow.MutableStateFlow(VoiceWizardUiState())
    val uiState = _uiState.asStateFlow()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            when {
                initialDraftId != null -> loadFromDraft(initialDraftId)
                initialExistingNoteId != null -> loadFromExistingNote(initialExistingNoteId)
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    transitionTo(WizardStep.JOB)
                }
            }
        }
    }

    private suspend fun loadFromDraft(draftId: Long) {
        val draft = repository.getVoiceDraftById(draftId)
        if (draft == null) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            transitionTo(WizardStep.JOB)
            return
        }
        val restoredStep = runCatching { WizardStep.valueOf(draft.step) }.getOrDefault(WizardStep.JOB)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            draftId = draft.id,
            existingNoteId = draft.existingNoteId,
            jobName = draft.jobName,
            title = draft.title,
            body = draft.body,
            tags = draft.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        )
        transitionTo(restoredStep)
    }

    private suspend fun loadFromExistingNote(noteId: Long) {
        val note = repository.getNoteById(noteId)
        if (note == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Note not found."
            )
            transitionTo(WizardStep.JOB)
            return
        }
        val job = repository.observeJob(note.jobId).first()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            existingNoteId = noteId,
            jobName = job?.name.orEmpty(),
            title = note.title,
            body = note.body,
            tags = note.tags.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() },
            isPinned = note.isPinned
        )
        transitionTo(WizardStep.EDIT_CONFIRM)
    }

    fun onSpeechResult(transcript: String) {
        val heard = SpeechParsing.clean(transcript)
        if (heard.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "I did not catch that. Please try again.")
            return
        }
        _uiState.value = _uiState.value.copy(errorMessage = null, voiceStatus = "Processing...")
        when (_uiState.value.currentStep) {
            WizardStep.JOB, WizardStep.EDIT_JOB -> {
                val parsed = SpeechParsing.parseJobName(heard).ifBlank { heard }
                _uiState.value = _uiState.value.copy(jobName = parsed)
                persistDraftSnapshot()
                transitionTo(if (_uiState.value.currentStep == WizardStep.JOB) WizardStep.TITLE else WizardStep.EDIT_CONFIRM)
            }

            WizardStep.TITLE, WizardStep.EDIT_TITLE -> {
                val parsed = SpeechParsing.parseTitle(heard).ifBlank { heard }
                _uiState.value = _uiState.value.copy(title = parsed)
                persistDraftSnapshot()
                transitionTo(if (_uiState.value.currentStep == WizardStep.TITLE) WizardStep.NOTE else WizardStep.EDIT_CONFIRM)
            }

            WizardStep.NOTE, WizardStep.EDIT_NOTE -> {
                val parsed = SpeechParsing.parseBody(heard).ifBlank { heard }
                _uiState.value = _uiState.value.copy(body = parsed)
                persistDraftSnapshot()
                transitionTo(if (_uiState.value.currentStep == WizardStep.NOTE) WizardStep.TAGS else WizardStep.EDIT_CONFIRM)
            }

            WizardStep.TAGS -> {
                val suggestions = _uiState.value.suggestedTags
                val parsedTags = SpeechParsing.extractTags(heard, suggestions)
                val resolvedTags = if (parsedTags.isEmpty() && !heard.lowercase().contains("skip")) _uiState.value.tags else parsedTags
                _uiState.value = _uiState.value.copy(tags = resolvedTags)
                persistDraftSnapshot()
                transitionTo(WizardStep.EDIT_CONFIRM)
            }

            WizardStep.EDIT_CONFIRM -> {
                when (SpeechParsing.parseBinaryResponse(heard)) {
                    BinaryResponse.YES -> transitionTo(WizardStep.EDIT_CHOICE)
                    BinaryResponse.NO -> finalizeNote()
                    BinaryResponse.UNKNOWN -> {
                        when (SpeechParsing.parseEditChoice(heard)) {
                            EditChoice.JOB -> transitionTo(WizardStep.EDIT_JOB)
                            EditChoice.TITLE -> transitionTo(WizardStep.EDIT_TITLE)
                            EditChoice.NOTE -> transitionTo(WizardStep.EDIT_NOTE)
                            EditChoice.NONE -> finalizeNote()
                            EditChoice.CANCEL -> saveDraftAndExit("Draft saved. You can continue later.")
                            EditChoice.UNKNOWN -> transitionTo(WizardStep.EDIT_CHOICE)
                        }
                    }
                }
            }

            WizardStep.EDIT_CHOICE -> {
                when (SpeechParsing.parseEditChoice(heard)) {
                    EditChoice.JOB -> transitionTo(WizardStep.EDIT_JOB)
                    EditChoice.TITLE -> transitionTo(WizardStep.EDIT_TITLE)
                    EditChoice.NOTE -> transitionTo(WizardStep.EDIT_NOTE)
                    EditChoice.NONE -> finalizeNote()
                    EditChoice.CANCEL -> saveDraftAndExit("Draft saved. You can continue later.")
                    EditChoice.UNKNOWN -> {
                        _uiState.value = _uiState.value.copy(errorMessage = "Please say Edit Job Name, Edit Title, Edit Note, or No Edits.")
                        transitionTo(WizardStep.EDIT_CHOICE)
                    }
                }
            }

            WizardStep.COMPLETE -> Unit
        }
    }

    fun onSpeechTimeout() {
        if (_uiState.value.hasAnyContent) {
            saveDraftAndExit("Draft saved due to inactivity.")
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = "No speech detected. Please try again.")
        }
    }

    fun onSpeechError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message, voiceStatus = "Ready")
    }

    fun setVoiceStatus(status: String) {
        _uiState.value = _uiState.value.copy(voiceStatus = status)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun persistForBackground() {
        if (_uiState.value.hasAnyContent) {
            persistDraftSnapshot()
        }
    }

    fun markExitConsumed() {
        _uiState.value = _uiState.value.copy(shouldExit = false)
    }

    private fun transitionTo(step: WizardStep) {
        val prompt = buildPrompt(step)
        _uiState.value = _uiState.value.copy(
            currentStep = step,
            currentPrompt = prompt,
            promptVersion = _uiState.value.promptVersion + 1,
            voiceStatus = "Ready"
        )
    }

    private fun buildPrompt(step: WizardStep): String {
        return when (step) {
            WizardStep.JOB -> "What is the job name?"
            WizardStep.TITLE -> "What is the subject?"
            WizardStep.NOTE -> "What is the note?"
            WizardStep.TAGS -> {
                val suggestions = TagSuggestionEngine.buildSuggestions(_uiState.value.title, _uiState.value.body)
                _uiState.value = _uiState.value.copy(suggestedTags = suggestions)
                if (suggestions.isEmpty()) {
                    "Would you like to add any tags? Say tags now or say skip tags."
                } else {
                    val numbered = suggestions.mapIndexed { index, value -> "${index + 1} $value" }.joinToString(", ")
                    "Would you like to add any tags? I suggest $numbered. Say numbers, names, or skip tags."
                }
            }

            WizardStep.EDIT_CONFIRM -> {
                "I captured job ${_uiState.value.jobName.ifBlank { "Unassigned" }}, subject ${_uiState.value.title.ifBlank { "Untitled" }}. Any edits?"
            }

            WizardStep.EDIT_CHOICE -> "Edit Job Name, Edit Title, Edit Note, or No Edits?"
            WizardStep.EDIT_JOB -> "Please say the updated job name."
            WizardStep.EDIT_TITLE -> "Please say the updated subject."
            WizardStep.EDIT_NOTE -> "Please say the updated note."
            WizardStep.COMPLETE -> _uiState.value.completionMessage ?: "Saved."
        }
    }

    private fun finalizeNote() {
        viewModelScope.launch {
            val state = _uiState.value
            val resolvedJob = repository.resolveOrCreateJob(state.jobName, allowCreate = true)
            repository.saveNote(
                noteId = state.existingNoteId,
                jobId = resolvedJob.id,
                title = state.title,
                body = state.body,
                tags = state.tags.joinToString(", "),
                isPinned = state.isPinned
            )
            state.draftId?.let { repository.deleteVoiceDraft(it) }
            _uiState.value = _uiState.value.copy(
                completionMessage = "Saved to ${resolvedJob.name}.",
                shouldExit = true
            )
            transitionTo(WizardStep.COMPLETE)
        }
    }

    private fun saveDraftAndExit(message: String) {
        viewModelScope.launch {
            persistDraftSnapshotInternal()
            _uiState.value = _uiState.value.copy(
                completionMessage = message,
                shouldExit = true
            )
        }
    }

    private fun persistDraftSnapshot() {
        viewModelScope.launch {
            persistDraftSnapshotInternal()
        }
    }

    private suspend fun persistDraftSnapshotInternal() {
        val state = _uiState.value
        if (!state.hasAnyContent) return

        val existingDraft = state.draftId?.let { repository.getVoiceDraftById(it) }
        val now = System.currentTimeMillis()
        val draftId = state.draftId ?: repository.createVoiceDraft(
            existingNoteId = state.existingNoteId,
            initialStep = state.currentStep.name
        )

        repository.upsertVoiceDraft(
            VoiceDraftEntity(
                id = draftId,
                existingNoteId = state.existingNoteId,
                jobName = state.jobName,
                title = state.title,
                body = state.body,
                tags = state.tags.joinToString(", "),
                step = state.currentStep.name,
                createdAt = existingDraft?.createdAt ?: now,
                updatedAt = now
            )
        )

        if (_uiState.value.draftId == null) {
            _uiState.value = _uiState.value.copy(draftId = draftId)
        }
    }
}
