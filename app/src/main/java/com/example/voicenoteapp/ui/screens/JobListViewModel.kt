package com.example.voicenoteapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicenoteapp.data.db.JobEntity
import com.example.voicenoteapp.data.repo.VoiceNotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JobListViewModel(
    private val repository: VoiceNotesRepository
) : ViewModel() {
    private val showArchived = MutableStateFlow(false)

    val showArchivedState: StateFlow<Boolean> = showArchived

    val jobs: StateFlow<List<JobEntity>> = showArchived
        .flatMapLatest { repository.observeJobs(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setShowArchived(enabled: Boolean) {
        showArchived.value = enabled
    }

    fun addJob(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createJob(name)
        }
    }

    fun renameJob(job: JobEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renameJob(job, newName)
        }
    }

    fun setArchived(job: JobEntity, archived: Boolean) {
        viewModelScope.launch {
            repository.setJobArchived(job, archived)
        }
    }
}
