package com.classmemory.app.ui

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.i18n.tr

internal sealed interface SessionTimelineItem {
    val key: String
    val timeMs: Long
    val createdAt: Long

    data class Transcript(val value: TranscriptSegment) : SessionTimelineItem {
        override val key = "transcript:${value.id}"
        override val timeMs = value.startMs
        override val createdAt = value.createdAt
    }

    data class Photo(val value: BoardPhoto) : SessionTimelineItem {
        override val key = "photo:${value.id}"
        override val timeMs = value.capturedAtMs
        override val createdAt = value.createdAt
    }

    data class Note(val value: ClassroomNote) : SessionTimelineItem {
        override val key = "note:${value.id}"
        override val timeMs = value.atMs
        override val createdAt = value.createdAt
    }
}

internal enum class SessionTimelineFilter {
    ALL,
    BOARD_PHOTOS,
    NOTES;

    val label: String
        get() = when (this) {
            ALL -> tr("所有", "All")
            BOARD_PHOTOS -> tr("板书", "Board photos")
            NOTES -> tr("疑问、注释", "Questions and comments")
        }
}

internal fun filterSessionTimeline(
    timeline: List<SessionTimelineItem>,
    filter: SessionTimelineFilter,
): List<SessionTimelineItem> = when (filter) {
    SessionTimelineFilter.ALL -> timeline
    SessionTimelineFilter.BOARD_PHOTOS -> timeline.filterIsInstance<SessionTimelineItem.Photo>()
    SessionTimelineFilter.NOTES -> timeline.filterIsInstance<SessionTimelineItem.Note>()
}

internal fun buildSessionTimeline(
    segments: List<TranscriptSegment>,
    photos: List<BoardPhoto>,
    notes: List<ClassroomNote> = emptyList(),
): List<SessionTimelineItem> =
    (segments.map(SessionTimelineItem::Transcript) + notes.map(SessionTimelineItem::Note) + photos.map(SessionTimelineItem::Photo))
        .sortedWith(
            compareBy<SessionTimelineItem>(
                SessionTimelineItem::timeMs,
                { when (it) { is SessionTimelineItem.Transcript -> 0; is SessionTimelineItem.Note -> 1; is SessionTimelineItem.Photo -> 2 } },
                SessionTimelineItem::createdAt,
                SessionTimelineItem::key,
            ),
        )
