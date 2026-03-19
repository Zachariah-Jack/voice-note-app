package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NoteEditorUiState(
    val isLoading: Boolean = true,
    val isNewNote: Boolean = true,
    val title: String = "",
    val body: String = "",
    val tags: String = "",
    val isPinned: Boolean = false,
    val errorMessage: String? = null,
    val voiceStatus: String = "Ready",
    val justSaved: Boolean = false
)

class NoteEditorViewModel(
    private val repository: VoiceNotesRepository,
    private val jobId: Long,
    private val noteId: Long?
) : ViewModel() {
    private val _uiState = MutableStateFlow(NoteEditorUiState())
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()
    private var draftSaveJob: Job? = null

    init {
        viewModelScope.launch {
            if (noteId != null) {
                repository.observeNote(noteId).collect { note ->
                    if (note == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isNewNote = false,
                            errorMessage = "Note no longer exists."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isNewNote = false,
                            title = note.title,
                            body = note.body,
                            tags = note.tags.orEmpty(),
                            isPinned = note.isPinned
                        )
                    }
                }
            } else {
                repository.observeDraft(jobId).collect { draft ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isNewNote = true,
                        title = draft?.title.orEmpty(),
                        body = draft?.body.orEmpty(),
                        tags = draft?.tags.orEmpty()
                    )
                }
            }
        }
    }

    fun updateTitle(value: String) {
        _uiState.value = _uiState.value.copy(title = value, justSaved = false)
        scheduleAutoSave()
    }

    fun updateBody(value: String) {
        _uiState.value = _uiState.value.copy(body = value, justSaved = false)
        scheduleAutoSave()
    }

    fun updateTags(value: String) {
        _uiState.value = _uiState.value.copy(tags = value, justSaved = false)
        scheduleAutoSave()
    }

    fun togglePinned() {
        _uiState.value = _uiState.value.copy(isPinned = !_uiState.value.isPinned, justSaved = false)
    }

    fun setVoiceStatus(status: String) {
        _uiState.value = _uiState.value.copy(voiceStatus = status)
    }

    fun applyTranscript(transcript: String) {
        val current = _uiState.value
        val merged = if (current.body.isBlank()) transcript else current.body + "\n" + transcript
        _uiState.value = current.copy(body = merged, voiceStatus = "Transcript captured", justSaved = false)
        scheduleAutoSave()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    fun saveNote(onSaved: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            repository.saveNote(
                noteId = noteId,
                jobId = jobId,
                title = state.title,
                body = state.body,
                tags = state.tags,
                isPinned = state.isPinned
            )
            if (noteId == null) repository.clearDraft(jobId)
            _uiState.value = _uiState.value.copy(justSaved = true)
            onSaved()
        }
    }

    fun deleteNote(onDeleted: () -> Unit) {
        val id = noteId ?: return
        viewModelScope.launch {
            repository.deleteNote(id)
            onDeleted()
        }
    }

    private fun scheduleAutoSave() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(450)
            val state = _uiState.value
            if (noteId == null) {
                repository.saveDraft(jobId, state.title, state.body, state.tags)
            } else {
                repository.saveNote(
                    noteId = noteId,
                    jobId = jobId,
                    title = state.title,
                    body = state.body,
                    tags = state.tags,
                    isPinned = state.isPinned
                )
            }
        }
    }
}
