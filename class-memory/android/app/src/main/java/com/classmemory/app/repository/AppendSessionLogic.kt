package com.classmemory.app.repository

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.i18n.tr
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal data class AppendPlan(
    val target: Session,
    val durationSec: Long,
    val endedAt: Long,
    val segments: List<TranscriptSegment>,
    val photos: List<BoardPhoto>,
    val notes: List<ClassroomNote>,
    val finalCharCount: Int,
)

internal fun buildAppendPlan(
    target: Session,
    source: Session,
    targetSegments: List<TranscriptSegment>,
    sourceSegments: List<TranscriptSegment>,
    sourcePhotos: List<BoardPhoto>,
    sourceNotes: List<ClassroomNote>,
): AppendPlan {
    require(target.id != source.id) { tr("不能将课堂追加到自身", "A class cannot be appended to itself") }
    require(target.status != com.classmemory.app.data.SessionStatus.recording) { tr("记录中的课堂不能追加", "A class being recorded cannot be appended") }
    require(source.status != com.classmemory.app.data.SessionStatus.recording) { tr("记录中的课堂不能追加", "A class being recorded cannot be appended") }
    require(source.startedAt >= 0 && (source.endedAt == null || source.endedAt >= 0) && source.lastHeartbeatAt >= 0) { tr("来源课堂时间无效", "Invalid source class timestamp") }
    require(target.durationSec >= 0 && source.durationSec >= 0) { tr("课堂时长不能为负数", "Class duration cannot be negative") }
    val shiftedDuration = Math.multiplyExact(target.durationSec, 1_000L)
    val newDuration = Math.addExact(target.durationSec, source.durationSec)
    val newEndedAt = Math.addExact(target.startedAt, Math.multiplyExact(newDuration, 1_000L))
    require(target.startedAt >= 0 && newEndedAt >= 0) { tr("课堂时间无效", "Invalid class timestamp") }
    require(targetSegments.all { it.sessionId == target.id }) { tr("目标转写归属无效", "Transcript does not belong to the target class") }
    require(sourceSegments.all { it.sessionId == source.id }) { tr("来源转写归属无效", "Transcript does not belong to the source class") }
    require(sourcePhotos.all { it.sessionId == source.id }) { tr("来源板书归属无效", "Board photo does not belong to the source class") }
    require(sourceNotes.all { it.sessionId == source.id }) { tr("来源课堂记录归属无效", "Class note does not belong to the source class") }
    require(targetSegments.all { it.startMs >= 0 && it.endMs >= it.startMs }) { tr("目标转写时间无效", "Invalid target transcript timestamp") }
    val newSegments = sourceSegments.map { segment ->
        require(segment.startMs >= 0 && segment.endMs >= segment.startMs) { tr("转写时间无效", "Invalid transcript timestamp") }
        val start = Math.addExact(segment.startMs, shiftedDuration)
        val end = Math.addExact(segment.endMs, shiftedDuration)
        segment.copy(
            id = UUID.randomUUID().toString(), sessionId = target.id, startMs = start, endMs = end,
            dedupeKey = "${segment.dedupeKey}|append:${UUID.randomUUID()}",
        )
    }
    val newPhotos = sourcePhotos.map { photo ->
        require(photo.capturedAtMs >= 0) { tr("板书时间无效", "Invalid board photo timestamp") }
        photo.copy(
            id = UUID.randomUUID().toString(), sessionId = target.id,
            capturedAtMs = Math.addExact(photo.capturedAtMs, shiftedDuration),
            relativePath = "", // 填入文件复制器生成的目标路径。
        )
    }
    val newNotes = sourceNotes.map { note ->
        require(note.atMs >= 0) { tr("课堂记录时间无效", "Invalid class note timestamp") }
        note.copy(
            id = UUID.randomUUID().toString(), sessionId = target.id,
            atMs = Math.addExact(note.atMs, shiftedDuration),
        )
    }
    val chars = (targetSegments + newSegments).fold(0) { total, segment ->
        Math.addExact(total, segment.text.codePointCount(0, segment.text.length))
    }
    val updatedTarget = target.copy(
        durationSec = newDuration, endedAt = newEndedAt, lastHeartbeatAt = newEndedAt,
        finalCharCount = chars, isOrganized = false,
    )
    return AppendPlan(updatedTarget, newDuration, newEndedAt, newSegments, newPhotos, newNotes, chars)
}

/** Copies only operation-owned files and removes them if the caller's transaction fails. */
internal class AppendPhotoCopies(private val root: File) {
    private val createdFiles = mutableListOf<File>()
    private val createdDirectories = mutableListOf<File>()
    private var retained = false
    private var transactionMayHaveCommitted = false

    fun copy(sourceSessionId: String, targetSessionId: String, relativePath: String): String {
        try {
            require(isSafeBoardPhotoPath(sourceSessionId, relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
            val canonicalRoot = root.canonicalFile
            val source = File(root.parentFile, relativePath).canonicalFile
            val sourceDir = File(canonicalRoot, sourceSessionId).canonicalFile
            check(sourceDir.parentFile == canonicalRoot && sourceDir.name == sourceSessionId && source.path.startsWith(sourceDir.path + File.separator)) {
                tr("板书照片路径越界", "Board photo path is outside the allowed directory")
            }
            check(source.isFile && source.length() > 0L) { tr("板书照片不存在或为空", "Board photo is missing or empty") }

            val targetDir = File(canonicalRoot, targetSessionId).canonicalFile
            check(targetDir.parentFile == canonicalRoot && targetDir.name == targetSessionId) { tr("目标照片路径越界", "Target photo path is outside the allowed directory") }
            if (!targetDir.exists()) {
                check(targetDir.mkdirs()) { tr("无法创建目标照片目录", "Could not create target photo directory") }
                createdDirectories += targetDir
            }
            val suffix = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"
            val target = File.createTempFile("append-", suffix, targetDir).canonicalFile
            createdFiles += target
            check(target.parentFile == targetDir) { tr("目标照片路径无效", "Invalid target photo path") }
            FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            check(target.isFile && target.length() == source.length() && target.length() > 0L) { tr("板书照片复制失败", "Could not copy board photo") }
            return "board-photos/$targetSessionId/${target.name}"
        } catch (failure: Exception) {
            rollback()
            throw failure
        }
    }

    fun keep() { retained = true }

    fun transactionBodyCompleted() { transactionMayHaveCommitted = true }

    /** true means every copied row is referenced, false means none are, null means the check failed or was partial. */
    fun reconcileFailure(referenceState: Boolean?) {
        if (referenceState == false) {
            transactionMayHaveCommitted = false
            rollback()
        } else {
            transactionMayHaveCommitted = true
            retained = true
        }
    }

    fun rollback() {
        if (retained || transactionMayHaveCommitted) return
        createdFiles.asReversed().forEach(File::delete)
        createdDirectories.asReversed().forEach { directory ->
            if (directory.isDirectory && directory.list()?.isEmpty() == true) directory.delete()
        }
        createdFiles.clear()
        createdDirectories.clear()
    }
}
