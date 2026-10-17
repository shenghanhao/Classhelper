package com.classmemory.app.ui

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTimelineTest {
    @Test
    fun transcriptAndPhotosAreOrderedByClassTime() {
        val segments = listOf(
            TranscriptSegment("late", "session", 20_000, 21_000, "第二段", 3, "late"),
            TranscriptSegment("early", "session", 1_000, 2_000, "第一段", 1, "early"),
        )
        val photos = listOf(
            BoardPhoto("photo", "session", 10_000, "board-photos/session/photo.jpg", 2),
        )

        assertEquals(
            listOf("transcript:early", "photo:photo", "transcript:late"),
            buildSessionTimeline(segments, photos).map(SessionTimelineItem::key),
        )
    }

    @Test
    fun transcriptPrecedesPhotoAtTheSameTime() {
        val segment = TranscriptSegment("text", "session", 10_000, 11_000, "文字", 2, "text")
        val photo = BoardPhoto("image", "session", 10_000, "board-photos/session/image.jpg", 1)

        assertEquals(
            listOf("transcript:text", "photo:image"),
            buildSessionTimeline(listOf(segment), listOf(photo)).map(SessionTimelineItem::key),
        )
    }

    @Test
    fun notesMixByClassTimeAndUseIdForSameTimestampAndCreationTime() {
        val laterId = ClassroomNote("a", "session", 10_000, ClassroomNoteKind.COMMENT, "较晚创建", 20)
        val tiedIdZ = ClassroomNote("z", "session", 10_000, ClassroomNoteKind.COMMENT, "同刻后项", 10)
        val tiedIdA = ClassroomNote("b", "session", 10_000, ClassroomNoteKind.COMMENT, "同刻前项", 10)
        val transcript = TranscriptSegment("t", "session", 10_000, 11_000, "转写", 99, "t")

        assertEquals(
            listOf("transcript:t", "note:b", "note:z", "note:a"),
            buildSessionTimeline(listOf(transcript), emptyList(), listOf(laterId, tiedIdZ, tiedIdA)).map(SessionTimelineItem::key),
        )
    }
}
