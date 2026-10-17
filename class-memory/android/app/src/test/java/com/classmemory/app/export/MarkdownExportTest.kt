package com.classmemory.app.export

import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportTest {
    @Test
    fun buildMarkdownExport_containsMetadataAndTimestampedTranscript() {
        val startedAt = Instant.parse("2026-09-22T01:02:03Z").toEpochMilli()
        val session = Session(
            id = "session-1",
            startedAt = startedAt,
            title = "理论力学",
            courseType = "大学物理ⅠB",
            status = SessionStatus.completed,
            durationSec = 3_661,
            finalCharCount = 8,
        )
        val segments = listOf(
            TranscriptSegment("1", session.id, 2_000, 6_000, "力系  平衡", startedAt, "key"),
        )

        val export = buildMarkdownExport(session, segments, ZoneId.of("Asia/Shanghai"), english = false)

        assertEquals("理论力学-2026-09-22_09-02.md", export.fileName)
        assertTrue(export.content.contains("# 理论力学"))
        assertTrue(export.content.contains("- 课程类型：大学物理ⅠB"))
        assertTrue(export.content.contains("- 日期：2026-09-22 09:02"))
        assertTrue(export.content.contains("- 时长：01:01:01"))
        assertTrue(export.content.contains("[00:00:02–00:00:06] 力系 平衡"))
    }

    @Test
    fun buildMarkdownExport_englishLabelsKeepChineseUserContent() {
        val startedAt = Instant.parse("2026-09-22T01:02:03Z").toEpochMilli()
        val session = Session(
            id = "session-english",
            startedAt = startedAt,
            title = "理论力学",
            courseType = "大学物理ⅠB",
            status = SessionStatus.completed,
            durationSec = 3_661,
            finalCharCount = 8,
        )
        val segment = TranscriptSegment("segment-english", session.id, 2_000, 6_000, "力系 平衡", startedAt, "key")

        val export = buildMarkdownExport(session, listOf(segment), ZoneId.of("Asia/Shanghai"), english = true)

        assertTrue(export.content.contains("# 理论力学"))
        assertTrue(export.content.contains("- Course: 大学物理ⅠB"))
        assertTrue(export.content.contains("- Date: 2026-09-22 09:02"))
        assertTrue(export.content.contains("- Duration: 01:01:01"))
        assertTrue(export.content.contains("## Transcript"))
        assertTrue(export.content.contains("[00:00:02–00:00:06] 力系 平衡"))
    }

    @Test
    fun buildMarkdownExport_sanitizesFileNameAndHandlesNoTranscript() {
        val session = Session(
            id = "session-2",
            startedAt = 0,
            title = "工程/力学:第一讲",
            status = SessionStatus.completed,
        )

        val export = buildMarkdownExport(session, emptyList(), ZoneId.of("UTC"), english = false)

        assertEquals("工程_力学_第一讲-1970-01-01_00-00.md", export.fileName)
        assertTrue(export.content.contains("_暂无转写内容_"))
    }

    @Test
    fun buildMarkdownExport_keepsNotesTimestampedAndSeparateFromTranscriptCount() {
        val session = Session("s", 0, title = "课堂", status = SessionStatus.completed, finalCharCount = 0)
        val question = ClassroomNote("q", "s", 2_000, ClassroomNoteKind.QUESTION, "为什么是 *这样*？\n第二行 `x`", 2)
        val comment = ClassroomNote("c", "s", 2_000, ClassroomNoteKind.COMMENT, "补充说明", 3)

        val export = buildMarkdownExport(session, emptyList(), ZoneId.of("UTC"), listOf(question, comment), english = false)

        assertTrue(export.content.contains("- 转写字数：0"))
        assertTrue(export.content.contains("[00:00:02] 学生疑问：\n\n> 为什么是 *这样*？\n> 第二行 `x`"))
        assertTrue(export.content.contains("[00:00:02] 学生注释：\n\n> 补充说明"))
        assertTrue(export.content.indexOf("学生疑问") < export.content.indexOf("学生注释"))
        assertFalse(export.content.contains("暂无转写内容"))
    }

    @Test
    fun sameTimestampUsesTypeThenCreationThenIdRegardlessOfInputOrder() {
        val session = Session("s", 0, status = SessionStatus.completed)
        val segment = TranscriptSegment("t", "s", 2_000, 3_000, "老师转写", 99, "t")
        val notes = listOf(
            ClassroomNote("z", "s", 2_000, ClassroomNoteKind.COMMENT, "后项", 1),
            ClassroomNote("a", "s", 2_000, ClassroomNoteKind.QUESTION, "前项\n\n# 学生写的标题", 1),
        )
        val content = buildMarkdownExport(session, listOf(segment), ZoneId.of("UTC"), notes, english = false).content
        assertTrue(content.indexOf("老师转写") < content.indexOf("前项"))
        assertTrue(content.indexOf("前项") < content.indexOf("后项"))
        assertTrue(content.contains("> 前项\n> \n> # 学生写的标题"))
    }
}
