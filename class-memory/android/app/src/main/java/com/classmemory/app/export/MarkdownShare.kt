package com.classmemory.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.i18n.tr
import com.classmemory.app.data.ClassroomNote
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun shareMarkdown(
    context: Context,
    session: Session,
    segments: List<TranscriptSegment>,
    notes: List<ClassroomNote> = emptyList(),
) {
    val export = buildMarkdownExport(session, segments, notes = notes)
    val file = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "markdown-exports").apply { mkdirs() }
        File(directory, export.fileName).apply { writeText(export.content, Charsets.UTF_8) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.title ?: tr("课堂记录", "Classroom recording"))
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, tr("分享课堂 Markdown", "Share class Markdown")))
}
