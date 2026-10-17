package com.classmemory.app.export

import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.formatClassroomNoteTimestamp
import com.classmemory.app.i18n.AppLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MarkdownExport(val fileName: String, val content: String)

fun buildMarkdownExport(
    session: Session,
    segments: List<TranscriptSegment>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    notes: List<ClassroomNote> = emptyList(),
    english: Boolean = AppLanguage.isEnglish,
): MarkdownExport {
    val untitled = if (english) "Untitled class" else "未命名课堂"
    val title = session.title?.trim()?.takeIf { it.isNotEmpty() } ?: untitled
    val startedAt = Instant.ofEpochMilli(session.startedAt).atZone(zoneId)
    val dateText = startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val fileDate = startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
    val safeTitle = title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .ifEmpty { untitled }
        .take(60)

    val markdown = buildString {
        append("# ").append(title).append("\n\n")
        session.courseType?.let { append(if (english) "- Course: " else "- 课程类型：").append(it).append('\n') }
        append(if (english) "- Date: " else "- 日期：").append(dateText).append('\n')
        append(if (english) "- Duration: " else "- 时长：").append(formatClock(session.durationSec)).append('\n')
        append(if (english) "- Transcript characters: " else "- 转写字数：").append(session.finalCharCount).append("\n\n")
        append(if (english) {
            if (notes.isEmpty()) "## Transcript\n\n" else "## Transcript and Notes\n\n"
        } else {
            if (notes.isEmpty()) "## 课堂转写\n\n" else "## 课堂转写与课堂标注\n\n"
        })
        if (segments.isEmpty() && notes.isEmpty()) {
            append(if (english) "_No transcript content_\n" else "_暂无转写内容_\n")
        } else {
            data class Event(val timeMs: Long, val order: Int, val createdAt: Long, val id: String, val text: String)
            val events = segments.map { segment ->
                Event(segment.startMs, 0, segment.createdAt, segment.id, "[${formatClock(segment.startMs / 1_000)}–${formatClock(segment.endMs / 1_000)}] ${segment.text.trim().replace(Regex("\\s+"), " ")}")
            } + notes.map { note ->
                Event(note.atMs, 1, note.createdAt, note.id, formatClassroomNoteTimestamp(note, english))
            }
            events.sortedWith(compareBy<Event>(Event::timeMs, Event::order, Event::createdAt, Event::id)).forEach { event ->
                append(event.text).append("\n\n")
            }
        }
    }
    return MarkdownExport("$safeTitle-$fileDate.md", markdown)
}

private fun formatClock(seconds: Long): String = "%02d:%02d:%02d".format(
    seconds / 3_600,
    (seconds % 3_600) / 60,
    seconds % 60,
)
