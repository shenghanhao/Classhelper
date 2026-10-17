package com.classmemory.app.export

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import java.time.ZoneId
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookExportTest {
    private val session = Session("lesson-1", 0, title = "物理", courseType = "力学", status = SessionStatus.completed, durationSec = 60)
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==")

    @Test fun notebookParsesAndInterleavesTranscriptWithEmbeddedPhoto() {
        val segments = listOf(
            TranscriptSegment("late", session.id, 20_000, 21_000, "第二段", 2, "b"),
            TranscriptSegment("early", session.id, 1_000, 2_000, "第一段", 1, "a"),
        )
        val photo = BoardPhoto("p", session.id, 10_000, "board-photos/lesson-1/photo.png", 3)
        val exported = buildNotebookExport(session, segments, listOf(photo), { png }, ZoneId.of("UTC"), english = false)
        val notebook = JSONObject(exported.content)
        val cells = notebook.getJSONArray("cells")
        assertEquals(4, notebook.getInt("nbformat"))
        assertEquals(4, cells.length())
        assertTrue(cells.getJSONObject(0).getJSONArray("source").getString(0).contains("- 时长：00:01:00"))
        assertTrue(cells.getJSONObject(1).getJSONArray("source").getString(0).contains("第一段"))
        val imageCell = cells.getJSONObject(2)
        assertTrue(imageCell.getJSONArray("source").getString(0).contains("attachment:board-1.png"))
        val encoded = imageCell.getJSONObject("attachments").getJSONObject("board-1.png").getString("image/png")
        assertArrayEquals(png, Base64.getDecoder().decode(encoded))
        assertTrue(cells.getJSONObject(3).getJSONArray("source").getString(0).contains("第二段"))
        assertFalse(exported.content.contains(photo.relativePath))
        assertEquals("物理-1970-01-01_00-00.ipynb", exported.fileName)
    }

    @Test fun notebookEnglishLabelsKeepChineseUserContentAndPhotoAttachment() {
        val transcript = TranscriptSegment("english-segment", session.id, 1_000, 2_000, "第一段转写", 1, "english-key")
        val photo = BoardPhoto("english-photo", session.id, 3_000, "board-photos/lesson-1/photo.png", 3)

        val exported = buildNotebookExport(session, listOf(transcript), listOf(photo), { png }, ZoneId.of("UTC"), english = true)
        val cells = JSONObject(exported.content).getJSONArray("cells")
        val header = cells.getJSONObject(0).getJSONArray("source").getString(0)
        val transcriptCell = cells.getJSONObject(1).getJSONArray("source").getString(0)
        val photoCell = cells.getJSONObject(2)
        val photoSource = photoCell.getJSONArray("source").getString(0)
        val encoded = photoCell.getJSONObject("attachments").getJSONObject("board-1.png").getString("image/png")

        assertTrue(header.contains("- Course: 力学"))
        assertTrue(header.contains("- Duration: 00:01:00"))
        assertTrue(transcriptCell.contains("第一段转写"))
        assertTrue(photoSource.contains("Board photo"))
        assertTrue(photoSource.contains("attachment:board-1.png"))
        assertArrayEquals(png, Base64.getDecoder().decode(encoded))
    }

    @Test fun missingPhotoFailsBeforeExport() {
        val photo = BoardPhoto("p", session.id, 0, "board-photos/lesson-1/photo.png", 0)
        assertThrows(IllegalStateException::class.java) {
            buildNotebookExport(session, emptyList(), listOf(photo), { error("missing") }, english = false)
        }
    }

    @Test fun notebookIncludesTimestampedNotesWithoutLosingTranscriptOrPhoto() {
        val transcript = TranscriptSegment("t", session.id, 1_000, 2_000, "转写内容", 1, "t")
        val photo = BoardPhoto("p", session.id, 4_000, "board-photos/lesson-1/photo.png", 4)
        val note = ClassroomNote("n", session.id, 3_000, ClassroomNoteKind.QUESTION, "疑问\n第二行", 3)

        val exported = buildNotebookExport(session, listOf(transcript), listOf(photo), { png }, ZoneId.of("UTC"), listOf(note), english = false)
        val cells = JSONObject(exported.content).getJSONArray("cells")

        assertEquals(4, cells.length())
        assertTrue(cells.getJSONObject(1).getJSONArray("source").getString(0).contains("转写内容"))
        assertTrue(cells.getJSONObject(2).getJSONArray("source").getString(0).contains("[00:00:03] 学生疑问：\n\n> 疑问\n> 第二行"))
        assertTrue(cells.getJSONObject(3).getJSONArray("source").getString(0).contains("attachment:board-1.png"))
    }

    @Test fun sameTimestampUsesTranscriptThenStableNotesThenPhoto() {
        val transcript = TranscriptSegment("t", session.id, 2_000, 3_000, "老师转写", 99, "t")
        val photo = BoardPhoto("p", session.id, 2_000, "board-photos/lesson-1/photo.png", 0)
        val notes = listOf(
            ClassroomNote("z", session.id, 2_000, ClassroomNoteKind.COMMENT, "后项", 1),
            ClassroomNote("a", session.id, 2_000, ClassroomNoteKind.QUESTION, "前项", 1),
        )
        val content = buildNotebookExport(session, listOf(transcript), listOf(photo), { png }, ZoneId.of("UTC"), notes, english = false).content
        val cells = JSONObject(content).getJSONArray("cells")
        val sources = (1 until cells.length()).map { cells.getJSONObject(it).getJSONArray("source").getString(0) }
        assertTrue(sources[0].contains("老师转写"))
        assertTrue(sources[1].contains("前项"))
        assertTrue(sources[2].contains("后项"))
        assertTrue(sources[3].contains("attachment:board-1.png"))
    }
}
