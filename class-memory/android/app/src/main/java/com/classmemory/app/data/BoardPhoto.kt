package com.classmemory.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "board_photos", indices = [Index(value = ["sessionId", "capturedAtMs", "createdAt"])])
data class BoardPhoto(
    @PrimaryKey val id: String,
    val sessionId: String,
    val capturedAtMs: Long,
    val relativePath: String,
    val createdAt: Long,
)
