package com.classmemory.app.repository

import com.classmemory.app.data.*
import com.classmemory.app.export.buildMarkdownExport
import com.classmemory.app.export.buildNotebookExport
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppendSessionLogicTest {
    private fun session(id: String, status: SessionStatus = SessionStatus.completed, start: Long = 10_000, duration: Long = 2) =
        Session(id, start, title = "原课堂", courseType = "物理", endedAt = start + duration * 1_000,
            status = status, durationSec = duration, finalCharCount = 99, lastHeartbeatAt = start + duration * 1_000,
            isOrganized = true)

    @Test fun buildPlan_offsetsRowsAndPreservesTargetIdentityAndStatus() {
        val target = session("a", SessionStatus.failed)
        val originalSegment = TranscriptSegment("b-seg", "b", 100, 250, " 公式 ", 7, "key")
        val photo = BoardPhoto("photo", "b", 400, "board-photos/b/image.jpg", 8)
        val note = ClassroomNote("note", "b", 500, ClassroomNoteKind.QUESTION, "为什么", 9)
        val plan = buildAppendPlan(target, session("b", duration = 3), emptyList(), listOf(originalSegment), listOf(photo), listOf(note))

        assertEquals(5L, plan.durationSec)
        assertEquals(15_000L, plan.endedAt)
        assertEquals(4, plan.finalCharCount)
        assertEquals("a", plan.segments.single().sessionId)
        assertEquals(2_100L, plan.segments.single().startMs)
        assertEquals(2_250L, plan.segments.single().endMs)
        assertNotEquals(originalSegment.id, plan.segments.single().id)
        assertNotEquals(originalSegment.dedupeKey, plan.segments.single().dedupeKey)
        assertEquals(2_400L, plan.photos.single().capturedAtMs)
        assertEquals(2_500L, plan.notes.single().atMs)
        assertEquals("b", originalSegment.sessionId)
        assertEquals("board-photos/b/image.jpg", photo.relativePath)
        assertEquals("a", plan.target.id)
        assertEquals(10_000L, plan.target.startedAt)
        assertEquals("原课堂", plan.target.title)
        assertEquals("物理", plan.target.courseType)
        assertEquals(SessionStatus.failed, plan.target.status)
        assertFalse(plan.target.isOrganized)
        assertEquals(5L, plan.target.durationSec)
        assertEquals(15_000L, plan.target.lastHeartbeatAt)
        assertEquals(4, plan.target.finalCharCount)
        assertTrue(target.isOrganized) // Planning does not mutate the original snapshot.
    }

    @Test fun buildPlanAllowsZeroDurationSourceAndCountsExistingText() {
        val target = session("a", duration = 0)
        val existing = TranscriptSegment("old", "a", 0, 1, "已有", 1, "old")
        val source = TranscriptSegment("new", "b", 0, 0, "新增", 1, "new")
        val plan = buildAppendPlan(target, session("b", duration = 0), listOf(existing), listOf(source), emptyList(), emptyList())
        assertEquals(0L, plan.durationSec)
        assertEquals(4, plan.finalCharCount)
        assertEquals(0L, plan.segments.single().startMs)
    }

    @Test fun buildPlanRejectsSelfRecordingNegativeTimeAndOverflow() {
        val normal = session("a")
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(normal, normal, emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(session("a", SessionStatus.recording), session("b"), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(session("a"), session("b", SessionStatus.recording), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(normal, session("b", duration = -1), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(normal, session("b", start = -1), emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildAppendPlan(normal, session("b"), emptyList(), listOf(TranscriptSegment("x", "wrong", 0, 1, "x", 1, "x")), emptyList(), emptyList())
        }
        assertThrows(ArithmeticException::class.java) {
            buildAppendPlan(session("a", start = Long.MAX_VALUE - 1_000), session("b"), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    @Test fun codePointCountMatchesSqliteLengthForSupplementaryCharacters() {
        val source = TranscriptSegment("new", "b", 0, 0, "🧪", 1, "new")
        val plan = buildAppendPlan(session("a"), session("b"), emptyList(), listOf(source), emptyList(), emptyList())
        assertEquals(1, plan.finalCharCount)
    }

    @Test fun copiedPhotoIsIndependentFromSourceAndRollbackOnlyRemovesNewFiles() {
        val temp = Files.createTempDirectory("append-photo-test").toFile()
        try {
            val root = File(temp, "board-photos").apply { mkdirs() }
            val sourceDir = File(root, "b").apply { mkdirs() }
            val source = File(sourceDir, "image.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val targetDir = File(root, "a").apply { mkdirs() }
            val existing = File(targetDir, "existing.jpg").apply { writeBytes(byteArrayOf(9)) }

            val copies = AppendPhotoCopies(root)
            val relative = copies.copy("b", "a", "board-photos/b/image.jpg")
            val target = File(temp, relative)
            source.delete()
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            copies.rollback()
            assertFalse(target.exists())
            assertTrue(existing.exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun missingSourceAndTransactionFailureCleanUpCopiesButCancellationAfterCommitCannotDeleteThem() {
        val temp = Files.createTempDirectory("append-photo-failure-test").toFile()
        try {
            val root = File(temp, "board-photos").apply { mkdirs() }
            val sourceDir = File(root, "b").apply { mkdirs() }
            File(sourceDir, "empty.jpg").createNewFile()
            val existingDir = File(root, "a").apply { mkdirs() }
            val existing = File(existingDir, "keep.jpg").apply { writeBytes(byteArrayOf(5)) }
            val source = File(sourceDir, "image.jpg").apply { writeBytes(byteArrayOf(1)) }
            val copies = AppendPhotoCopies(root)
            assertThrows(IllegalStateException::class.java) {
                copies.copy("b", "a", "board-photos/b/empty.jpg")
            }
            assertTrue(existing.exists())
            assertThrows(IllegalArgumentException::class.java) {
                AppendPhotoCopies(root).copy("b", "a", "board-photos/b/../a/keep.jpg")
            }
            assertTrue(existing.exists())

            val partialCopy = AppendPhotoCopies(root)
            val firstCopy = File(temp, partialCopy.copy("b", "a", "board-photos/b/image.jpg"))
            assertThrows(IllegalStateException::class.java) {
                partialCopy.copy("b", "a", "board-photos/b/missing.jpg")
            }
            assertFalse(firstCopy.exists())
            assertTrue(source.exists())
            assertTrue(existing.exists())

            val failedTransaction = AppendPhotoCopies(root)
            val failedCopy = File(temp, failedTransaction.copy("b", "a", "board-photos/b/image.jpg"))
            failedTransaction.rollback() // Repository invokes this when a DB write fails before the transaction body completes.
            assertFalse(failedCopy.exists())

            val second = AppendPhotoCopies(root)
            val copied = File(temp, second.copy("b", "a", "board-photos/b/image.jpg"))
            second.transactionBodyCompleted() // withTransaction may have committed even if cancellation is observed at its return boundary.
            second.reconcileFailure(true)
            second.rollback()
            assertTrue(source.exists())
            assertTrue(existing.exists())
            assertTrue(copied.exists())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun failureReconciliationCleansUnreferencedAndRetainsReferencedOrUnknownCopies() {
        val temp = Files.createTempDirectory("append-photo-reconcile-test").toFile()
        try {
            val root = File(temp, "board-photos").apply { mkdirs() }
            val sourceDir = File(root, "b").apply { mkdirs() }
            File(sourceDir, "image.jpg").writeBytes(byteArrayOf(1, 2))

            val unreferencedManager = AppendPhotoCopies(root)
            val unreferenced = File(temp, unreferencedManager.copy("b", "a", "board-photos/b/image.jpg"))
            unreferencedManager.transactionBodyCompleted()
            unreferencedManager.reconcileFailure(false)
            assertFalse(unreferenced.exists())

            listOf(true, null).forEach { state ->
                val manager = AppendPhotoCopies(root)
                val retained = File(temp, manager.copy("b", "a", "board-photos/b/image.jpg"))
                manager.transactionBodyCompleted()
                manager.reconcileFailure(state)
                manager.rollback()
                assertTrue(retained.exists())
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun appendedPlanFeedsMarkdownAndNotebookWithCopiedPhotoAfterSourceRemoval() {
        val temp = Files.createTempDirectory("append-export-test").toFile()
        try {
            val root = File(temp, "board-photos").apply { mkdirs() }
            val sourceDir = File(root, "b").apply { mkdirs() }
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==")
            val sourceImage = File(sourceDir, "image.png").apply { writeBytes(png) }
            val segment = TranscriptSegment("seg", "b", 1_000, 2_000, "追加内容", 1, "segment-key")
            val sourcePhoto = BoardPhoto("photo", "b", 3_500, "board-photos/b/image.png", 2)
            val sourceNote = ClassroomNote("note", "b", 700, ClassroomNoteKind.QUESTION, "追加疑问", 3)
            val plan = buildAppendPlan(session("a"), session("b", duration = 2), emptyList(), listOf(segment), listOf(sourcePhoto), listOf(sourceNote))
            val copies = AppendPhotoCopies(root)
            val appendedPhoto = plan.photos.single().copy(
                relativePath = copies.copy("b", "a", sourcePhoto.relativePath),
            )
            val targetPhotoFile = File(temp, appendedPhoto.relativePath)
            sourceImage.delete()

            val markdown = buildMarkdownExport(plan.target, plan.segments, ZoneId.of("UTC"), plan.notes)
            assertTrue(markdown.content.contains("[00:00:03–00:00:04] 追加内容"))
            assertTrue(markdown.content.contains("[00:00:02] 学生疑问"))
            val notebook = buildNotebookExport(plan.target, plan.segments, listOf(appendedPhoto), { targetPhotoFile.readBytes() }, ZoneId.of("UTC"), plan.notes)
            val cells = JSONObject(notebook.content).getJSONArray("cells")
            assertTrue(cells.getJSONObject(1).getJSONArray("source").getString(0).contains("学生疑问"))
            assertTrue(cells.getJSONObject(2).getJSONArray("source").getString(0).contains("追加内容"))
            assertTrue(notebook.content.contains("attachment:board-1.png"))
            assertArrayEquals(png, Base64.getDecoder().decode(
                cells.getJSONObject(3).getJSONObject("attachments").getJSONObject("board-1.png").getString("image/png"),
            ))
            assertTrue(targetPhotoFile.isFile)
            copies.keep()
        } finally {
            temp.deleteRecursively()
        }
    }
}
