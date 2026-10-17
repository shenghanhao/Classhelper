package com.classmemory.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.Session
import com.classmemory.app.i18n.tr
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.repository.isSafeBoardPhotoPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun shareNotebook(context: Context, session: Session, segments: List<TranscriptSegment>, photos: List<BoardPhoto>, notes: List<ClassroomNote> = emptyList()) {
    val file = withContext(Dispatchers.IO) {
        val root = context.filesDir.canonicalFile
        val export = buildNotebookExport(session, segments, photos, { photo ->
            require(isSafeBoardPhotoPath(session.id, photo.relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
            val image = File(root, photo.relativePath).canonicalFile
            require(image.path.startsWith(root.path + File.separator) && image.isFile) { tr("板书照片缺失", "Board photo is missing") }
            image.readBytes().also { bytes ->
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error(tr("板书照片损坏", "Board photo is corrupted"))
                decoded.recycle()
            }
        }, notes = notes)
        val directory = File(context.cacheDir, "notebook-exports").apply { check(isDirectory || mkdirs()) { tr("无法创建导出目录", "Could not create export directory") } }
        File(directory, export.fileName).apply { writeText(export.content, Charsets.UTF_8) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-ipynb+json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.title ?: tr("课堂记录", "Classroom recording"))
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, tr("分享课堂 Notebook", "Share class Notebook")))
}
