package com.classmemory.app.ui

import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.i18n.tr

internal enum class EndRequest { DISABLED, CONFIRM, DRAFT, RETURN }
internal enum class TranscriptDirection { LATEST_AT_START, LATEST_AT_END }

internal fun endRequest(finished: Boolean, busy: Boolean, hasDraft: Boolean): EndRequest = when {
    busy -> EndRequest.DISABLED
    hasDraft -> EndRequest.DRAFT
    finished -> EndRequest.RETURN
    else -> EndRequest.CONFIRM
}

internal fun newestSegmentsFirst(segments: List<TranscriptSegment>): List<TranscriptSegment> =
    segments.sortedWith(compareByDescending<TranscriptSegment> { it.startMs }
        .thenByDescending { it.createdAt }
        .thenByDescending { it.id })

internal fun newestNote(notes: List<ClassroomNote>): ClassroomNote? =
    notes.maxWithOrNull(compareBy<ClassroomNote> { it.atMs }
        .thenBy { it.createdAt }
        .thenBy { it.id })

internal fun noteSummary(note: ClassroomNote): String =
    tr(
        "${if (note.kind == ClassroomNoteKind.QUESTION) "疑问" else "注释"} · ${formatDuration(note.atMs / 1_000)} · ${note.text.trim().replace(Regex("\\s+"), " ")}",
        "${if (note.kind == ClassroomNoteKind.QUESTION) "Question" else "Comment"} · ${formatDuration(note.atMs / 1_000)} · ${note.text.trim().replace(Regex("\\s+"), " ")}",
    )

internal fun partialTranscriptInOrder(snapshot: String): String = snapshot

internal fun recordingTranscriptWeights(): Pair<Float, Float> = 0.4f to 0.6f

internal fun requestBoardPhotoDeletion(confirmed: Boolean, busy: Boolean, onStart: () -> Unit) {
    if (confirmed && !busy) onStart()
}

internal fun currentSessionBoardPhotos(photos: List<BoardPhoto>, sessionId: String): List<BoardPhoto> =
    photos.filter { it.sessionId == sessionId }
        .sortedWith(compareBy<BoardPhoto> { it.capturedAtMs }.thenBy { it.createdAt }.thenBy { it.id })

internal fun boardPhotoSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (maxOf(width / sample, height / sample) > maxEdge && sample <= (1 shl 29)) sample *= 2
    return sample
}

internal data class BoardPhotoViewport(val scale: Float, val offsetX: Float, val offsetY: Float)

internal fun clampBoardPhotoViewport(
    scale: Float, offsetX: Float, offsetY: Float, viewWidth: Float, viewHeight: Float,
    contentWidth: Float = viewWidth, contentHeight: Float = viewHeight,
): BoardPhotoViewport {
    val safeScale = if (scale.isFinite()) scale.coerceIn(1f, 5f) else 1f
    val safeWidth = viewWidth.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeHeight = viewHeight.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val imageWidth = contentWidth.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val imageHeight = contentHeight.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val maxX = ((imageWidth * safeScale - safeWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((imageHeight * safeScale - safeHeight) / 2f).coerceAtLeast(0f)
    val boundedX = if (maxX <= 0f) 0f else offsetX.takeIf { it.isFinite() }?.coerceIn(-maxX, maxX) ?: 0f
    val boundedY = if (maxY <= 0f) 0f else offsetY.takeIf { it.isFinite() }?.coerceIn(-maxY, maxY) ?: 0f
    return BoardPhotoViewport(safeScale, boundedX, boundedY)
}

internal fun isAtTranscriptLatest(
    scrollOffset: Int,
    maxScrollOffset: Int,
    direction: TranscriptDirection,
    threshold: Int = 8,
): Boolean = when (direction) {
    TranscriptDirection.LATEST_AT_START -> scrollOffset <= threshold
    TranscriptDirection.LATEST_AT_END -> maxScrollOffset - scrollOffset <= threshold
}

internal fun shouldFollowTranscriptLatest(
    currentlyFollowing: Boolean,
    isUserScroll: Boolean,
    scrollOffset: Int,
    maxScrollOffset: Int,
    direction: TranscriptDirection,
): Boolean = if (!isUserScroll) currentlyFollowing else
    isAtTranscriptLatest(scrollOffset, maxScrollOffset, direction)
