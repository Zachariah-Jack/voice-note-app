package com.example.voicenoteapp.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndQueryJobAndNotes() = runBlocking {
        val now = System.currentTimeMillis()
        val jobId = db.jobDao().insert(JobEntity(name = "Test Job", createdAt = now, updatedAt = now))

        db.noteDao().insert(
            NoteEntity(
                jobId = jobId,
                title = "Safety note",
                body = "Check harness before climbing",
                createdAt = now,
                updatedAt = now,
                tags = "safety",
                isPinned = true
            )
        )

        val jobs = db.jobDao().observeJobs(showArchived = false).first()
        val notes = db.noteDao().observeNotesForJob(jobId, "harness").first()

        assertEquals(1, jobs.size)
        assertEquals("Test Job", jobs.first().name)
        assertEquals(1, notes.size)
        assertTrue(notes.first().isPinned)
    }
}
