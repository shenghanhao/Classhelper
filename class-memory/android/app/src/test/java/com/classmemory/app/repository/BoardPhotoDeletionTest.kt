package com.classmemory.app.repository

import com.classmemory.app.data.BoardPhoto
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardPhotoDeletionTest {
    @Test
    fun deletesOnlyTargetAndPreservesSharedFileReferences() = withTempRoot { root ->
        val first = photo("a", "board-photos/session/a.jpg")
        val other = photo("b", "board-photos/session/b.jpg")
        val firstFile = file(root, first.relativePath)
        val otherFile = file(root, other.relativePath)
        assertEquals(firstFile.canonicalFile, resolveBoardPhotoFile(root, "session", first))
        assertEquals(BoardPhotoDeletionResult.DELETED, runBlocking {
            commitBoardPhotoDeletion(firstFile, deleteRecord = { 1 })
        })
        assertFalse(firstFile.exists())
        assertTrue(otherFile.isFile)

        val sharedFile = file(root, "board-photos/session/shared.jpg")
        assertEquals(BoardPhotoDeletionResult.DELETED, runBlocking {
            commitBoardPhotoDeletion(sharedFile, deleteRecord = { 1 }, hasRemainingReference = { true })
        })
        assertTrue("shared file remains for the other photo row", sharedFile.isFile)
    }

    @Test
    fun databaseFailureKeepsPhotoFile() = withTempRoot { root ->
        val photo = photo("a", "board-photos/session/a.jpg")
        val target = file(root, photo.relativePath)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { commitBoardPhotoDeletion(target, deleteRecord = { error("database failure") }) }
        }
        assertTrue(target.isFile)
    }

    @Test
    fun missingPhotoFileStillAllowsRecordDeletion() = withTempRoot { root ->
        val photo = photo("a", "board-photos/session/missing.jpg")
        val missing = File(root.parentFile, photo.relativePath)
        var databaseDeleted = false
        assertEquals(BoardPhotoDeletionResult.DELETED, runBlocking {
            commitBoardPhotoDeletion(missing, deleteRecord = { databaseDeleted = true; 1 })
        })
        assertTrue(databaseDeleted)
        assertFalse(missing.exists())
    }

    @Test
    fun refusesWrongSessionUnsafePathAndNonFileBeforeDatabaseMutation() = withTempRoot { root ->
        val valid = photo("a", "board-photos/session/a.jpg")
        assertThrows(IllegalArgumentException::class.java) { resolveBoardPhotoFile(root, "other", valid) }
        assertThrows(IllegalArgumentException::class.java) {
            resolveBoardPhotoFile(root, "session", valid.copy(relativePath = "board-photos/session/../escape.jpg"))
        }
        val directory = File(root.parentFile, valid.relativePath).apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { resolveBoardPhotoFile(root, "session", valid) }
        assertTrue(directory.isDirectory)
    }

    @Test
    fun fileCleanupFailureHasDistinctWarningResult() = withTempRoot { root ->
        val directory = File(root.parentFile, "board-photos/session/a.jpg").apply { mkdirs() }
        assertEquals(BoardPhotoDeletionResult.FILE_CLEANUP_FAILED, runBlocking {
            commitBoardPhotoDeletion(directory, deleteRecord = { 1 })
        })
        assertTrue(directory.isDirectory)
    }

    @Test
    fun nonCancellableBoundaryCompletesCommitAndCleanupAfterCallerCancellation() = withTempRoot { root ->
        val target = file(root, "board-photos/session/a.jpg")
        runBlocking {
            val enteredDelete = CompletableDeferred<Unit>()
            val resumeDelete = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val databaseCommitted = AtomicBoolean(false)
            val job = launch {
                try {
                    commitBoardPhotoDeletion(target, deleteRecord = {
                        enteredDelete.complete(Unit)
                        resumeDelete.await()
                        databaseCommitted.set(true)
                        1
                    })
                } finally {
                    finished.complete(Unit)
                }
            }
            enteredDelete.await()
            job.cancel()
            resumeDelete.complete(Unit)
            finished.await()
            assertTrue(databaseCommitted.get())
            assertFalse(target.exists())
        }
    }

    private fun photo(id: String, path: String) = BoardPhoto(id, "session", 0, path, 0)

    private fun file(root: File, relativePath: String): File = File(root.parentFile, relativePath).apply {
        parentFile?.mkdirs()
        writeText("test photo")
    }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val tempRoot = Files.createTempDirectory("board-photo-delete-test").toFile()
        val boardPhotoRoot = File(tempRoot, "files/board-photos").apply { mkdirs() }
        try { block(boardPhotoRoot) } finally { tempRoot.deleteRecursively() }
    }
}
