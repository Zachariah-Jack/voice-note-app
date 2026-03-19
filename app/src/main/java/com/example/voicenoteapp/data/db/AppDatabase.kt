package com.example.voicenoteapp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [JobEntity::class, NoteEntity::class, DraftEntity::class, VoiceDraftEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun noteDao(): NoteDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val DB_NAME = "voice_note_app.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS drafts (
                        jobId INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS voice_drafts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        existingNoteId INTEGER,
                        jobName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        step TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voice_drafts_updatedAt ON voice_drafts(updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voice_drafts_existingNoteId ON voice_drafts(existingNoteId)")
            }
        }
    }
}
