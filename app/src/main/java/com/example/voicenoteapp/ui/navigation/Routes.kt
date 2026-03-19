package com.example.voicenoteapp.ui.navigation

sealed class Route(val route: String) {
    data object DriveHome : Route("drive_home")
    data object UnsavedDrafts : Route("unsaved_drafts")
    data object JobList : Route("job_list")
    data object Settings : Route("settings")
    data object JobDetail : Route("job_detail/{jobId}") {
        fun create(jobId: Long) = "job_detail/$jobId"
    }

    data object NoteEditor : Route("note_editor/{jobId}/{noteId}") {
        fun create(jobId: Long, noteId: Long?) = "note_editor/$jobId/${noteId ?: -1L}"
    }

    data object VoiceWizard : Route("voice_wizard/{draftId}/{noteId}") {
        fun create(draftId: Long?, noteId: Long?) = "voice_wizard/${draftId ?: -1L}/${noteId ?: -1L}"
    }
}
