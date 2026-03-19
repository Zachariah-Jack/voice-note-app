package com.example.voicenoteapp.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("jobId"), Index("updatedAt")]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tags: String? = null,
    val isPinned: Boolean = false
)

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val jobId: Long,
    val title: String,
    val body: String,
    val tags: String,
    val updatedAt: Long
)

@Entity(
    tableName = "voice_drafts",
    indices = [Index("updatedAt"), Index("existingNoteId")]
)
data class VoiceDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val existingNoteId: Long? = null,
    val jobName: String = "",
    val title: String = "",
    val body: String = "",
    val tags: String = "",
    val step: String = "JOB",
    val createdAt: Long,
    val updatedAt: Long
)
