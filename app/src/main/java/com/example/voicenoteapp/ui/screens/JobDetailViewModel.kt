package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.data.db.JobEntity
import com.example.voicenoteapp.data.db.NoteEntity
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class JobDetailViewModel(
    private val repository: VoiceNotesRepository,
    val jobId: Long
) : ViewModel() {
    private val query = MutableStateFlow("")

    val searchQuery: StateFlow<String> = query

    val job: StateFlow<JobEntity?> = repository.observeJob(jobId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notes: StateFlow<List<NoteEntity>> = query
        .flatMapLatest { repository.observeNotes(jobId, it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(newQuery: String) {
        query.value = newQuery
    }
}
