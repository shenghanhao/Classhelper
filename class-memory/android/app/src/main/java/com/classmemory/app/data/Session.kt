package com.classmemory.app.data
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import androidx.room.Index
enum class SessionStatus { recording, completed, failed }
enum class ClassroomNoteKind { QUESTION, COMMENT }
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val title: String? = null,
    val courseType: String? = null,
    val endedAt: Long? = null,
    val status: SessionStatus,
    val durationSec: Long = 0,
    val finalCharCount: Int = 0,
    val lastHeartbeatAt: Long = startedAt,
    @ColumnInfo(defaultValue = "0") val isOrganized: Boolean = false,
)

@Entity(tableName = "classroom_notes", indices = [Index(value = ["sessionId", "atMs", "createdAt"])])
data class ClassroomNote(
    @PrimaryKey val id: String,
    val sessionId: String,
    val atMs: Long,
    val kind: ClassroomNoteKind,
    val text: String,
    val createdAt: Long,
)
