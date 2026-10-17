package com.classmemory.app.export

import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.formatClassroomNoteTimestamp
import com.classmemory.app.i18n.AppLanguage
import com.classmemory.app.i18n.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class NotebookExport(val fileName: String, val content: String)

fun buildNotebookExport(
    session: Session,
    segments: List<TranscriptSegment>,
    photos: List<BoardPhoto>,
    loadPhoto: (BoardPhoto) -> ByteArray,
    zoneId: ZoneId = ZoneId.systemDefault(),
    notes: List<ClassroomNote> = emptyList(),
    english: Boolean = AppLanguage.isEnglish,
): NotebookExport {
    val markdown = buildMarkdownExport(session, emptyList(), zoneId, notes, english)
    val startedAt = Instant.ofEpochMilli(session.startedAt).atZone(zoneId)
    val date = startedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val title = session.title?.trim()?.takeIf { it.isNotEmpty() } ?: if (english) "Untitled class" else "未命名课堂"
    val header = buildString {
        append("# ").append(title).append("\n\n")
        session.courseType?.let { append(if (english) "- Course: " else "- 课程类型：").append(it).append('\n') }
        append(if (english) "- Date: " else "- 日期：").append(date).append('\n')
        append(if (english) "- Duration: " else "- 时长：").append(clock(session.durationSec)).append('\n')
        append(if (english) "- Transcript characters: " else "- 转写字数：").append(session.finalCharCount)
    }
    val cells = JSONArray().put(markdownCell(header))
    data class Event(val timeMs: Long, val order: Int, val createdAt: Long, val id: String, val segment: TranscriptSegment? = null, val photo: BoardPhoto? = null, val note: ClassroomNote? = null)
    val events = segments.map { Event(it.startMs, 0, it.createdAt, it.id, segment = it) } +
        notes.map { Event(it.atMs, 1, it.createdAt, it.id, note = it) } +
        photos.map { Event(it.capturedAtMs, 2, it.createdAt, it.id, photo = it) }
    var photoIndex = 0
    events.sortedWith(compareBy<Event>(Event::timeMs, Event::order, Event::createdAt, Event::id)).forEach { event ->
        val segment = event.segment
        if (segment != null) {
            cells.put(markdownCell("[${clock(segment.startMs / 1_000)}–${clock(segment.endMs / 1_000)}] ${segment.text.trim().replace(Regex("\\s+"), " ")}"))
        } else if (event.note != null) {
            cells.put(markdownCell(formatClassroomNoteTimestamp(event.note, english)))
        } else {
            val photo = requireNotNull(event.photo)
            require(photo.sessionId == session.id && photo.capturedAtMs >= 0) { tr("板书照片与课堂不匹配", "Board photo does not match this class") }
            val bytes = loadPhoto(photo)
            val mime = imageMime(bytes) ?: error(tr("板书照片损坏或格式不支持", "Board photo is corrupted or uses an unsupported format"))
            val extension = if (mime == "image/png") "png" else "jpg"
            val name = "board-${++photoIndex}.$extension"
            val photoLabel = if (english) "Board photo" else "板书照片"
            val source = "[${clock(photo.capturedAtMs / 1_000)}] $photoLabel\n\n![$photoLabel](attachment:$name)"
            cells.put(markdownCell(source, JSONObject().put(name, JSONObject().put(mime, Base64.getEncoder().encodeToString(bytes)))))
        }
    }
    val notebook = JSONObject()
        .put("cells", cells)
        .put("metadata", JSONObject().put("language_info", JSONObject().put("name", "markdown")))
        .put("nbformat", 4)
        .put("nbformat_minor", 4)
    return NotebookExport(markdown.fileName.removeSuffix(".md") + ".ipynb", notebook.toString(2))
}

private fun markdownCell(source: String, attachments: JSONObject = JSONObject()) = JSONObject()
    .put("cell_type", "markdown")
    .put("metadata", JSONObject())
    .put("source", JSONArray().put(source))
    .put("attachments", attachments)

private fun imageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
    bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xff) == 0xd8 && (bytes[bytes.lastIndex - 1].toInt() and 0xff) == 0xff && (bytes.last().toInt() and 0xff) == 0xd9 -> "image/jpeg"
    else -> null
}

private fun clock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
