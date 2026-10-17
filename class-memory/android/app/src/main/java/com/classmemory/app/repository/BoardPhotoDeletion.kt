package com.classmemory.app.repository

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.i18n.tr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class BoardPhotoDeletionResult { DELETED, FILE_CLEANUP_FAILED }

internal fun resolveBoardPhotoFile(rootDirectory: File?, sessionId: String, photo: BoardPhoto): File {
    require(photo.sessionId == sessionId) { tr("板书照片不属于当前课堂", "Board photo does not belong to this class") }
    require(isSafeBoardPhotoPath(sessionId, photo.relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
    val boardPhotoRoot = (rootDirectory ?: error(tr("板书照片存储目录不可用", "Board photo storage directory is unavailable"))).canonicalFile
    require(boardPhotoRoot.name == "board-photos") { tr("板书照片存储目录无效", "Invalid board photo storage directory") }
    val filesRoot = boardPhotoRoot.parentFile?.canonicalFile ?: error(tr("板书照片存储目录无效", "Invalid board photo storage directory"))
    val sessionRoot = File(boardPhotoRoot, sessionId).canonicalFile
    require(sessionRoot.path == File(boardPhotoRoot, sessionId).absolutePath) { tr("课堂照片目录越界", "Class photo directory is outside the allowed path") }
    val unresolved = File(filesRoot, photo.relativePath)
    val expected = unresolved.canonicalFile
    require(expected.path == unresolved.absolutePath) { tr("板书照片路径包含符号链接", "Board photo path contains a symbolic link") }
    require(expected.path.startsWith(sessionRoot.path + File.separator)) { tr("板书照片路径越界", "Board photo path is outside the allowed directory") }
    require(!expected.exists() || expected.isFile) { tr("板书照片不是普通文件", "Board photo is not a regular file") }
    return expected
}

/** The callback must commit the one-row database deletion before this helper removes the file. */
internal suspend fun commitBoardPhotoDeletion(
    photoFile: File,
    deleteRecord: suspend () -> Int,
    hasRemainingReference: suspend () -> Boolean = { false },
): BoardPhotoDeletionResult = withContext(NonCancellable + Dispatchers.IO) {
    check(deleteRecord() == 1) { tr("板书照片记录删除失败", "Could not delete board photo record") }
    val stillReferenced = try { hasRemainingReference() } catch (_: Exception) {
        return@withContext BoardPhotoDeletionResult.FILE_CLEANUP_FAILED
    }
    if (stillReferenced) return@withContext BoardPhotoDeletionResult.DELETED
    if (!photoFile.exists()) return@withContext BoardPhotoDeletionResult.DELETED
    try {
        if (photoFile.isFile && photoFile.delete()) BoardPhotoDeletionResult.DELETED
        else BoardPhotoDeletionResult.FILE_CLEANUP_FAILED
    } catch (_: Exception) {
        BoardPhotoDeletionResult.FILE_CLEANUP_FAILED
    }
}
