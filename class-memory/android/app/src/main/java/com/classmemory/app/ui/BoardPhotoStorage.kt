package com.classmemory.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val BOARD_PHOTO_MAX_EDGE = 2560

internal fun boardPhotoRelativePath(sessionId: String, photoId: String): String {
    require(UUID.fromString(sessionId).toString() == sessionId) { "Invalid session ID" }
    require(UUID.fromString(photoId).toString() == photoId) { "Invalid photo ID" }
    return "board-photos/$sessionId/$photoId.jpg"
}

internal fun boardPhotoCapturedAtMs(startedAtMs: Long, capturedAtMs: Long): Long =
    (capturedAtMs - startedAtMs).coerceAtLeast(0L)

internal fun createTemporaryBoardPhoto(context: Context): Pair<File, android.net.Uri> {
    val directory = File(context.cacheDir, "board-photo-capture").apply {
        check(isDirectory || mkdirs()) { "Cannot create photo cache" }
    }
    val file = File.createTempFile("board-", ".jpg", directory)
    return try {
        file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (failure: Exception) {
        file.delete()
        throw failure
    }
}

/** Re-encodes camera output so the stored JPEG has no source EXIF or GPS metadata. */
internal suspend fun storeBoardPhoto(context: Context, temporary: File, sessionId: String): String =
    withContext(Dispatchers.IO) {
        val relativePath = boardPhotoRelativePath(sessionId, UUID.randomUUID().toString())
        val destination = File(context.filesDir, relativePath)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid camera image" }
            var sampleSize = 1
            while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > BOARD_PHOTO_MAX_EDGE) {
                sampleSize *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                temporary.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: error("Cannot decode camera image")
            val orientation = ExifInterface(temporary.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val oriented = orientBoardPhoto(decoded, orientation)
            if (oriented !== decoded) decoded.recycle()
            val longest = maxOf(oriented.width, oriented.height)
            val scaled = if (longest > BOARD_PHOTO_MAX_EDGE) {
                val ratio = BOARD_PHOTO_MAX_EDGE.toFloat() / longest
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * ratio).toInt().coerceAtLeast(1),
                    (oriented.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else oriented
            if (scaled !== oriented) oriented.recycle()
            try {
                check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
                    "Cannot create photo directory"
                }
                destination.outputStream().use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Cannot encode JPEG" }
                }
            } finally {
                scaled.recycle()
            }
            relativePath
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        } finally {
            temporary.delete()
        }
    }

private fun orientBoardPhoto(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
