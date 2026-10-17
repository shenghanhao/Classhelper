package com.classmemory.app.ui

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionTimelineFilterTest {
    private val transcript = SessionTimelineItem.Transcript(
        TranscriptSegment("text", "session", 1_000, 2_000, "文字", 1, "text"),
    )
    private val photo = SessionTimelineItem.Photo(
        BoardPhoto("photo", "session", 2_000, "board-photos/session/photo.jpg", 2),
    )
    private val question = SessionTimelineItem.Note(
        ClassroomNote("question", "session", 2_000, ClassroomNoteKind.QUESTION, "疑问", 3),
    )
    private val comment = SessionTimelineItem.Note(
        ClassroomNote("comment", "session", 2_000, ClassroomNoteKind.COMMENT, "注释", 4),
    )

    @Test
    fun allFilterReturnsOriginalTimelineWithoutChangingOrderOrInput() {
        val timeline = listOf(photo, transcript, question, comment)
        val original = timeline.toList()

        val result = filterSessionTimeline(timeline, SessionTimelineFilter.ALL)

        assertSame(timeline, result)
        assertEquals(original, timeline)
        assertEquals(original, result)
    }

    @Test
    fun boardPhotoFilterKeepsOnlyPhotosInOriginalOrder() {
        val first = SessionTimelineItem.Photo(
            BoardPhoto("first", "session", 2_000, "board-photos/session/first.jpg", 2),
        )
        val timeline = listOf(photo, question, first, transcript)
        val original = timeline.toList()

        assertEquals(listOf(photo, first), filterSessionTimeline(timeline, SessionTimelineFilter.BOARD_PHOTOS))
        assertEquals(original, timeline)
    }

    @Test
    fun notesFilterIncludesQuestionsAndCommentsAtSameTimestampInInputOrder() {
        val timeline = listOf(photo, comment, transcript, question)
        val original = timeline.toList()

        assertEquals(listOf(comment, question), filterSessionTimeline(timeline, SessionTimelineFilter.NOTES))
        assertEquals(original, timeline)
    }

    @Test
    fun emptyAndUnmatchedTimelinesReturnNoItems() {
        SessionTimelineFilter.values().forEach { filter ->
            assertEquals(emptyList<SessionTimelineItem>(), filterSessionTimeline(emptyList(), filter))
        }

        assertEquals(
            emptyList<SessionTimelineItem>(),
            filterSessionTimeline(listOf(transcript, question), SessionTimelineFilter.BOARD_PHOTOS),
        )
        assertEquals(
            emptyList<SessionTimelineItem>(),
            filterSessionTimeline(listOf(transcript, photo), SessionTimelineFilter.NOTES),
        )
    }
}
