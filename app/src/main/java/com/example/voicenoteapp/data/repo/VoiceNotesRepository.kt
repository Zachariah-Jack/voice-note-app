package com.example.voicenoteapp.data.repo

import com.example.voicenoteapp.data.db.AppDatabase
import com.example.voicenoteapp.data.db.DraftEntity
import com.example.voicenoteapp.data.db.JobEntity
import com.example.voicenoteapp.data.db.NoteEntity
import com.example.voicenoteapp.data.db.VoiceDraftEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class VoiceNotesRepository(
    private val db: AppDatabase
) {
    fun observeJobs(showArchived: Boolean): Flow<List<JobEntity>> = db.jobDao().observeJobs(showArchived)

    fun observeJob(jobId: Long): Flow<JobEntity?> = db.jobDao().observeJob(jobId)

    suspend fun createJob(name: String): Long {
        val now = System.currentTimeMillis()
        return db.jobDao().insert(
            JobEntity(
                name = name.trim(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun renameJob(job: JobEntity, newName: String) {
        db.jobDao().update(
            job.copy(
                name = newName.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setJobArchived(job: JobEntity, archived: Boolean) {
        db.jobDao().update(
            job.copy(
                isArchived = archived,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeNotes(jobId: Long, query: String): Flow<List<NoteEntity>> =
        db.noteDao().observeNotesForJob(jobId, query)

    fun observeNote(noteId: Long): Flow<NoteEntity?> = db.noteDao().observeNoteById(noteId)

    suspend fun getNoteById(noteId: Long): NoteEntity? = db.noteDao().getNoteById(noteId)

    suspend fun saveNote(
        noteId: Long?,
        jobId: Long,
        title: String,
        body: String,
        tags: String,
        isPinned: Boolean
    ): Long {
        val now = System.currentTimeMillis()
        val normalizedTitle = deriveTitle(title, body)
        val cleanedTags = tags.trim().ifBlank { "" }

        return if (noteId == null) {
            db.noteDao().insert(
                NoteEntity(
                    jobId = jobId,
                    title = normalizedTitle,
                    body = body,
                    createdAt = now,
                    updatedAt = now,
                    tags = cleanedTags.ifBlank { null },
                    isPinned = isPinned
                )
            )
        } else {
            val existing = db.noteDao().getNoteById(noteId)
            if (existing != null) {
                db.noteDao().update(
                    existing.copy(
                        jobId = jobId,
                        title = normalizedTitle,
                        body = body,
                        updatedAt = now,
                        tags = cleanedTags.ifBlank { null },
                        isPinned = isPinned
                    )
                )
            }
            noteId
        }
    }

    suspend fun deleteNote(noteId: Long) {
        db.noteDao().deleteById(noteId)
    }

    fun observeDraft(jobId: Long): Flow<DraftEntity?> = db.draftDao().observeDraft(jobId)

    suspend fun saveDraft(jobId: Long, title: String, body: String, tags: String) {
        db.draftDao().upsertDraft(
            DraftEntity(
                jobId = jobId,
                title = title,
                body = body,
                tags = tags,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearDraft(jobId: Long) {
        db.draftDao().deleteDraft(jobId)
    }

    fun observeVoiceDrafts(): Flow<List<VoiceDraftEntity>> = db.draftDao().observeVoiceDrafts()

    fun observeVoiceDraftById(draftId: Long): Flow<VoiceDraftEntity?> = db.draftDao().observeVoiceDraftById(draftId)

    suspend fun getVoiceDraftById(draftId: Long): VoiceDraftEntity? = db.draftDao().getVoiceDraftById(draftId)

    suspend fun createVoiceDraft(existingNoteId: Long? = null, initialStep: String = "JOB"): Long {
        val now = System.currentTimeMillis()
        return db.draftDao().upsertVoiceDraft(
            VoiceDraftEntity(
                existingNoteId = existingNoteId,
                step = initialStep,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun upsertVoiceDraft(draft: VoiceDraftEntity): Long {
        return db.draftDao().upsertVoiceDraft(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteVoiceDraft(draftId: Long) {
        db.draftDao().deleteVoiceDraftById(draftId)
    }

    suspend fun countVoiceDrafts(): Int = db.draftDao().getVoiceDraftCount()

    suspend fun resolveOrCreateJob(jobInput: String, allowCreate: Boolean = true): JobEntity {
        val cleaned = cleanLabel(jobInput)
        val allJobs = db.jobDao().getAllJobs()
        val matched = findBestJobMatch(cleaned, allJobs)
        if (matched != null) return matched

        val fallback = if (cleaned.isBlank()) "Unassigned" else cleaned
        if (!allowCreate) {
            val unassigned = findBestJobMatch("Unassigned", allJobs)
            return unassigned ?: createAndLoadJob("Unassigned")
        }
        return createAndLoadJob(fallback)
    }

    private suspend fun createAndLoadJob(name: String): JobEntity {
        val id = createJob(name)
        return db.jobDao().observeJob(id).first()
            ?: JobEntity(
                id = id,
                name = name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
    }

    private fun findBestJobMatch(input: String, jobs: List<JobEntity>): JobEntity? {
        if (jobs.isEmpty()) return null
        val normalizedInput = normalize(input)
        if (normalizedInput.isBlank()) return null

        jobs.firstOrNull { normalize(it.name) == normalizedInput }?.let { return it }

        val tokenSet = normalizedInput.split(" ").filter { it.length >= 3 }.toSet()
        if (tokenSet.isNotEmpty()) {
            val scored = jobs.map { job ->
                val jobTokens = normalize(job.name).split(" ").filter { it.length >= 3 }.toSet()
                val overlap = tokenSet.intersect(jobTokens).size
                job to overlap
            }.filter { it.second > 0 }
            val best = scored.maxByOrNull { (_, score) -> score }
            if (best != null && best.second >= 2) return best.first
        }

        jobs.firstOrNull {
            val normalized = normalize(it.name)
            normalizedInput.length >= 4 && (normalized.contains(normalizedInput) || normalizedInput.contains(normalized))
        }?.let { return it }

        return null
    }

    private fun cleanLabel(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "")
            .trim()
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun deriveTitle(inputTitle: String, body: String): String {
        if (inputTitle.isNotBlank()) return inputTitle.trim()
        val fallback = body.trim().replace("\n", " ")
        return if (fallback.isBlank()) "Untitled note" else fallback.take(40)
    }
}
