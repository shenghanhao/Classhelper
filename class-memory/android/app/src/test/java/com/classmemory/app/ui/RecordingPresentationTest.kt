package com.classmemory.app.ui

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPresentationTest {
    @Test
    fun endRequestRequiresConfirmationAndProtectsDraft() {
        assertEquals(EndRequest.CONFIRM, endRequest(finished = false, busy = false, hasDraft = false))
        assertEquals(EndRequest.DRAFT, endRequest(finished = false, busy = false, hasDraft = true))
        assertEquals(EndRequest.DISABLED, endRequest(finished = false, busy = true, hasDraft = false))
        assertEquals(EndRequest.RETURN, endRequest(finished = true, busy = false, hasDraft = false))
        assertEquals(EndRequest.DRAFT, endRequest(finished = true, busy = false, hasDraft = true))
    }

    @Test
    fun segmentsAreNewestFirstWithoutChangingTextOrder() {
        val older = segment("a", startMs = 10, createdAt = 1, text = "先说")
        val newer = segment("b", startMs = 20, createdAt = 2, text = "后说")
        assertEquals(listOf(newer, older), newestSegmentsFirst(listOf(older, newer)))
        assertEquals("后说", newestSegmentsFirst(listOf(older, newer)).first().text)
        assertEquals("第一行\n第二行", partialTranscriptInOrder("第一行\n第二行"))
    }

    @Test
    fun partialRevisionReplacesSnapshotAndPreservesSentenceOrder() {
        val firstSnapshot = "今天讨论学习计划"
        val revisedSnapshot = "今天讨论新的学习计划"
        assertEquals("今天讨论学习计划", partialTranscriptInOrder(firstSnapshot))
        assertEquals("今天讨论新的学习计划", partialTranscriptInOrder(revisedSnapshot))
    }

    @Test
    fun transcriptLatestEdgeMatchesReadingDirectionAndUserReviewStopsFollowing() {
        assertEquals(true, isAtTranscriptLatest(0, 100, TranscriptDirection.LATEST_AT_START))
        assertEquals(false, isAtTranscriptLatest(100, 100, TranscriptDirection.LATEST_AT_START))
        assertEquals(true, isAtTranscriptLatest(100, 100, TranscriptDirection.LATEST_AT_END))
        assertEquals(false, isAtTranscriptLatest(0, 100, TranscriptDirection.LATEST_AT_END))

        assertEquals(false, shouldFollowTranscriptLatest(
            currentlyFollowing = true,
            isUserScroll = true,
            scrollOffset = 40,
            maxScrollOffset = 100,
            direction = TranscriptDirection.LATEST_AT_END,
        ))
        assertEquals(true, shouldFollowTranscriptLatest(
            currentlyFollowing = true,
            isUserScroll = false,
            scrollOffset = 40,
            maxScrollOffset = 100,
            direction = TranscriptDirection.LATEST_AT_END,
        ))
        assertEquals(false, shouldFollowTranscriptLatest(
            currentlyFollowing = false,
            isUserScroll = false,
            scrollOffset = 100,
            maxScrollOffset = 100,
            direction = TranscriptDirection.LATEST_AT_END,
        ))
    }

    @Test
    fun noteSummaryUsesNewestAndCompactsWhitespace() {
        val old = note("old", 10, 1, "旧内容")
        val latest = note("new", 20, 2, "最新\n摘要")
        assertEquals(latest, newestNote(listOf(old, latest)))
        assertEquals("Question · 00:00:00 · 最新 摘要", noteSummary(latest))
        assertNull(newestNote(emptyList()))
    }

    @Test
    fun boardPhotoHelpersFilterSortAndBoundViewport() {
        val old = BoardPhoto("b", "session", 10, "b.jpg", 2)
        val sameTimeFirst = BoardPhoto("a", "session", 10, "a.jpg", 2)
        val otherSession = BoardPhoto("c", "other", 1, "c.jpg", 1)
        assertEquals(0.4f to 0.6f, recordingTranscriptWeights())
        assertEquals(listOf(sameTimeFirst, old), currentSessionBoardPhotos(listOf(old, otherSession, sameTimeFirst), "session"))
        assertEquals(1, boardPhotoSampleSize(128, 96, 256))
        assertEquals(8, boardPhotoSampleSize(4000, 3000, 512))
        assertEquals(BoardPhotoViewport(5f, 200f, -200f), clampBoardPhotoViewport(7f, 900f, -900f, 100f, 100f))
        assertEquals(BoardPhotoViewport(1f, 0f, 0f), clampBoardPhotoViewport(0.5f, 90f, -90f, 100f, 100f))
        assertEquals(BoardPhotoViewport(2f, 0f, 0f), clampBoardPhotoViewport(2f, 80f, -80f, 100f, 100f, 40f, 40f))
        assertEquals(BoardPhotoViewport(1f, 0f, 0f), clampBoardPhotoViewport(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, 100f, 100f))
        assertEquals("00:00:00", formatBoardPhotoTime(0))
        assertEquals("00:10:30", formatBoardPhotoTime(630_000))
    }

    private fun segment(id: String, startMs: Long, createdAt: Long, text: String) =
        TranscriptSegment(id, "session", startMs, startMs + 1, text, createdAt, id)

    private fun note(id: String, atMs: Long, createdAt: Long, text: String) =
        ClassroomNote(id, "session", atMs, ClassroomNoteKind.QUESTION, text, createdAt)
}
