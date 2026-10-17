package com.classmemory.app.data

import com.classmemory.app.i18n.AppLanguage

fun formatClassroomNoteLabel(note: ClassroomNote, english: Boolean = AppLanguage.isEnglish): String = when (note.kind) {
    ClassroomNoteKind.QUESTION -> if (english) "Student question" else "学生疑问"
    ClassroomNoteKind.COMMENT -> if (english) "Student comment" else "学生注释"
}

fun formatClassroomNoteTimestamp(note: ClassroomNote, english: Boolean = AppLanguage.isEnglish): String =
    "[${noteClock(note.atMs / 1_000)}] ${formatClassroomNoteLabel(note, english)}${if (english) ":" else "："}\n\n" +
        note.text.lines().joinToString("\n") { "> $it" }

private fun noteClock(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3_600, seconds % 3_600 / 60, seconds % 60)
