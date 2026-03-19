package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.data.db.VoiceDraftEntity
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UnsavedDraftsViewModel(
    private val repository: VoiceNotesRepository
) : ViewModel() {
    val drafts: StateFlow<List<VoiceDraftEntity>> = repository.observeVoiceDrafts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteDraft(draftId: Long) {
        viewModelScope.launch {
            repository.deleteVoiceDraft(draftId)
        }
    }
}
