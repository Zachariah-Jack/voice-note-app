package com.example.voicenoteapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Query(
        """
        SELECT * FROM jobs
        WHERE (:showArchived = 1 OR isArchived = 0)
        ORDER BY isArchived ASC, updatedAt DESC
        """
    )
    fun observeJobs(showArchived: Boolean): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    fun observeJob(jobId: Long): Flow<JobEntity?>

    @Query("SELECT * FROM jobs")
    suspend fun getAllJobs(): List<JobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity)
}

@Dao
interface NoteDao {
    @Query(
        """
        SELECT * FROM notes
        WHERE jobId = :jobId
          AND (
            :query = '' OR
            title LIKE '%' || :query || '%' OR
            body LIKE '%' || :query || '%' OR
            ifnull(tags, '') LIKE '%' || :query || '%'
          )
        ORDER BY isPinned DESC, updatedAt DESC
        """
    )
    fun observeNotesForJob(jobId: Long, query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    fun observeNoteById(noteId: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun getNoteById(noteId: Long): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteById(noteId: Long)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE jobId = :jobId LIMIT 1")
    fun observeDraft(jobId: Long): Flow<DraftEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE jobId = :jobId")
    suspend fun deleteDraft(jobId: Long)

    @Query("SELECT * FROM voice_drafts ORDER BY updatedAt DESC")
    fun observeVoiceDrafts(): Flow<List<VoiceDraftEntity>>

    @Query("SELECT * FROM voice_drafts WHERE id = :draftId LIMIT 1")
    fun observeVoiceDraftById(draftId: Long): Flow<VoiceDraftEntity?>

    @Query("SELECT * FROM voice_drafts WHERE id = :draftId LIMIT 1")
    suspend fun getVoiceDraftById(draftId: Long): VoiceDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVoiceDraft(draft: VoiceDraftEntity): Long

    @Query("DELETE FROM voice_drafts WHERE id = :draftId")
    suspend fun deleteVoiceDraftById(draftId: Long)

    @Query("SELECT COUNT(*) FROM voice_drafts")
    suspend fun getVoiceDraftCount(): Int
}
