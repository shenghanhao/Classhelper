package com.classmemory.app.repository

import com.classmemory.app.data.*
import com.classmemory.app.i18n.tr
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionRepository(
    private val db: AppDatabase,
    private val boardPhotoRoot: File? = null,
) {
    private val recoveryLock = Mutex()
    private val fileMutationLock = Mutex()
    private var recoveryComplete = false

    suspend fun recoverAbandonedSessions(now: Long = System.currentTimeMillis()): Int = recoveryLock.withLock {
        if (recoveryComplete) return@withLock 0
        db.sessions().failAbandoned().also { recoveryComplete = true }
    }

    suspend fun begin(id: String, startedAt: Long) {
        recoveryLock.withLock {
            if (!recoveryComplete) {
                db.sessions().failAbandoned()
                recoveryComplete = true
            }
            db.sessions().insert(Session(id = id, startedAt = startedAt, status = SessionStatus.recording))
        }
    }

    suspend fun heartbeat(id: String, at: Long = System.currentTimeMillis()) = db.sessions().heartbeat(id, at)
    suspend fun storeFinal(sessionId: String, generation: Long, startMs: Long, endMs: Long, text: String): Boolean {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || endMs < startMs) return false
        val key = Dedupe.segmentKey(generation, startMs, endMs, clean)
        return db.withTransaction {
            val inserted = db.segments().insert(
                TranscriptSegment(UUID.randomUUID().toString(), sessionId, startMs, endMs, clean, System.currentTimeMillis(), key),
            )
            if (inserted != -1L) {
                db.sessions().addFinalChars(sessionId, clean.length)
                true
            } else false
        }
    }
    suspend fun finish(id: String, status: SessionStatus, endedAt: Long, audioDurationSec: Long? = null) = db.withTransaction {
        val chars = db.segments().charCount(id)
        val durationSec = audioDurationSec ?: ((endedAt - currentStart(id)).coerceAtLeast(0) / 1000)
        db.sessions().finish(id, status, endedAt, durationSec, chars)
    }
    suspend fun updateMetadata(id: String, title: String, courseType: String?) {
        val clean = normalizeSessionTitle(title)
        val cleanType = courseType?.let(::normalizeCourseType)
        if (cleanType != null) check(db.courseTypes().exists(cleanType)) { tr("课程类型不存在", "Course type does not exist") }
        check(db.sessions().updateMetadata(id, clean, cleanType) == 1) { tr("课堂记录不存在", "Class record does not exist") }
    }
    suspend fun updateOrganized(id: String, isOrganized: Boolean) {
        check(db.sessions().updateOrganized(id, isOrganized) == 1) { tr("课堂记录不存在", "Class record does not exist") }
    }
    suspend fun addCourseType(name: String) {
        val clean = normalizeCourseType(name)
        check(db.courseTypes().insert(CourseType(clean, System.currentTimeMillis())) != -1L) { tr("课程类型已存在", "Course type already exists") }
    }
    suspend fun deleteCourseType(name: String) = db.withTransaction {
        val clean = normalizeCourseType(name)
        db.sessions().clearCourseType(clean)
        check(db.courseTypes().delete(clean) == 1) { tr("课程类型不存在", "Course type does not exist") }
    }
    suspend fun delete(id: String): Boolean = fileMutationLock.withLock {
        val deleted = db.withTransaction {
            val session = db.sessions().find(id) ?: return@withTransaction false
            check(session.status != SessionStatus.recording) { tr("记录中的课堂不能删除", "A class being recorded cannot be deleted") }
            db.segments().deleteForSession(id)
            db.boardPhotos().deleteForSession(id)
            db.classroomNotes().deleteForSession(id)
            check(db.sessions().delete(id) == 1) { tr("课堂记录删除失败", "Could not delete class record") }
            true
        }
        if (deleted) deleteBoardPhotoDirectory(id)
        deleted
    }
    suspend fun appendSession(targetId: String, sourceId: String): Unit = fileMutationLock.withLock {
        require(targetId != sourceId) { tr("不能将课堂追加到自身", "A class cannot be appended to itself") }
        val root = boardPhotoRoot
        val copies = root?.let(::AppendPhotoCopies)
        val copiedPhotoIds = mutableListOf<String>()
        try {
            db.withTransaction {
                val target = db.sessions().find(targetId) ?: error(tr("目标课堂不存在", "Target class does not exist"))
                val source = db.sessions().find(sourceId) ?: error(tr("来源课堂不存在", "Source class does not exist"))
                check(target.status != SessionStatus.recording) { tr("记录中的课堂不能追加", "A class being recorded cannot be appended") }
                check(source.status != SessionStatus.recording) { tr("记录中的课堂不能追加", "A class being recorded cannot be appended") }
                val targetSegments = db.segments().snapshotFor(targetId)
                val sourceSegments = db.segments().snapshotFor(sourceId)
                val sourcePhotos = db.boardPhotos().snapshotFor(sourceId)
                val sourceNotes = db.classroomNotes().snapshotFor(sourceId)
                val prepared = buildAppendPlan(target, source, targetSegments, sourceSegments, sourcePhotos, sourceNotes)
                val copiedPhotos = prepared.photos.mapIndexed { index, photo ->
                    val photoRoot = copies ?: error(tr("板书照片存储目录不可用", "Board photo storage directory is unavailable"))
                    val relativePath = photoRoot.copy(sourceId, targetId, sourcePhotos[index].relativePath)
                    copiedPhotoIds += photo.id
                    photo.copy(relativePath = relativePath)
                }
                val plan = prepared.copy(photos = copiedPhotos)
                plan.segments.forEach { segment -> check(db.segments().insert(segment) != -1L) { tr("转写追加失败", "Could not append transcript") } }
                plan.photos.forEach { photo ->
                    require(isSafeBoardPhotoPath(targetId, photo.relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
                    db.boardPhotos().insert(photo)
                }
                plan.notes.forEach { note -> check(db.classroomNotes().insert(note) != -1L) { tr("课堂记录追加失败", "Could not append class notes") } }
                check(db.sessions().updateAfterAppend(targetId, plan.durationSec, plan.finalCharCount, plan.endedAt) == 1) {
                    tr("目标课堂更新失败", "Could not update target class")
                }
                copies?.transactionBodyCompleted()
            }
            copies?.keep()
        } catch (failure: Throwable) {
            val referenceState = try {
                withContext(NonCancellable) {
                    if (copiedPhotoIds.isEmpty()) false else {
                        val count = db.boardPhotos().countByIds(copiedPhotoIds)
                        when (count) {
                            0 -> false
                            copiedPhotoIds.size -> true
                            else -> null
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
            copies?.reconcileFailure(referenceState)
            throw failure
        }
    }
    suspend fun addBoardPhoto(sessionId: String, capturedAtMs: Long, relativePath: String) = fileMutationLock.withLock {
        db.withTransaction {
            require(capturedAtMs >= 0) { tr("拍摄时间不能为负数", "Photo timestamp cannot be negative") }
            require(isSafeBoardPhotoPath(sessionId, relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
            val session = db.sessions().find(sessionId) ?: error(tr("课堂记录不存在", "Class record does not exist"))
            check(session.status == SessionStatus.recording) { tr("只有记录中的课堂可添加板书照片", "Board photos can only be added while recording") }
            db.boardPhotos().insert(BoardPhoto(UUID.randomUUID().toString(), sessionId, capturedAtMs, relativePath, System.currentTimeMillis()))
        }
    }
    suspend fun deleteBoardPhoto(sessionId: String, photoId: String): BoardPhotoDeletionResult = fileMutationLock.withLock {
        val photo = db.boardPhotos().find(photoId) ?: error(tr("板书照片记录不存在", "Board photo record does not exist"))
        val photoFile = resolveBoardPhotoFile(boardPhotoRoot, sessionId, photo)
        commitBoardPhotoDeletion(
            photoFile = photoFile,
            deleteRecord = {
                db.withTransaction {
                    val current = db.boardPhotos().find(photoId) ?: return@withTransaction 0
                    check(current.sessionId == sessionId && current.relativePath == photo.relativePath) { tr("板书照片已变更，请刷新后重试", "Board photo has changed. Refresh and try again.") }
                    db.boardPhotos().delete(photoId, sessionId)
                }
            },
            hasRemainingReference = { db.boardPhotos().countByRelativePath(photo.relativePath) > 0 },
        )
    }
    suspend fun addClassroomNote(
        sessionId: String,
        atMs: Long,
        kind: ClassroomNoteKind,
        text: String,
        id: String = UUID.randomUUID().toString(),
    ): ClassroomNote {
        require(atMs >= 0) { tr("记录时间不能为负数", "Note timestamp cannot be negative") }
        val clean = normalizeClassroomNote(text)
        val candidate = ClassroomNote(id, sessionId, atMs, kind, clean, System.currentTimeMillis())
        return db.withTransaction {
            val session = db.sessions().find(sessionId) ?: error(tr("课堂记录不存在", "Class record does not exist"))
            val elapsedAtSave = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0)
            val maxAllowedAt = if (session.status == SessionStatus.recording) elapsedAtSave + 15_000 else {
                maxOf(session.durationSec * 1_000, (session.endedAt ?: session.lastHeartbeatAt) - session.startedAt) + 15_000
            }
            require(atMs <= maxAllowedAt) { tr("记录时间超出课堂时长", "Note timestamp exceeds class duration") }
            db.classroomNotes().insert(candidate)
            val stored = db.classroomNotes().find(id) ?: error(tr("疑问或注释保存失败", "Could not save question or comment"))
            check(stored.sessionId == sessionId && stored.atMs == atMs && stored.kind == kind && stored.text == clean) { tr("记录编号已被使用", "Note ID is already in use") }
            stored
        }
    }
    private suspend fun currentStart(id: String): Long = db.sessions().startedAt(id)
    fun sessions(): Flow<List<Session>> = db.sessions().observeAll()
    fun session(id: String) = db.sessions().observe(id)
    fun segments(id: String) = db.segments().observeFor(id)
    fun photos(id: String): Flow<List<BoardPhoto>> = db.boardPhotos().observeFor(id)
    fun classroomNotes(id: String): Flow<List<ClassroomNote>> = db.classroomNotes().observeFor(id)
    fun courseTypes(): Flow<List<CourseType>> = db.courseTypes().observeAll()

    private suspend fun deleteBoardPhotoDirectory(sessionId: String) = withContext(Dispatchers.IO) {
        val root = boardPhotoRoot?.canonicalFile ?: return@withContext
        val directory = File(root, sessionId).canonicalFile
        if (directory.path.startsWith(root.path + File.separator)) directory.deleteRecursively()
    }
}

internal fun normalizeClassroomNote(value: String): String {
    val clean = value.replace("\r\n", "\n").replace('\r', '\n').trim()
    require(clean.isNotEmpty()) { tr("疑问或注释不能为空", "Question or comment cannot be empty") }
    require(clean.length <= 2_000) { tr("疑问或注释不能超过 2000 个字符", "Question or comment cannot exceed 2,000 characters") }
    return clean
}

fun isSafeBoardPhotoPath(sessionId: String, relativePath: String): Boolean {
    if (sessionId.isEmpty() || sessionId.contains('/') || sessionId.contains('\\') || sessionId == "." || sessionId == "..") return false
    val prefix = "board-photos/$sessionId/"
    if (!relativePath.startsWith(prefix) || relativePath.contains('\\') || relativePath.any { it.code < 32 || it.code == 127 }) return false
    val tail = relativePath.removePrefix(prefix)
    return tail.isNotEmpty() && tail.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}

internal fun normalizeSessionTitle(value: String): String {
    val clean = value.trim().replace(Regex("\\s+"), " ")
    require(clean.isNotEmpty()) { tr("课堂名称不能为空", "Class name cannot be empty") }
    require(clean.length <= 60) { tr("课堂名称不能超过 60 个字符", "Class name cannot exceed 60 characters") }
    return clean
}

internal fun normalizeCourseType(value: String): String {
    val clean = value.trim().replace(Regex("\\s+"), " ")
    require(clean.isNotEmpty()) { tr("课程类型不能为空", "Course type cannot be empty") }
    require(clean.length <= 60) { tr("课程类型不能超过 60 个字符", "Course type cannot exceed 60 characters") }
    return clean
}

object Dedupe { fun segmentKey(generation: Long, startMs: Long, endMs: Long, text: String) = "$generation|$startMs|$endMs|${text.trim().replace(Regex("\\s+"), " ")}" }
