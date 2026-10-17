package com.classmemory.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.repository.BoardPhotoDeletionResult
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.isSafeBoardPhotoPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RecordingBoardPreview(
    sessionId: String,
    photos: List<BoardPhoto>,
    ownerScope: CoroutineScope,
    deletePhoto: suspend (sessionId: String, photoId: String) -> BoardPhotoDeletionResult,
    onDeleteCompleted: (BoardPhotoDeletionResult) -> Unit,
) {
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var selectedPhoto by remember(sessionId) { mutableStateOf<BoardPhoto?>(null) }
    var deletingPhotoId by remember(sessionId) { mutableStateOf<String?>(null) }
    val visiblePhotos = currentSessionBoardPhotos(photos, sessionId)
    LaunchedEffect(visiblePhotos.map { it.id }.toSet()) {
        if (selectedId != null && deletingPhotoId != selectedId && visiblePhotos.none { it.id == selectedId }) {
            selectedId = null
            selectedPhoto = null
        }
    }
    val handleColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).pointerInput(expanded) {
            var drag = 0f
            detectVerticalDragGestures(
                onVerticalDrag = { _, amount -> drag += amount },
                onDragEnd = {
                    if (kotlin.math.abs(drag) > 24f) {
                        expanded = drag < 0f
                        if (!expanded) selectedId = null
                    }
                    drag = 0f
                },
                onDragCancel = { drag = 0f },
            )
        }.clickable {
            expanded = !expanded
            if (!expanded) selectedId = null
        }.semantics { contentDescription = if (expanded) tr("收起板书预览", "Collapse board photo preview") else tr("展开板书预览", "Expand board photo preview") }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Canvas(Modifier.size(width = 12.dp, height = 6.dp)) {
                    val stroke = 1.5.dp.toPx()
                    val centerY = if (expanded) size.height * 0.3f else size.height * 0.7f
                    drawLine(handleColor, androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height - centerY), stroke)
                    drawLine(handleColor, androidx.compose.ui.geometry.Offset(size.width / 2f, size.height - centerY), androidx.compose.ui.geometry.Offset(size.width, centerY), stroke)
                }
                Surface(Modifier.width(42.dp).height(4.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.outlineVariant) {}
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(tr("板书预览", "Board photo preview"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (visiblePhotos.isEmpty()) {
                    Text(tr("还没有保存的板书照片", "No saved board photos yet"), Modifier.fillMaxWidth().height(66.dp).wrapContentHeight(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(Modifier.fillMaxWidth().heightIn(max = 124.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                        items(visiblePhotos, key = { it.id }) { photo ->
                            BoardPhotoThumbnail(photo, onClick = { selectedId = photo.id; selectedPhoto = photo })
                        }
                    }
                }
            }
        }
    }
    val selected = selectedPhoto?.takeIf { it.id == selectedId && it.sessionId == sessionId }
        ?: selectedId?.let { id -> visiblePhotos.firstOrNull { it.id == id } }
    if (selected != null) BoardPhotoViewer(
        photo = selected,
        ownerScope = ownerScope,
        deleting = deletingPhotoId == selected.id,
        deletePhoto = deletePhoto,
        onDeletePending = { deletingPhotoId = it },
        onDeleteCompleted = { result ->
            selectedId = null
            selectedPhoto = null
            deletingPhotoId = null
            onDeleteCompleted(result)
        },
        onClose = {
            if (deletingPhotoId != selected.id) {
                selectedId = null
                selectedPhoto = null
            }
        },
    )
}

@Composable
private fun BoardPhotoThumbnail(photo: BoardPhoto, onClick: () -> Unit) {
    val context = LocalContext.current
    val edge = (LocalConfiguration.current.screenHeightDp / 12).coerceIn(72, 104)
    val edgePx = with(LocalDensity.current) { edge.dp.roundToPx() }.coerceIn(1, 384)
    val result by produceState(false to null as Bitmap?, photo.id, photo.relativePath) {
        value = true to withContext(Dispatchers.IO) { decodeSafeBoardPhoto(context.filesDir, photo, edgePx) }
    }
    val (loaded, bitmap) = result
    Column(Modifier.width(edge.dp).clickable(onClick = onClick).semantics { contentDescription = tr("板书照片，${formatBoardPhotoTime(photo.capturedAtMs)}", "Board photo, ${formatBoardPhotoTime(photo.capturedAtMs)}") }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(edge.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (bitmap != null) androidx.compose.foundation.Image(bitmap!!.asImageBitmap(), tr("板书照片 ${formatBoardPhotoTime(photo.capturedAtMs)}", "Board photo ${formatBoardPhotoTime(photo.capturedAtMs)}"), Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text(if (loaded) tr("照片不可用", "Photo unavailable") else tr("加载中…", "Loading…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(formatBoardPhotoTime(photo.capturedAtMs), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun BoardPhotoViewer(
    photo: BoardPhoto,
    ownerScope: CoroutineScope,
    deleting: Boolean,
    deletePhoto: suspend (sessionId: String, photoId: String) -> BoardPhotoDeletionResult,
    onDeletePending: (String?) -> Unit,
    onDeleteCompleted: (BoardPhotoDeletionResult) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val result by produceState(false to null as Bitmap?, photo.id, photo.relativePath) {
        value = true to withContext(Dispatchers.IO) { decodeSafeBoardPhoto(context.filesDir, photo, BOARD_PHOTO_MAX_EDGE) }
    }
    val (loaded, bitmap) = result
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.id) { mutableFloatStateOf(0f) }
    var viewWidth by remember(photo.id) { mutableFloatStateOf(1f) }
    var viewHeight by remember(photo.id) { mutableFloatStateOf(1f) }
    var confirmDelete by remember(photo.id) { mutableStateOf(false) }
    var deleteError by remember(photo.id) { mutableStateOf<String?>(null) }
    var deleteInFlight by remember(photo.id) { mutableStateOf(false) }
    val busy = deleting || deleteInFlight
    fun update(nextScale: Float, x: Float = offsetX, y: Float = offsetY) {
        val image = bitmap
        val ratio = if (image != null) minOf(viewWidth / image.width, viewHeight / image.height) else 0f
        val bounded = clampBoardPhotoViewport(nextScale, x, y, viewWidth, viewHeight,
            if (image != null) image.width * ratio else 0f,
            if (image != null) image.height * ratio else 0f)
        scale = bounded.scale; offsetX = bounded.offsetX; offsetY = bounded.offsetY
    }
    LaunchedEffect(viewWidth, viewHeight, bitmap) { update(scale, offsetX, offsetY) }
    Dialog(onDismissRequest = { if (!deleting && !deleteInFlight) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(16.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatBoardPhotoTime(photo.capturedAtMs), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { if (!deleting && !deleteInFlight) confirmDelete = true }, enabled = !busy) { Text(tr("删除", "Delete")) }
                    TextButton(onClick = { if (!deleting && !deleteInFlight) onClose() }, enabled = !busy) { Text(tr("关闭", "Close")) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewWidth = it.width.toFloat().coerceAtLeast(1f); viewHeight = it.height.toFloat().coerceAtLeast(1f) }
                    .pointerInput(photo.id, viewWidth, viewHeight, bitmap) {
                        detectTransformGestures { _, pan, zoom, _ -> update(scale * zoom, offsetX + pan.x, offsetY + pan.y) }
                    }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) androidx.compose.foundation.Image(bitmap!!.asImageBitmap(), tr("板书照片 ${formatBoardPhotoTime(photo.capturedAtMs)}", "Board photo ${formatBoardPhotoTime(photo.capturedAtMs)}"), Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY }, contentScale = ContentScale.Fit)
                    else Text(if (loaded) tr("图片无法读取", "Couldn't read image") else tr("正在加载图片…", "Loading image…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { update(scale / 1.25f) }, enabled = loaded && bitmap != null && !busy, modifier = Modifier.semantics { contentDescription = tr("缩小", "Zoom out") }) { Text("−") }
                    TextButton(onClick = { update(1f, 0f, 0f) }, enabled = loaded && bitmap != null && !busy, modifier = Modifier.semantics { contentDescription = tr("复位缩放", "Reset zoom") }) { Text(tr("复位", "Reset")) }
                    TextButton(onClick = { update(scale * 1.25f) }, enabled = loaded && bitmap != null && !busy, modifier = Modifier.semantics { contentDescription = tr("放大", "Zoom in") }) { Text("＋") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting && !deleteInFlight) confirmDelete = false },
            properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
            title = { Text(tr("确定删除这张板书照片？", "Delete this board photo?")) },
            text = {
                Column {
                    Text(tr("删除后不可恢复，只影响当前板书照片，不会影响课堂文字或其他图片。", "This can't be undone. Only this photo is affected; class notes and other photos will remain."))
                    deleteError?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        requestBoardPhotoDeletion(confirmed = true, busy = deleting || deleteInFlight) {
                            deleteInFlight = true
                            deleteError = null
                            onDeletePending(photo.id)
                            ownerScope.launch {
                                try {
                                    val result = deletePhoto(photo.sessionId, photo.id)
                                    confirmDelete = false
                                    onDeleteCompleted(result)
                                } catch (error: Exception) {
                                    deleteError = error.message ?: tr("删除失败，请重试", "Delete failed. Try again.")
                                } finally {
                                    deleteInFlight = false
                                    onDeletePending(null)
                                }
                            }
                        }
                    },
                ) { Text(if (busy) tr("正在删除…", "Deleting…") else tr("确认删除", "Delete photo")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) confirmDelete = false }, enabled = !busy) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

private suspend fun decodeSafeBoardPhoto(filesDir: File, photo: BoardPhoto, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
    try {
        if (!isSafeBoardPhotoPath(photo.sessionId, photo.relativePath)) return@withContext null
        val root = filesDir.canonicalFile
        val file = File(root, photo.relativePath).canonicalFile
        if (file.path != root.path + File.separator + photo.relativePath || !file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = boardPhotoSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Exception) { null }
}

internal fun formatBoardPhotoTime(ms: Long): String = formatDuration(ms.coerceAtLeast(0L) / 1_000)
