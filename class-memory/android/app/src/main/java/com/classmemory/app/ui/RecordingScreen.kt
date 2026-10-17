package com.classmemory.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.data.ClassroomNote
import com.classmemory.app.data.ClassroomNoteKind
import com.classmemory.app.service.RecordingService
import com.classmemory.app.i18n.tr
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    repo: SessionRepository,
    onStop: () -> Unit,
    onFinished: () -> Unit = {},
    onBoardPhotoCaptured: suspend (sessionId: String, capturedAtMs: Long, relativePath: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestPhotoCallback by rememberUpdatedState(onBoardPhotoCaptured)
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotoSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPhotoStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var pendingPhotoCapturedAt by rememberSaveable { mutableLongStateOf(0L) }
    var savingPhoto by remember { mutableStateOf(false) }
    val pendingPhoto = pendingPhotoPath != null
    var photoMessage by remember { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { succeeded ->
        val temporary = pendingPhotoPath?.let(::File)
        val photographedSession = pendingPhotoSessionId
        val startedAt = pendingPhotoStartedAt
        val photographedAt = pendingPhotoCapturedAt
        pendingPhotoPath = null
        pendingPhotoSessionId = null
        if (!succeeded || temporary == null || photographedSession == null) {
            temporary?.delete()
            photoMessage = if (succeeded) tr("板书照片未保存", "Board photo wasn't saved") else tr("已取消拍照", "Photo capture canceled")
        } else {
            val capturedAtMs = boardPhotoCapturedAtMs(startedAt, photographedAt)
            savingPhoto = true
            scope.launch {
                var relativePath: String? = null
                try {
                    val savedPath = storeBoardPhoto(context, temporary, photographedSession)
                    relativePath = savedPath
                    latestPhotoCallback(photographedSession, capturedAtMs, savedPath)
                    photoMessage = tr("板书照片已保存", "Board photo saved")
                } catch (_: Exception) {
                    relativePath?.let { File(context.filesDir, it).delete() }
                    temporary.delete()
                    photoMessage = tr("板书照片保存失败，请重试", "Couldn't save board photo. Try again.")
                } finally {
                    savingPhoto = false
                }
            }
        }
    }
    val state by RecordingService.state.collectAsState()
    var sawSession by rememberSaveable { mutableStateOf(false) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var completionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var noteEditorOpen by rememberSaveable { mutableStateOf(false) }
    var noteKindName by rememberSaveable { mutableStateOf(ClassroomNoteKind.QUESTION.name) }
    var noteText by rememberSaveable { mutableStateOf("") }
    var noteAtMs by rememberSaveable { mutableLongStateOf(0L) }
    var noteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var noteId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var savingNote by remember { mutableStateOf(false) }
    var noteMessage by remember { mutableStateOf<String?>(null) }
    var noteSavedMessage by remember { mutableStateOf<String?>(null) }
    var confirmEndWithDraft by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var endRequested by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardNote by remember { mutableStateOf(false) }
    var errorDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.sessionId) {
        state.sessionId?.let { sessionId = it; sawSession = true }
        if (sawSession && state.sessionId == null) { completionMessage = state.error; finished = true }
    }
    val recentFinals by repo.segments(sessionId.orEmpty()).collectAsState(emptyList())
    val currentSessionId = state.sessionId ?: sessionId
    val boardPhotos by repo.photos(currentSessionId.orEmpty()).collectAsState(emptyList())
    val currentBoardPhotos = currentSessionId?.let { currentSessionBoardPhotos(boardPhotos, it) }.orEmpty()
    val notesSessionId = noteSessionId?.takeIf { noteText.isNotBlank() } ?: sessionId
    val classroomNotes by repo.classroomNotes(notesSessionId.orEmpty()).collectAsState(emptyList())
    var now by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var displayStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAt) {
        if (state.startedAt > 0L) displayStartedAt = state.startedAt
    }
    LaunchedEffect(state.sessionId, state.ending) {
        while (state.sessionId != null && !state.ending) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = if (displayStartedAt > 0L) {
        ((now - displayStartedAt).coerceAtLeast(0) / 1_000)
    } else {
        0L
    }
    val completionIsWarning = completionMessage?.let {
        it.contains("尾句未确认", ignoreCase = true) || it.contains("final phrase unconfirmed", ignoreCase = true)
    } == true
    val ending = !finished && (state.ending || endRequested)
    LaunchedEffect(photoMessage) {
        if (photoMessage == tr("板书照片已保存", "Board photo saved") ||
            photoMessage == tr("已取消拍照", "Photo capture canceled") ||
            photoMessage == tr("板书照片已删除", "Board photo deleted")) {
            delay(2_500)
            photoMessage = null
        }
    }
    val openNote: (ClassroomNoteKind) -> Unit = { kind ->
        if (noteText.isBlank()) {
            noteKindName = kind.name
            val startedAt = displayStartedAt.takeIf { it > 0 } ?: state.startedAt
            noteAtMs = if (startedAt > 0) (System.currentTimeMillis() - startedAt).coerceAtLeast(0) else 0L
            noteSessionId = sessionId
            noteId = java.util.UUID.randomUUID().toString()
        }
        noteMessage = null
        noteSavedMessage = null
        noteEditorOpen = true
    }
    BackHandler(enabled = noteText.isNotBlank() && !noteEditorOpen) { noteEditorOpen = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("课堂记录", "Class recording"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (finished) {
                        TextButton(
                            onClick = {
                                if (noteText.isNotBlank()) confirmEndWithDraft = true else onFinished()
                            },
                            enabled = !savingNote,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(tr("返回", "Back"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        TextButton(
                            onClick = {
                                when (endRequest(finished = false, busy = savingNote || savingPhoto || pendingPhoto || ending, hasDraft = noteText.isNotBlank())) {
                                    EndRequest.CONFIRM -> confirmEnd = true
                                    EndRequest.DRAFT -> confirmEndWithDraft = true
                                    else -> Unit
                                }
                            },
                            enabled = !savingNote && !savingPhoto && !pendingPhoto && !ending,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                if (ending) tr("正在结束…", "Ending…") else tr("结束课堂", "End class"),
                                color = if (ending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (finished) {
                Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        RecordingNotesSection(
                            boardSessionId = null,
                            boardPhotos = emptyList(),
                            photoOwnerScope = scope,
                            onDeletePhoto = { _, _ -> error(tr("课堂已结束，不能删除当前板书照片", "Can't delete a board photo after the class has ended")) },
                            onPhotoDeleteResult = {},
                            notes = classroomNotes,
                            enabled = !savingNote && noteText.isNotBlank(),
                            message = noteMessage,
                            savedMessage = noteSavedMessage,
                            photoMessage = null,
                            hasDraft = noteText.isNotBlank(),
                            onResumeDraft = { noteEditorOpen = true },
                            onAddQuestion = { openNote(ClassroomNoteKind.QUESTION) },
                            onAddComment = { openNote(ClassroomNoteKind.COMMENT) },
                        )
                        Button(
                            onClick = { if (noteText.isNotBlank()) confirmEndWithDraft = true else onFinished() },
                            enabled = !savingNote,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(tr("返回历史课堂", "Back to past classes")) }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            } else if (state.sessionId != null) {
                Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        RecordingNotesSection(
                            boardSessionId = currentSessionId,
                            boardPhotos = currentBoardPhotos,
                            photoOwnerScope = scope,
                            onDeletePhoto = { targetSessionId, photoId -> repo.deleteBoardPhoto(targetSessionId, photoId) },
                            onPhotoDeleteResult = { result ->
                                photoMessage = when (result) {
                                    com.classmemory.app.repository.BoardPhotoDeletionResult.DELETED -> tr("板书照片已删除", "Board photo deleted")
                                    com.classmemory.app.repository.BoardPhotoDeletionResult.FILE_CLEANUP_FAILED -> tr("照片记录已删除，文件清理失败", "Photo record deleted, but its file couldn't be removed")
                                }
                            },
                            notes = classroomNotes,
                            enabled = !savingNote && !ending && (!finished || noteText.isNotBlank()),
                            message = noteMessage,
                            savedMessage = noteSavedMessage,
                            photoMessage = photoMessage,
                            hasDraft = noteText.isNotBlank(),
                            onResumeDraft = { noteEditorOpen = true },
                            onAddQuestion = { openNote(ClassroomNoteKind.QUESTION) },
                            onAddComment = { openNote(ClassroomNoteKind.COMMENT) },
                        )
                        OutlinedButton(
                            onClick = {
                                try {
                                    val (temporary, uri) = createTemporaryBoardPhoto(context)
                                    pendingPhotoPath = temporary.absolutePath
                                    pendingPhotoSessionId = state.sessionId
                                    pendingPhotoStartedAt = state.startedAt
                                    pendingPhotoCapturedAt = System.currentTimeMillis()
                                    photoMessage = null
                                    takePicture.launch(uri)
                                } catch (_: Exception) {
                                    pendingPhotoPath?.let { File(it).delete() }
                                    pendingPhotoPath = null
                                    pendingPhotoSessionId = null
                                    photoMessage = tr("无法打开相机，请重试", "Couldn't open the camera. Try again.")
                                }
                            },
                            enabled = !ending && !savingNote && !pendingPhoto && !savingPhoto,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            CameraSymbol()
                            Spacer(Modifier.width(10.dp))
                            Text(when {
                                savingPhoto -> tr("正在保存照片…", "Saving photo…")
                                pendingPhoto -> tr("正在打开相机…", "Opening camera…")
                                photoMessage?.let(::isUiErrorMessage) == true -> tr("重新拍摄板书", "Retake board photo")
                                else -> tr("拍摄板书", "Take board photo")
                            })
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
        ) {
            RecordingHeader(
                label = when {
                    finished && completionMessage != null && !completionIsWarning -> tr("异常结束", "Ended unexpectedly")
                    finished && completionIsWarning -> tr("已结束 · 尾句未确认", "Ended · final phrase unconfirmed")
                    finished -> tr("课堂已保存", "Class saved")
                    ending -> tr("等待最后一句确认", "Waiting for the final phrase")
                    state.error != null -> tr("本地识别异常", "Local recognition error")
                    state.asrReady -> tr("正在记录", "Recording")
                    else -> tr("准备本地识别", "Preparing ASR")
                },
                tone = when {
                    finished && completionMessage != null && !completionIsWarning -> RecordingTone.Error
                    finished && completionIsWarning -> RecordingTone.Warning
                    finished -> RecordingTone.Success
                    ending -> RecordingTone.Warning
                    state.error != null -> RecordingTone.Error
                    state.asrReady -> RecordingTone.Success
                    else -> RecordingTone.Warning
                },
                seconds = seconds,
            )

            val recordingError = if (finished) null else state.error
            recordingError?.let { detail ->
                RecordingErrorDetails(
                    detail = detail,
                    expanded = errorDetailsExpanded,
                    onToggle = { errorDetailsExpanded = !errorDetailsExpanded },
                    title = tr("本地语音识别异常", "Local speech recognition error"),
                )
            }

            if (finished) {
                CompletionContent(completionMessage, completionIsWarning, state.finalCount)
            } else {
                RecordingContent(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    stateReady = state.asrReady,
                    recentFinals = newestSegmentsFirst(recentFinals),
                    finalCount = state.finalCount,
                    partial = state.partial,
                    ending = ending,
                )
            }
        }
    }

    if (confirmEnd && !finished) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(tr("是否确认结束课堂？", "End this class?")) },
            confirmButton = {
                TextButton(enabled = !finished && !savingNote && !savingPhoto && !pendingPhoto && !ending, onClick = {
                    confirmEnd = false
                    endRequested = true
                    onStop()
                }) { Text(tr("确认结束", "End class")) }
            },
            dismissButton = { TextButton(enabled = !savingNote && !savingPhoto && !pendingPhoto, onClick = { confirmEnd = false }) { Text(tr("继续记录", "Keep recording")) } },
        )
    }

    if (noteEditorOpen) {
        ModalBottomSheet(
            onDismissRequest = { if (!savingNote) noteEditorOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 620.dp).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(tr("记下这一刻", "Capture a moment"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(tr("${if (finished) "课堂已结束，草稿尚未保存" else if (state.ending) "课堂正在结束，草稿尚未保存" else "录制继续进行"} · ${formatDuration(noteAtMs / 1_000)}", "${if (finished) "Class ended; draft not saved" else if (state.ending) "Class is ending; draft not saved" else "Recording continues"} · ${formatDuration(noteAtMs / 1_000)}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = noteKindName == ClassroomNoteKind.QUESTION.name, onClick = { noteKindName = ClassroomNoteKind.QUESTION.name }, enabled = !savingNote && !ending, label = { Text(tr("疑问", "Question")) })
                    FilterChip(selected = noteKindName == ClassroomNoteKind.COMMENT.name, onClick = { noteKindName = ClassroomNoteKind.COMMENT.name }, enabled = !savingNote && !ending, label = { Text(tr("注释", "Comment")) })
                }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= 2_000) noteText = it },
                    enabled = !savingNote && !ending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    label = { Text(if (noteKindName == ClassroomNoteKind.QUESTION.name) tr("这里有什么没听懂？", "What wasn't clear?") else tr("写下理解、补充或提醒自己的话", "Add a note, clarification, or reminder")) },
                    placeholder = { Text(tr("支持多行输入…", "Multiple lines supported…")) },
                    supportingText = { Text(tr("${noteText.length}/2000 字", "${noteText.length}/2000 characters")) },
                    minLines = 4,
                    maxLines = 8,
                )
                noteMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { noteEditorOpen = false }, enabled = !savingNote, modifier = Modifier.weight(1f)) { Text(tr("稍后继续", "Continue later"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                    onClick = {
                        val target = noteSessionId ?: return@Button
                        val submittedId = noteId
                        val submittedText = noteText
                        val submittedAtMs = noteAtMs
                        val submittedKind = ClassroomNoteKind.valueOf(noteKindName)
                        savingNote = true
                        noteMessage = null
                        scope.launch {
                            try {
                                repo.addClassroomNote(target, submittedAtMs, submittedKind, submittedText, submittedId)
                                noteSavedMessage = tr("${if (submittedKind == ClassroomNoteKind.QUESTION) "疑问" else "注释"}已保存 · ${formatDuration(submittedAtMs / 1_000)}", "${if (submittedKind == ClassroomNoteKind.QUESTION) "Question" else "Comment"} saved · ${formatDuration(submittedAtMs / 1_000)}")
                                noteText = ""
                                noteSessionId = null
                                noteId = java.util.UUID.randomUUID().toString()
                                noteEditorOpen = false
                            } catch (error: Exception) {
                                noteMessage = error.message ?: tr("保存失败，内容还在这里，请重试", "Couldn't save. Your text is still here; try again.")
                            } finally { savingNote = false }
                        }
                    },
                    enabled = noteText.isNotBlank() && !savingNote && !ending && noteSessionId != null,
                    modifier = Modifier.weight(1.5f).heightIn(min = 50.dp),
                ) { Text(if (savingNote) tr("正在保存…", "Saving…") else if (noteKindName == ClassroomNoteKind.QUESTION.name) tr("保存疑问", "Save question") else tr("保存注释", "Save comment"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                TextButton(onClick = { confirmDiscardNote = true }, enabled = !savingNote && !ending, modifier = Modifier.align(Alignment.End)) { Text(tr("丢弃草稿", "Discard draft")) }
            }
        }
    }
    if (confirmDiscardNote) {
        AlertDialog(
            onDismissRequest = { confirmDiscardNote = false },
            title = { Text(tr("放弃这条草稿？", "Discard this draft?")) },
            text = { Text(tr("尚未保存的疑问或注释会被丢弃。", "This unsaved question or comment will be discarded.")) },
            confirmButton = { TextButton(enabled = !savingNote && !ending, onClick = { noteText = ""; noteSessionId = null; noteEditorOpen = false; confirmDiscardNote = false }) { Text(tr("放弃草稿", "Discard draft")) } },
            dismissButton = { TextButton(enabled = !savingNote && !ending, onClick = { confirmDiscardNote = false }) { Text(tr("继续编辑", "Keep editing")) } },
        )
    }
    if (confirmEndWithDraft) {
        AlertDialog(
            onDismissRequest = { confirmEndWithDraft = false },
            title = { Text(if (noteKindName == ClassroomNoteKind.QUESTION.name) tr("还有一条未保存的疑问", "You have an unsaved question") else tr("还有一条未保存的注释", "You have an unsaved comment")) },
            text = { Text(if (finished) tr("返回历史课堂前选择如何处理这条草稿。", "Choose what to do with this draft before leaving.") else tr("结束课堂前选择如何处理这条草稿。", "Choose what to do with this draft before ending the class.")) },
            confirmButton = {
                TextButton(enabled = !savingNote && !savingPhoto && !pendingPhoto && !ending, onClick = {
                    val target = noteSessionId
                    if (target == null || noteText.isBlank()) { confirmEndWithDraft = false; if (finished) onFinished() else { endRequested = true; onStop() } }
                    else {
                        val submittedId = noteId
                        val submittedText = noteText
                        val submittedAtMs = noteAtMs
                        val submittedKind = ClassroomNoteKind.valueOf(noteKindName)
                        savingNote = true
                        scope.launch {
                            try {
                                repo.addClassroomNote(target, submittedAtMs, submittedKind, submittedText, submittedId)
                                noteSavedMessage = tr("${if (submittedKind == ClassroomNoteKind.QUESTION) "疑问" else "注释"}已保存 · ${formatDuration(submittedAtMs / 1_000)}", "${if (submittedKind == ClassroomNoteKind.QUESTION) "Question" else "Comment"} saved · ${formatDuration(submittedAtMs / 1_000)}")
                                noteText = ""; noteSessionId = null; noteEditorOpen = false; confirmEndWithDraft = false
                                if (finished) onFinished() else { endRequested = true; onStop() }
                            } catch (error: Exception) {
                                confirmEndWithDraft = false
                                noteMessage = error.message ?: tr("保存失败，内容还在这里，请重试", "Couldn't save. Your text is still here; try again.")
                                noteEditorOpen = true
                            } finally { savingNote = false }
                        }
                    }
                }) { Text(if (finished) tr("保存并返回", "Save and return") else tr("保存并结束", "Save and end"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !savingNote && !ending, onClick = { confirmEndWithDraft = false; noteEditorOpen = true }) { Text(tr("继续编辑", "Keep editing"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    TextButton(enabled = !savingNote && !savingPhoto && !pendingPhoto && !ending, onClick = { noteText = ""; noteSessionId = null; noteEditorOpen = false; confirmEndWithDraft = false; if (finished) onFinished() else { endRequested = true; onStop() } }) { Text(if (finished) tr("放弃并返回", "Discard and return") else tr("放弃并结束", "Discard and end"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
        )
    }
}

private enum class RecordingTone { Success, Warning, Error, Neutral }

private fun isUiErrorMessage(message: String): Boolean =
    listOf("失败", "无法", "未保存", "failed", "couldn't", "wasn't saved", "unable", "error")
        .any { message.contains(it, ignoreCase = true) }

@Composable
private fun RecordingNotesSection(
    boardSessionId: String?,
    boardPhotos: List<com.classmemory.app.data.BoardPhoto>,
    photoOwnerScope: kotlinx.coroutines.CoroutineScope,
    onDeletePhoto: suspend (sessionId: String, photoId: String) -> com.classmemory.app.repository.BoardPhotoDeletionResult,
    onPhotoDeleteResult: (com.classmemory.app.repository.BoardPhotoDeletionResult) -> Unit,
    notes: List<ClassroomNote>,
    enabled: Boolean,
    message: String?,
    savedMessage: String?,
    photoMessage: String?,
    hasDraft: Boolean,
    onResumeDraft: () -> Unit,
    onAddQuestion: () -> Unit,
    onAddComment: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        if (boardSessionId != null) RecordingBoardPreview(boardSessionId, boardPhotos, photoOwnerScope, onDeletePhoto, onPhotoDeleteResult)
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            val latest = newestNote(notes)
            Text(
                text = when {
                    hasDraft -> tr("有未保存草稿 · 点击恢复编辑", "Unsaved draft · tap to resume")
                    latest != null -> noteSummary(latest)
                    else -> tr("暂无标注", "No notes yet")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(if (hasDraft) Modifier.clickable(enabled = enabled, onClick = onResumeDraft) else Modifier),
            )
            Spacer(Modifier.width(8.dp))
            Text(tr("${notes.size} 条", "${notes.size}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (hasDraft) onResumeDraft() else onAddQuestion() }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(tr("添加疑问", "Add question"), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                TextButton(onClick = { if (hasDraft) onResumeDraft() else onAddComment() }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(tr("添加注释", "Add comment"), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        (message ?: photoMessage ?: savedMessage)?.takeIf { it.isNotBlank() }?.let {
            val isError = message != null || isUiErrorMessage(it)
            Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun RecordingHeader(
    label: String,
    tone: RecordingTone,
    seconds: Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(label, tone, Modifier.weight(1f))
        Text(
            text = formatDuration(seconds),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            letterSpacing = (-1).sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun StatusPill(label: String, tone: RecordingTone, modifier: Modifier = Modifier) {
    val (container, content) = toneColors(tone)
    Surface(modifier = modifier, color = container, contentColor = content, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(content))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecordingErrorDetails(
    detail: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    title: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.heightIn(min = 32.dp)) {
                Text(if (expanded) tr("收起原因", "Hide details") else tr("查看原因", "View details"), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (expanded) {
            Text(detail, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RecordingContent(
    modifier: Modifier,
    stateReady: Boolean,
    recentFinals: List<com.classmemory.app.data.TranscriptSegment>,
    finalCount: Int,
    partial: String,
    ending: Boolean,
) {
    val newest = recentFinals.take(3)
    val recentText = newest.joinToString("\n") { "${formatDuration(it.startMs / 1_000)}  ${it.text}" }
    val (temporaryWeight, confirmedWeight) = recordingTranscriptWeights()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            TranscriptSection(
                modifier = Modifier.weight(temporaryWeight).fillMaxWidth(),
                label = when {
                    ending -> tr("正在结束 · 临时文字 · 尚未保存", "Ending · temporary text · not saved")
                    stateReady -> tr("正在识别 · 临时文字 · 尚未保存", "Recognizing · temporary text · not saved")
                    else -> tr("准备中 · 临时文字 · 尚未保存", "Preparing · temporary text · not saved")
                },
                text = partial.ifBlank { if (stateReady) tr("暂时没有语音", "No speech yet") else tr("识别准备中…", "Preparing recognition…") },
                partial = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            TranscriptSection(
                modifier = Modifier.weight(confirmedWeight).fillMaxWidth(),
                label = tr("最近已确认 · 已保存 $finalCount 字", "Recently confirmed · $finalCount characters saved"),
                text = recentText.ifBlank { if (stateReady) tr("暂无已确认文字", "No confirmed text yet") else tr("等待本地识别…", "Waiting for local recognition…") },
                partial = false,
            )
        }
    }
}

@Composable
private fun CameraSymbol() {
    Box(
        modifier = Modifier
            .size(width = 21.dp, height = 16.dp)
            .border(1.5.dp, LocalContentColor.current, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(7.dp).border(1.5.dp, LocalContentColor.current, CircleShape))
    }
}

@Composable
private fun TranscriptSection(modifier: Modifier, label: String, text: String, partial: Boolean) {
    val scrollState = rememberScrollState()
    val direction = if (partial) TranscriptDirection.LATEST_AT_END else TranscriptDirection.LATEST_AT_START
    var followLatest by remember { mutableStateOf(true) }
    val userScrollConnection = remember(scrollState, direction) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    followLatest = shouldFollowTranscriptLatest(
                        currentlyFollowing = followLatest,
                        isUserScroll = true,
                        scrollOffset = scrollState.value,
                        maxScrollOffset = scrollState.maxValue,
                        direction = direction,
                    )
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(text, scrollState.maxValue, followLatest) {
        if (followLatest && !scrollState.isScrollInProgress) {
            scrollState.scrollTo(if (direction == TranscriptDirection.LATEST_AT_START) 0 else scrollState.maxValue)
        }
    }
    BoxWithConstraints(modifier.nestedScroll(userScrollConnection)) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (maxWidth < 360.dp) 12.dp else 16.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (partial) partialTranscriptInOrder(text) else text,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 5.dp).verticalScroll(scrollState),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!followLatest) {
                TextButton(
                    onClick = { followLatest = true },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) { Text(tr("回到最新", "Back to latest"), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun CompletionContent(message: String?, warning: Boolean, finalCount: Int) {
    message?.let {
        InlineMessageCard(
            title = if (warning) tr("有未确认的临时文字", "Temporary text wasn't confirmed") else tr("课堂记录异常结束", "Recording ended unexpectedly"),
            detail = tr("$it。此前已确认的文字仍保存在历史课堂中。", "$it. Previously confirmed text is still available in past classes."),
            tone = if (warning) RecordingTone.Warning else RecordingTone.Error,
        )
    }
    InlineMessageCard(
        title = tr("本次已确认 $finalCount 字", "$finalCount characters confirmed"),
        detail = tr("未确认的临时文字不会保存。", "Unconfirmed temporary text isn't saved."),
        tone = RecordingTone.Neutral,
    )
}

@Composable
private fun InlineMessageCard(
    title: String,
    detail: String?,
    tone: RecordingTone,
    showProgress: Boolean = false,
) {
    val (container, content) = toneColors(tone)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = content,
                        strokeWidth = 2.dp,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            detail?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.82f),
                )
            }
            if (showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    color = content,
                    trackColor = content.copy(alpha = 0.16f),
                )
            }
        }
    }
}

@Composable
private fun toneColors(tone: RecordingTone): Pair<Color, Color> = when (tone) {
    RecordingTone.Success -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    RecordingTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    RecordingTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    RecordingTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}
