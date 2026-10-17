package com.classmemory.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN courseType TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS course_types (name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(name))")
        seedDefaultCourseTypes(db)
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS board_photos (id TEXT NOT NULL, sessionId TEXT NOT NULL, capturedAtMs INTEGER NOT NULL, relativePath TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_board_photos_sessionId_capturedAtMs_createdAt ON board_photos (sessionId, capturedAtMs, createdAt)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS classroom_notes (id TEXT NOT NULL, sessionId TEXT NOT NULL, atMs INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_classroom_notes_sessionId_atMs_createdAt ON classroom_notes (sessionId, atMs, createdAt)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN isOrganized INTEGER NOT NULL DEFAULT 0")
    }
}

fun seedDefaultCourseTypes(db: SupportSQLiteDatabase) {
    DEFAULT_COURSE_TYPES.forEachIndexed { index, name ->
        db.execSQL(
            "INSERT OR IGNORE INTO course_types(name, createdAt) VALUES(?, ?)",
            arrayOf<Any?>(name, index.toLong()),
        )
    }
}
