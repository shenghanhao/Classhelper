package com.classmemory.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.BoardPhoto
import com.classmemory.app.data.Session
import com.classmemory.app.data.TranscriptSegment
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.export.shareMarkdown
import com.classmemory.app.export.shareNotebook
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.repository.BoardPhotoDeletionResult
import com.classmemory.app.repository.isSafeBoardPhotoPath
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.icons.ClassMemoryIcons
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExportFormat { Markdown, Notebook }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(repo: SessionRepository, id: String, onBack: () -> Unit) {
    val session by repo.session(id).collectAsState(null)
    val rows by repo.segments(id).collectAsState(initial = null as List<TranscriptSegment>?)
    val photos by repo.photos(id).collectAsState(initial = null as List<BoardPhoto>?)
    val notes by repo.classroomNotes(id).collectAsState(initial = null as List<ClassroomNote>?)
    val rowsSnapshot = rows.orEmpty()
    val photosSnapshot = photos.orEmpty()
    val notesSnapshot = notes.orEmpty()
    val dataLoaded = rows != null && photos != null && notes != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val timeline = remember(rowsSnapshot, photosSnapshot, notesSnapshot) { buildSessionTimeline(rowsSnapshot, photosSnapshot, notesSnapshot) }
    var timelineFilter by rememberSaveable(id) { mutableStateOf(SessionTimelineFilter.ALL) }
    var timelineFilterMenuExpanded by remember(id) { mutableStateOf(false) }
    val filteredTimeline = remember(timeline, timelineFilter) { filterSessionTimeline(timeline, timelineFilter) }
    val boardPhotoCount = timeline.count { it is SessionTimelineItem.Photo }
    val noteCount = timeline.count { it is SessionTimelineItem.Note }
    var exportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var exporting by remember { mutableStateOf<ExportFormat?>(null) }
    var updatingOrganized by remember(id) { mutableStateOf(false) }
    var selectedPhoto by remember(id) { mutableStateOf<BoardPhoto?>(null) }
    var deletingPhotoId by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id, photosSnapshot.map { it.id }.toSet(), deletingPhotoId) {
        val selected = selectedPhoto
        if (selected != null && deletingPhotoId != selected.id && photosSnapshot.none { it.id == selected.id }) {
            selectedPhoto = null
        }
    }

    fun startExport(format: ExportFormat) {
        val current = session ?: return
        if (!dataLoaded || selectedPhoto != null || exporting != null) return
        exportFormat = null
        exporting = format
        scope.launch {
            val result = runCatching {
                when (format) {
                    ExportFormat.Markdown -> shareMarkdown(context, current, rowsSnapshot, notesSnapshot)
                    ExportFormat.Notebook -> shareNotebook(context, current, rowsSnapshot, photosSnapshot, notesSnapshot)
                }
            }
            exporting = null
            result.onFailure { snackbar.showSnackbar(it.message ?: tr("导出失败，请稍后重试", "Export failed. Try again.")) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(session?.title ?: tr("课堂详情", "Class details"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { TextButton(onClick = onBack) { Text(tr("返回", "Back")) } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SessionInformationHeader(
                    session = session,
                    photoCount = photosSnapshot.size,
                    updatingOrganized = updatingOrganized,
                    onToggleOrganized = {
                        val current = session ?: return@SessionInformationHeader
                        if (updatingOrganized) return@SessionInformationHeader
                        updatingOrganized = true
                        scope.launch {
                            runCatching { repo.updateOrganized(current.id, !current.isOrganized) }
                                .onFailure { snackbar.showSnackbar(it.message ?: tr("更新整理状态失败，请稍后重试", "Couldn't update organization status. Try again.")) }
                            updatingOrganized = false
                        }
                    },
                )
            }
            item { ExportShortcutSection(session != null && dataLoaded && exporting == null, exporting) { exportFormat = it } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("课堂时间线", "Class timeline"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Box {
                        FilterChip(
                            selected = timelineFilter != SessionTimelineFilter.ALL,
                            onClick = { timelineFilterMenuExpanded = true },
                            label = {
                                Text(
                                    timelineFilter.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 124.dp),
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = tr("筛选课堂时间线", "Filter class timeline") },
                        )
                        DropdownMenu(
                            expanded = timelineFilterMenuExpanded,
                            onDismissRequest = { timelineFilterMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 210.dp),
                        ) {
                            TimelineFilterMenuItem(tr("所有", "All"), timeline.size, timelineFilter == SessionTimelineFilter.ALL) {
                                timelineFilter = SessionTimelineFilter.ALL
                                timelineFilterMenuExpanded = false
                            }
                            TimelineFilterMenuItem(tr("板书", "Board photos"), boardPhotoCount, timelineFilter == SessionTimelineFilter.BOARD_PHOTOS) {
                                timelineFilter = SessionTimelineFilter.BOARD_PHOTOS
                                timelineFilterMenuExpanded = false
                            }
                            TimelineFilterMenuItem(tr("疑问、注释", "Questions and comments"), noteCount, timelineFilter == SessionTimelineFilter.NOTES) {
                                timelineFilter = SessionTimelineFilter.NOTES
                                timelineFilterMenuExpanded = false
                            }
                        }
                    }
                }
                Text(tr("转写、疑问、注释与板书按课堂时间排列", "Transcripts, questions, comments, and board photos in class-time order"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filteredTimeline.isEmpty()) {
                item {
                    when (timelineFilter) {
                        SessionTimelineFilter.ALL -> EmptyTimelineCard()
                        SessionTimelineFilter.BOARD_PHOTOS -> EmptyTimelineCard(tr("暂无板书照片", "No board photos yet"))
                        SessionTimelineFilter.NOTES -> EmptyTimelineCard(tr("暂无疑问或注释", "No questions or comments yet"))
                    }
                }
            }
            items(filteredTimeline, key = SessionTimelineItem::key) { item ->
                TimelineItem(item) { photo ->
                    if (exporting == null && photo.sessionId == id) selectedPhoto = photo
                }
            }
        }
    }

    exportFormat?.let { selected ->
        ExportFormatSheet(selected, session != null && dataLoaded && exporting == null, { exportFormat = null }, { exportFormat = it }, ::startExport)
    }
    selectedPhoto?.let { photo ->
        if (exporting == null) {
            BoardPhotoViewer(
                photo = photo,
                ownerScope = scope,
                deleting = deletingPhotoId == photo.id,
                deletePhoto = { targetSessionId, photoId ->
                    check(targetSessionId == id) { tr("板书照片不属于当前课堂", "This board photo doesn't belong to the current class") }
                    repo.deleteBoardPhoto(targetSessionId, photoId)
                },
                onDeletePending = { deletingPhotoId = it },
                onDeleteCompleted = { result ->
                    selectedPhoto = null
                    deletingPhotoId = null
                    scope.launch {
                        snackbar.showSnackbar(
                            when (result) {
                                BoardPhotoDeletionResult.DELETED -> tr("板书照片已删除", "Board photo deleted")
                                BoardPhotoDeletionResult.FILE_CLEANUP_FAILED -> tr("照片记录已删除，文件清理失败", "Photo record deleted, but its file couldn't be removed")
                            },
                        )
                    }
                },
                onClose = { if (deletingPhotoId != photo.id) selectedPhoto = null },
            )
        }
    }
}

@Composable
private fun SessionInformationHeader(
    session: Session?,
    photoCount: Int,
    updatingOrganized: Boolean,
    onToggleOrganized: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp)) {
            Text(session?.title ?: tr("正在读取课堂信息…", "Loading class details…"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            session?.let {
                Spacer(Modifier.height(6.dp))
                Text(it.courseType?.let(::displayCourseType) ?: tr("未分类课程", "Uncategorized"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.startedAt)), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    DetailStat(tr("时长", "Duration"), formatDuration(it.durationSec), Modifier.weight(1f))
                    DetailStat(tr("文字", "Text"), tr("${it.finalCharCount} 字", "${it.finalCharCount} chars"), Modifier.weight(1f))
                    DetailStat(tr("板书", "Board photos"), tr("$photoCount 张", "$photoCount"), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        statusText(it.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !updatingOrganized, onClick = onToggleOrganized)
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("已整理", "Organized"), style = MaterialTheme.typography.labelMedium)
                        Checkbox(
                            checked = it.isOrganized,
                            onCheckedChange = { onToggleOrganized() },
                            enabled = !updatingOrganized,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier = Modifier) = Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ExportShortcutSection(enabled: Boolean, exporting: ExportFormat?, onSelect: (ExportFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("导出课堂资料", "Export class materials"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (exporting != null) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(tr("正在生成 ${if (exporting == ExportFormat.Markdown) "Markdown" else "Notebook"}…", "Creating ${if (exporting == ExportFormat.Markdown) "Markdown" else "Notebook"}…"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ExportShortcutCard("MD", "Markdown", tr("只含文字，轻量通用", "Text only, lightweight and portable"), enabled, Modifier.weight(1f)) { onSelect(ExportFormat.Markdown) }
            ExportShortcutCard("NB", "Notebook", tr("文字与板书图片", "Text and board photos"), enabled, Modifier.weight(1f)) { onSelect(ExportFormat.Notebook) }
        }
    }
}

@Composable
private fun ExportShortcutCard(mark: String, title: String, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = modifier.semantics { contentDescription = tr("导出 $title：$description", "Export $title: $description") }.clickable(enabled = enabled, role = Role.Button, onClick = onClick), colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)) {
        Column(Modifier.padding(14.dp).heightIn(min = 94.dp)) {
            Text(mark, style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(7.dp)); Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportFormatSheet(selected: ExportFormat, enabled: Boolean, onDismiss: () -> Unit, onSelect: (ExportFormat) -> Unit, onConfirm: (ExportFormat) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(tr("选择导出格式", "Choose an export format"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(tr("导出是手动发起的，不会自动上传课堂内容。", "Exports are started manually. Class content is never uploaded automatically."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            ExportOption(ExportFormat.Markdown, selected, "Markdown", tr("导出转写与已保存的疑问、注释。", "Export transcripts, saved questions, and comments."), onSelect)
            ExportOption(ExportFormat.Notebook, selected, "Notebook", tr("导出完整时间线文字与内嵌板书图片。", "Export the full timeline with embedded board photos."), onSelect)
            TextButton(onClick = { onConfirm(selected) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tr("生成并分享 ${if (selected == ExportFormat.Markdown) "Markdown" else "Notebook"}", "Create and share ${if (selected == ExportFormat.Markdown) "Markdown" else "Notebook"}")) }
        }
    }
}

@Composable
private fun ExportOption(format: ExportFormat, selected: ExportFormat, title: String, description: String, onSelect: (ExportFormat) -> Unit) {
    val active = format == selected
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).border(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).clickable(role = Role.RadioButton) { onSelect(format) }, shape = RoundedCornerShape(16.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(15.dp)) { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TimelineItem(item: SessionTimelineItem, onOpenPhoto: (BoardPhoto) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp).fillMaxHeight()) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape)); Spacer(Modifier.height(4.dp)); Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(8.dp))
        when (item) {
            is SessionTimelineItem.Transcript -> TranscriptTimelineCard(item.value)
            is SessionTimelineItem.Photo -> BoardPhotoCard(item.value) { onOpenPhoto(item.value) }
            is SessionTimelineItem.Note -> ClassroomNoteTimelineCard(item.value)
        }
    }
}

@Composable
private fun TimelineFilterMenuItem(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("$count 项", "$count"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = {
            if (selected) Icon(ClassMemoryIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}

@Composable
private fun TranscriptTimelineCard(segment: TranscriptSegment) = Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
    Column(Modifier.padding(14.dp)) {
        Text("${formatDuration(segment.startMs / 1_000)} – ${formatDuration(segment.endMs / 1_000)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(segment.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun ClassroomNoteTimelineCard(note: ClassroomNote) = Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
    Column(Modifier.padding(14.dp)) {
        val kind = if (note.kind == com.classmemory.app.data.ClassroomNoteKind.QUESTION) tr("学生疑问", "Student question") else tr("学生注释", "Student comment")
        Text("${formatDuration(note.atMs / 1_000)} · $kind", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(note.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun EmptyTimelineCard(message: String = tr("这堂课还没有已确认的文字或板书。", "No confirmed text or board photos yet.")) = Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun statusText(status: com.classmemory.app.data.SessionStatus) = when (status) {
    com.classmemory.app.data.SessionStatus.recording -> tr("记录中", "Recording")
    com.classmemory.app.data.SessionStatus.completed -> tr("已完成", "Completed")
    com.classmemory.app.data.SessionStatus.failed -> tr("异常结束", "Ended unexpectedly")
}

@Composable
private fun BoardPhotoCard(photo: BoardPhoto, onOpen: () -> Unit) {
    val context = LocalContext.current
    val imageResult by produceState<Result<ImageBitmap>?>(null, photo.relativePath) { value = runCatching { loadBoardPhotoImage(context, photo, 1_800) } }
    val image = imageResult?.getOrNull()
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("${formatDuration(photo.capturedAtMs / 1_000)} · 板书照片", "${formatDuration(photo.capturedAtMs / 1_000)} · Board photo"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            when {
                imageResult == null -> Row(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(tr("正在加载板书照片", "Loading board photo")) }
                image != null -> {
                    Image(bitmap = image, contentDescription = tr("课堂板书 ${formatDuration(photo.capturedAtMs / 1_000)}，点击查看大图", "Board photo at ${formatDuration(photo.capturedAtMs / 1_000)}. Tap to view full size."), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen))
                    Text(tr("点击图片查看大图", "Tap the image to view full size"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                else -> PhotoUnavailableState(imageResult?.exceptionOrNull()?.message ?: tr("板书照片无法读取", "Board photo couldn't be read"), onOpen)
            }
        }
    }
}

@Composable
private fun PhotoUnavailableState(reason: String, onOpen: () -> Unit) = Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onOpen),
) {
    Column(Modifier.padding(14.dp)) {
        Text(tr("板书照片无法显示", "Board photo unavailable"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
        Text(tr("点击查看并删除照片记录", "Tap to view or delete this photo record"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
    }
}

private suspend fun loadBoardPhotoImage(context: Context, photo: BoardPhoto, maxEdge: Int): ImageBitmap = withContext(Dispatchers.IO) {
    require(isSafeBoardPhotoPath(photo.sessionId, photo.relativePath)) { tr("板书照片路径无效", "Invalid board photo path") }
    val root = context.filesDir.canonicalFile
    val file = File(root, photo.relativePath).canonicalFile
    require(file.path.startsWith(root.path + File.separator) && file.isFile) { tr("板书照片缺失", "Board photo is missing") }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { tr("板书照片损坏", "Board photo is damaged") }
    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxEdge) sampleSize *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap() ?: error(tr("板书照片损坏", "Board photo is damaged"))
}
