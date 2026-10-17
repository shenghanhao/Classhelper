package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.Session
import com.classmemory.app.BuildConfig
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.i18n.AppLanguage
import com.classmemory.app.i18n.tr
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.CustomHotwordLibrary
import com.classmemory.app.asr.CustomHotwordLibraryPreferences
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import com.classmemory.app.ui.theme.ClassMemorySpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: SessionRepository,
    onStart: () -> Unit,
    onImport: () -> Unit,
    showImport: Boolean,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onManageHotwords: () -> Unit,
) {
    val sessions by repo.sessions().collectAsState(emptyList())
    val courseTypes by repo.courseTypes().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val palette = ClassMemoryColors.current
    val supportsCourseHotwords = remember { AsrHotwordPreferences.supportsCourseHotwords() }
    val hotwordUiState = rememberHotwordUiState()
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var appendTargetId by remember { mutableStateOf<String?>(null) }
    var appendSourceId by remember { mutableStateOf<String?>(null) }
    var appending by remember { mutableStateOf(false) }
    var appendError by remember { mutableStateOf<String?>(null) }
    var debugMenuExpanded by remember { mutableStateOf(false) }
    var chooseHotwordCourse by rememberSaveable { mutableStateOf(false) }
    var pickerCustomCourses by remember { mutableStateOf<List<CustomHotwordLibrary>>(emptyList()) }
    var courseFilter by remember { mutableStateOf<CourseFilter>(CourseFilter.All) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    val filteredSessions = remember(sessions, courseFilter) { filterSessions(sessions, courseFilter) }

    LaunchedEffect(courseTypes, courseFilter) {
        val selected = courseFilter
        if (selected is CourseFilter.Tagged && courseTypes.none { it.name == selected.name }) {
            courseFilter = CourseFilter.All
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 65.dp).padding(horizontal = ClassMemorySpacing.screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(30.dp).background(palette.primary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ClassMemoryIcons.Mic, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.onPrimary)
                }
                Text(
                    if (BuildConfig.ASR_COMPARISON && !supportsCourseHotwords) BuildConfig.ASR_MODEL_LABEL else tr("课堂记忆", "Class Memory"),
                    style = if (supportsCourseHotwords) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box {
                        IconButton(onClick = { debugMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(ClassMemoryIcons.More, contentDescription = if (supportsCourseHotwords) tr("更多", "More") else tr("更多", "More options"), tint = palette.muted)
                        }
                        DropdownMenu(expanded = debugMenuExpanded, onDismissRequest = { if (!appending) debugMenuExpanded = false }) {
                            if (showImport) {
                                DropdownMenuItem(text = { Text(tr("导入测试音频", "Import test audio")) }, enabled = !appending, onClick = {
                                    debugMenuExpanded = false
                                    if (!appending) onImport()
                                })
                            }
                            DropdownMenuItem(
                                text = { Text(if (AppLanguage.isEnglish) "切换 中文" else "Switch to English") },
                                onClick = { debugMenuExpanded = false; AppLanguage.toggle(context) },
                            )
                        }
                }
                if (supportsCourseHotwords) {
                    TextButton(
                        onClick = {
                            if (hotwordUiState.enabled) AsrHotwordPreferences.setDataStructureEnabled(context, false)
                            else if (!AsrHotwordPreferences.setDataStructureEnabled(context, true)) onManageHotwords()
                        },
                        modifier = Modifier.widthIn(max = 112.dp).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp),
                    ) {
                        Text(
                            tr("热词 ${if (hotwordUiState.enabled) "开" else "关"}", "Hotwords ${if (hotwordUiState.enabled) "On" else "Off"}"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = if (AppLanguage.isEnglish) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                TextButton(onClick = onSettings, modifier = Modifier.widthIn(min = 48.dp, max = 72.dp).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(tr("设置", "Settings"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = ClassMemorySpacing.screen, end = ClassMemorySpacing.screen, top = 4.dp, bottom = 25.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().background(palette.surfaceAlt, RoundedCornerShape(24.dp)).padding(20.dp),
                ) {
                    Text(tr("本地课堂记录", "Local Class Notes"), style = MaterialTheme.typography.labelMedium, color = palette.primary, fontWeight = FontWeight.Bold)
                    Text(
                        tr("把今天的课堂留下来", "Keep a record of today's class"), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (supportsCourseHotwords && hotwordUiState.enabled) {
                        Surface(
                            modifier = Modifier.padding(top = 7.dp, bottom = 17.dp).heightIn(min = 48.dp).clickable(onClick = { chooseHotwordCourse = true }),
                            color = palette.surface,
                            shape = RoundedCornerShape(100.dp),
                            border = BorderStroke(1.dp, palette.border),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("热词词库", "Hotword library"), style = MaterialTheme.typography.labelMedium, color = palette.primary)
                                Spacer(Modifier.width(9.dp))
                                Text(hotwordUiState.selectedCourse.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(tr("更改", "Change"), style = MaterialTheme.typography.labelMedium, color = palette.muted)
                            }
                        }
                    } else {
                        Text(
                            tr("语音只在手机本地识别。只保存最终文字和你主动拍摄的板书，不保存课堂录音文件。", "Speech is recognized on this device. Only final text and board photos you take are saved; class audio is not stored."),
                            style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                            modifier = Modifier.padding(top = 7.dp, bottom = 17.dp),
                        )
                    }
                    Button(
                        onClick = { if (!appending) onStart() },
                        enabled = !appending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(17.dp),
                        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                    ) {
                        Icon(ClassMemoryIcons.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(tr("开始记录", "Start recording"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("历史课堂", "Past classes"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Box {
                        FilterChip(
                            selected = courseFilter !is CourseFilter.All,
                            onClick = { if (!appending) filterMenuExpanded = true },
                            label = {
                                Text(
                                    tr("${filteredSessions.size} 堂 · ${courseFilter.label}", "${filteredSessions.size} · ${courseFilter.label}"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 150.dp),
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = tr("按课程类型筛选历史课堂", "Filter past classes by course type") },
                        )
                        DropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 210.dp),
                        ) {
                            CourseFilterMenuItem(
                                label = tr("全部课程", "All courses"),
                                count = sessions.size,
                                selected = courseFilter is CourseFilter.All,
                                onClick = { courseFilter = CourseFilter.All; filterMenuExpanded = false },
                            )
                            courseTypes.forEach { type ->
                                CourseFilterMenuItem(
                                    label = displayCourseType(type.name),
                                    count = sessions.count { it.courseType == type.name },
                                    selected = courseFilter == CourseFilter.Tagged(type.name),
                                    onClick = { courseFilter = CourseFilter.Tagged(type.name); filterMenuExpanded = false },
                                )
                            }
                            val unclassifiedCount = sessions.count { it.courseType == null }
                            if (unclassifiedCount > 0) {
                                CourseFilterMenuItem(
                                    label = tr("未分类", "Uncategorized"),
                                    count = unclassifiedCount,
                                    selected = courseFilter is CourseFilter.Unclassified,
                                    onClick = { courseFilter = CourseFilter.Unclassified; filterMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
            if (sessions.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(), color = palette.surface,
                        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, palette.border),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.size(76.dp).background(palette.surfaceAlt, RoundedCornerShape(25.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(ClassMemoryIcons.File, contentDescription = null, modifier = Modifier.size(36.dp), tint = palette.primary) }
                            Text(tr("还没有课堂记录", "No classes yet"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 17.dp))
                            Text(
                                tr("开始第一次记录后，课堂文字和板书会按时间保存在这里。", "After your first recording, class notes and board photos will appear here in time order."),
                                style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                                modifier = Modifier.padding(top = 9.dp),
                            )
                            Text(
                                tr("从上方“开始记录”进入", "Tap “Start recording” above to begin"), style = MaterialTheme.typography.labelMedium,
                                color = palette.primary, modifier = Modifier.padding(top = 15.dp),
                            )
                        }
                    }
                }
            } else if (filteredSessions.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(), color = palette.surface,
                        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, palette.border),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(tr("这个类别还没有课堂", "No classes in this category"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                tr("可以选择其他课程类型，或返回查看全部课堂。", "Choose another course type or return to view all classes."),
                                style = MaterialTheme.typography.bodyMedium, color = palette.muted,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = { courseFilter = CourseFilter.All }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(tr("显示全部课堂", "Show all classes"))
                            }
                        }
                    }
                }
            } else {
                items(filteredSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onOpen = { if (!appending) onDetail(session.id) },
                        onEdit = { if (!appending) renameTarget = session },
                        onDelete = { if (!appending) deleteTarget = session },
                        onAppend = { if (!appending) { appendTargetId = session.id; appendSourceId = null; appendError = null } },
                        operationsEnabled = !appending,
                    )
                }
            }
        }
    }

    LaunchedEffect(chooseHotwordCourse) {
        if (chooseHotwordCourse) {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CustomHotwordLibraryPreferences.libraries(context) } }
                .onSuccess { pickerCustomCourses = it }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: tr("自定义词库读取失败", "Failed to load custom hotword libraries"), android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    if (chooseHotwordCourse) {
        AlertDialog(
            onDismissRequest = { chooseHotwordCourse = false },
            title = { Text(tr("选择课程热词", "Choose hotwords for this course")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(pickerCustomCourses, key = { "picker:${it.id}" }) { course ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            AsrHotwordPreferences.selectHotwordCourse(context, course.id)
                            chooseHotwordCourse = false
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(hotwordUiState.selectedCourse.id == course.id, onClick = {
                                AsrHotwordPreferences.selectHotwordCourse(context, course.id)
                                chooseHotwordCourse = false
                            })
                            Text(course.name, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (pickerCustomCourses.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(tr("还没有自定义词库。请先新建或导入词库，再返回这里选用。", "No custom libraries yet. Create or import one, then return here to select it."), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                chooseHotwordCourse = false
                                onManageHotwords()
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("新建或导入词库", "Create or import a library")) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseHotwordCourse = false }) { Text(tr("完成", "Done")) } },
        )
    }

    val currentAppendTarget = appendTargetId?.let { id -> sessions.firstOrNull { it.id == id } }
    val currentAppendSource = appendSourceId?.let { id -> sessions.firstOrNull { it.id == id } }
    if (appendTargetId != null && appendSourceId == null) {
        val target = currentAppendTarget
        SessionAppendDialog(
            target = target,
            candidates = target?.let { appendCandidates(sessions, it.id) }.orEmpty(),
            busy = appending,
            error = appendError,
            onDismiss = { if (!appending) { appendTargetId = null; appendSourceId = null; appendError = null } },
            onSelect = { appendSourceId = it; appendError = null },
        )
    }
    if (appendTargetId != null && appendSourceId != null) {
        val target = currentAppendTarget
        val source = currentAppendSource
        SessionAppendConfirmDialog(
            target = target,
            source = source,
            busy = appending,
            error = appendError,
            onDismiss = { if (!appending) { appendTargetId = null; appendSourceId = null; appendError = null } },
            onBack = { if (!appending) { appendSourceId = null; appendError = null } },
            onConfirm = {
                if (source == null || target == null || source.status == SessionStatus.recording || target.status == SessionStatus.recording) {
                    appendError = tr("课堂已不存在或正在记录，请返回重新选择", "This class no longer exists or is recording. Go back and choose another class.")
                } else if (!appending) {
                    appending = true
                    appendError = null
                    scope.launch {
                        val targetId = target.id
                        val sourceId = source.id
                        runCatching { repo.appendSession(targetId = targetId, sourceId = sourceId) }
                            .onSuccess {
                                appendSourceId = null
                                appendTargetId = null
                                appending = false
                                scope.launch {
                                    androidx.compose.runtime.withFrameNanos { }
                                    snackbar.showSnackbar(tr("已追加，原笔记仍保留", "Notes appended. The original notes were kept."))
                                }
                            }
                            .onFailure { appendError = it.message ?: tr("追加失败，请重试", "Failed to append notes. Try again.") }
                        appending = false
                    }
                }
            },
        )
    }

    renameTarget?.takeIf { !appending }?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title.orEmpty()) }
        var selectedType by remember(target.id) { mutableStateOf(target.courseType) }
        var typeMenuExpanded by remember(target.id) { mutableStateOf(false) }
        var saving by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) renameTarget = null },
            title = { Text(tr("编辑课堂信息", "Edit class details")) },
            text = {
                Column {
                    Text(tr("修改名称和类型，课堂文字与板书不会改变。", "Changing the name or type won't affect class notes or board photos."), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    OutlinedTextField(
                        value = draft, onValueChange = { if (it.length <= 60) draft = it },
                        label = { Text(tr("课程名称", "Class name")) }, supportingText = { Text(tr("最多 60 个字符", "Up to 60 characters")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
                        OutlinedTextField(
                            value = selectedType?.let(::displayCourseType) ?: tr("未分类", "Uncategorized"), onValueChange = {}, readOnly = true,
                            label = { Text(tr("课程类型", "Course type")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text(tr("未分类", "Uncategorized")) }, onClick = { selectedType = null; typeMenuExpanded = false })
                            courseTypes.forEach { type ->
                                DropdownMenuItem(text = { Text(displayCourseType(type.name)) }, onClick = { selectedType = type.name; typeMenuExpanded = false })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank() && !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching { repo.updateMetadata(target.id, draft, selectedType) }
                            .onSuccess { renameTarget = null }
                            .onFailure { snackbar.showSnackbar(it.message ?: tr("课堂信息保存失败", "Failed to save class details")) }
                        saving = false
                    }
                }) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }, enabled = !saving) { Text(tr("取消", "Cancel")) } },
        )
    }

    deleteTarget?.takeIf { !appending }?.let { target ->
        var deleting by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            icon = { Icon(ClassMemoryIcons.Trash, contentDescription = null, tint = palette.error) },
            title = { Text(tr("删除这堂课？", "Delete this class?")) },
            text = { Text(tr("“${target.title ?: "未命名课堂"}”的文字和板书照片都会永久删除，无法恢复。", "The notes and board photos for “${target.title ?: "Untitled class"}” will be permanently deleted.")) },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        runCatching { repo.delete(target.id) }
                            .onSuccess { deleteTarget = null }
                            .onFailure { snackbar.showSnackbar(it.message ?: tr("删除失败", "Delete failed")) }
                        deleting = false
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = palette.error)) {
                    Text(if (deleting) tr("删除中…", "Deleting…") else tr("删除课堂", "Delete class"))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !deleting) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun CourseFilterMenuItem(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("$count 堂", "$count"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = {
            if (selected) Icon(ClassMemoryIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}

internal sealed interface CourseFilter {
    val label: String

    data object All : CourseFilter { override val label: String get() = tr("全部", "All") }
    data object Unclassified : CourseFilter { override val label: String get() = tr("未分类", "Uncategorized") }
    data class Tagged(val name: String) : CourseFilter { override val label: String get() = displayCourseType(name) }
}

internal fun filterSessions(sessions: List<Session>, filter: CourseFilter): List<Session> = when (filter) {
    CourseFilter.All -> sessions
    CourseFilter.Unclassified -> sessions.filter { it.courseType == null }
    is CourseFilter.Tagged -> sessions.filter { it.courseType == filter.name }
}

internal fun appendCandidates(sessions: List<Session>, targetId: String): List<Session> =
    sessions.filter { it.id != targetId && it.status != SessionStatus.recording }

internal fun appendDirectionLabel(target: Session?, source: Session?): String = when {
    source == null || target == null -> tr("课堂已不存在或正在记录，无法追加。返回后重新选择。", "This class no longer exists or is recording, so notes cannot be appended. Go back and choose again.")
    source.id == target.id -> tr("不能把同一堂课追加到自身。返回后重新选择。", "A class cannot be appended to itself. Go back and choose another class.")
    source.status == SessionStatus.recording || target.status == SessionStatus.recording -> tr("课堂正在记录，无法追加。返回后重新选择。", "Notes cannot be appended while a class is recording. Go back and choose another class.")
    else -> tr("A：${appendSessionDisplayLabel(target)} → B：${appendSessionDisplayLabel(source)}", "A: ${appendSessionDisplayLabel(target)} → B: ${appendSessionDisplayLabel(source)}")
}

internal fun appendSessionDisplayLabel(session: Session): String =
        "${session.title ?: tr("未命名课堂", "Untitled class")} · ${session.courseType?.let(::displayCourseType) ?: tr("未分类", "Uncategorized")} · " +
        "${SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(Date(session.startedAt))} · ${formatDuration(session.durationSec)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(session: Session, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onAppend: () -> Unit, operationsEnabled: Boolean) {
    val palette = ClassMemoryColors.current
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    val (organizedColor, organizedBackground) = if (session.isOrganized) {
        palette.success to palette.successBackground
    } else {
        palette.muted to palette.surfaceAlt
    }
    Surface(
        modifier = Modifier.fillMaxWidth(), color = palette.surface,
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, palette.border),
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxWidth().clickable(enabled = operationsEnabled, onClick = onOpen).padding(15.dp).padding(end = 33.dp),
            ) {
                Text(
                    session.title ?: tr("未命名课堂", "Untitled class"), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusTag(session.courseType?.let(::displayCourseType) ?: tr("未分类", "Uncategorized"), palette.primary, palette.surfaceAlt)
                    StatusTag(organizedStatusLabel(session.isOrganized), organizedColor, organizedBackground)
                }
                Text(
                    remember(session.startedAt, AppLanguage.isEnglish) { SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(Date(session.startedAt)) },
                    style = MaterialTheme.typography.bodySmall, color = palette.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    tr("${formatDuration(session.durationSec)}   ·   ${session.finalCharCount} 字", "${formatDuration(session.durationSec)}   ·   ${session.finalCharCount} characters"),
                    style = MaterialTheme.typography.bodySmall, color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(
                    onClick = { if (operationsEnabled) menuExpanded = true },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = tr("${session.title ?: "未命名课堂"}的更多操作", "More actions for ${session.title ?: "Untitled class"}") },
                ) { Icon(ClassMemoryIcons.More, contentDescription = null, tint = palette.muted) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { if (operationsEnabled) menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("编辑信息", "Edit details")) }, leadingIcon = { Icon(ClassMemoryIcons.Edit, contentDescription = null) },
                        enabled = operationsEnabled,
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    if (session.status != SessionStatus.recording) {
                        DropdownMenuItem(
                            text = { Text(tr("追加其他笔记", "Append notes from another class")) },
                            enabled = operationsEnabled,
                            onClick = { menuExpanded = false; onAppend() },
                        )
                    }
                    if (session.status != SessionStatus.recording) {
                        DropdownMenuItem(
                            text = { Text(tr("删除课堂", "Delete class"), color = palette.error) },
                            leadingIcon = { Icon(ClassMemoryIcons.Trash, contentDescription = null, tint = palette.error) },
                            enabled = operationsEnabled,
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(label: String, textColor: Color, backgroundColor: Color) {
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = textColor,
        modifier = Modifier.background(backgroundColor, RoundedCornerShape(100.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

internal fun organizedStatusLabel(isOrganized: Boolean) = if (isOrganized) tr("已整理", "Organized") else tr("未整理", "Not organized")

fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
