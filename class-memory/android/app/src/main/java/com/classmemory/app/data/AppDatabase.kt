package com.classmemory.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

class DbConverters {
    @TypeConverter fun statusToString(v: SessionStatus) = v.name
    @TypeConverter fun stringToStatus(v: String) = SessionStatus.valueOf(v)
    @TypeConverter fun noteKindToString(v: ClassroomNoteKind) = v.name
    @TypeConverter fun stringToNoteKind(v: String) = ClassroomNoteKind.valueOf(v)
}
@Dao interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(session: Session)
    @Query("UPDATE sessions SET status = :status, endedAt = :endedAt, durationSec = :durationSec, finalCharCount = :chars WHERE id = :id") suspend fun finish(id: String, status: SessionStatus, endedAt: Long, durationSec: Long, chars: Int): Int
    @Query("UPDATE sessions SET status = 'failed', endedAt = lastHeartbeatAt, durationSec = MAX(0, (lastHeartbeatAt - startedAt) / 1000) WHERE status = 'recording'") suspend fun failAbandoned(): Int
    @Query("UPDATE sessions SET lastHeartbeatAt = :at WHERE id = :id AND status = 'recording'") suspend fun heartbeat(id: String, at: Long): Int
    @Query("UPDATE sessions SET finalCharCount = finalCharCount + :chars WHERE id = :id") suspend fun addFinalChars(id: String, chars: Int): Int
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun observeAll(): Flow<List<Session>>
    @Query("SELECT * FROM sessions WHERE id = :id") fun observe(id: String): Flow<Session?>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun find(id: String): Session?
    @Query("UPDATE sessions SET durationSec = :durationSec, finalCharCount = :chars, endedAt = :endedAt, lastHeartbeatAt = :endedAt, isOrganized = 0 WHERE id = :id") suspend fun updateAfterAppend(id: String, durationSec: Long, chars: Int, endedAt: Long): Int
    @Query("UPDATE sessions SET title = :title, courseType = :courseType WHERE id = :id") suspend fun updateMetadata(id: String, title: String, courseType: String?): Int
    @Query("UPDATE sessions SET isOrganized = :isOrganized WHERE id = :id") suspend fun updateOrganized(id: String, isOrganized: Boolean): Int
    @Query("UPDATE sessions SET courseType = NULL WHERE courseType = :courseType") suspend fun clearCourseType(courseType: String): Int
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String): Int
    @Query("SELECT startedAt FROM sessions WHERE id = :id") suspend fun startedAt(id: String): Long
}
@Dao interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(segment: TranscriptSegment): Long
    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMs, endMs, createdAt") fun observeFor(sessionId: String): Flow<List<TranscriptSegment>>
    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMs, endMs, createdAt") suspend fun snapshotFor(sessionId: String): List<TranscriptSegment>
    @Query("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM transcript_segments WHERE sessionId = :sessionId") suspend fun charCount(sessionId: String): Int
    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: String): Int
}
@Dao interface BoardPhotoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(photo: BoardPhoto)
    @Query("SELECT * FROM board_photos WHERE sessionId = :sessionId ORDER BY capturedAtMs, createdAt, id")
    fun observeFor(sessionId: String): Flow<List<BoardPhoto>>
    @Query("SELECT * FROM board_photos WHERE sessionId = :sessionId ORDER BY capturedAtMs, createdAt, id") suspend fun snapshotFor(sessionId: String): List<BoardPhoto>
    @Query("SELECT * FROM board_photos WHERE id = :id") suspend fun find(id: String): BoardPhoto?
    @Query("DELETE FROM board_photos WHERE id = :id AND sessionId = :sessionId") suspend fun delete(id: String, sessionId: String): Int
    @Query("SELECT COUNT(*) FROM board_photos WHERE relativePath = :relativePath") suspend fun countByRelativePath(relativePath: String): Int
    @Query("SELECT COUNT(*) FROM board_photos WHERE id IN (:ids)") suspend fun countByIds(ids: List<String>): Int
    @Query("DELETE FROM board_photos WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: String): Int
}
@Dao interface ClassroomNoteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(note: ClassroomNote): Long
    @Query("SELECT * FROM classroom_notes WHERE id = :id") suspend fun find(id: String): ClassroomNote?
    @Query("SELECT * FROM classroom_notes WHERE sessionId = :sessionId ORDER BY atMs, createdAt, id") suspend fun snapshotFor(sessionId: String): List<ClassroomNote>
    @Query("SELECT * FROM classroom_notes WHERE sessionId = :sessionId ORDER BY atMs, createdAt, id") fun observeFor(sessionId: String): Flow<List<ClassroomNote>>
    @Query("DELETE FROM classroom_notes WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: String): Int
}
@Dao interface CourseTypeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(courseType: CourseType): Long
    @Query("SELECT * FROM course_types ORDER BY createdAt, name") fun observeAll(): Flow<List<CourseType>>
    @Query("SELECT EXISTS(SELECT 1 FROM course_types WHERE name = :name)") suspend fun exists(name: String): Boolean
    @Query("DELETE FROM course_types WHERE name = :name") suspend fun delete(name: String): Int
}
@Database(entities = [Session::class, TranscriptSegment::class, CourseType::class, BoardPhoto::class, ClassroomNote::class], version = 6, exportSchema = false)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun segments(): SegmentDao
    abstract fun courseTypes(): CourseTypeDao
    abstract fun boardPhotos(): BoardPhotoDao
    abstract fun classroomNotes(): ClassroomNoteDao
}
