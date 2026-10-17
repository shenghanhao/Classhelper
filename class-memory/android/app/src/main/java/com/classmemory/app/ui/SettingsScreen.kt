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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.CourseType
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.i18n.tr
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.ui.icons.ClassMemoryIcons
import com.classmemory.app.ui.theme.ClassMemoryColors
import com.classmemory.app.ui.theme.ClassMemorySpacing
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repo: SessionRepository,
    onBack: () -> Unit,
    openHotwordManager: Boolean = false,
    onHotwordManagerOpened: () -> Unit = {},
) {
    val courseTypes by repo.courseTypes().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val supportsCourseHotwords = remember {
        AsrHotwordPreferences.supportsCourseHotwords()
    }
    val hotwordUiState = rememberHotwordUiState()
    val courseHotwordsEnabled = hotwordUiState.enabled
    val selectedHotwordCourse = hotwordUiState.selectedCourse
    var manageHotwords by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val palette = ClassMemoryColors.current
    var draft by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CourseType?>(null) }

    LaunchedEffect(openHotwordManager) {
        if (openHotwordManager) {
            manageHotwords = true
            onHotwordManagerOpened()
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 65.dp).padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(ClassMemoryIcons.Back, contentDescription = tr("返回", "Back"), tint = palette.primary)
                }
                Text(tr("设置", "Settings"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 1.dp))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = ClassMemorySpacing.screen, end = ClassMemorySpacing.screen, top = 6.dp, bottom = 25.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (supportsCourseHotwords) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(tr("课程热词", "Course hotwords"), style = MaterialTheme.typography.titleMedium)
                            Text(tr("下次录音或导入时应用所选课程词库。", "The selected course library will be used next time you record or import audio."), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                        Switch(
                            checked = courseHotwordsEnabled,
                            onCheckedChange = { enabled ->
                                if (!AsrHotwordPreferences.setDataStructureEnabled(context, enabled)) {
                                    manageHotwords = true
                                }
                            },
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = { manageHotwords = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(tr("编辑课程 · ${selectedHotwordCourse.name}", "Edit course · ${selectedHotwordCourse.name}"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item { Text(tr("添加课程类型", "Add course type"), style = MaterialTheme.typography.titleMedium) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft, onValueChange = { if (it.length <= 60) draft = it },
                        placeholder = { Text(tr("例如：普通化学", "e.g. General Chemistry"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp),
                    )
                    Button(
                        enabled = draft.isNotBlank() && !adding,
                        onClick = {
                            adding = true
                            scope.launch {
                                runCatching { repo.addCourseType(draft) }
                                    .onSuccess { draft = "" }
                                    .onFailure { snackbar.showSnackbar(it.message ?: tr("添加失败", "Couldn't add course type")) }
                                adding = false
                            }
                        },
                        modifier = Modifier.widthIn(min = 72.dp).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(if (adding) tr("添加中", "Adding…") else tr("添加", "Add")) }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("已有类型", "Existing types"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(tr("${courseTypes.size} 个", "${courseTypes.size}"), style = MaterialTheme.typography.labelMedium, color = palette.primary)
                }
            }
            if (courseTypes.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(), color = palette.surface,
                        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, palette.border),
                    ) {
                        Text(tr("还没有课程类型，添加后即可为课堂分类。", "No course types yet. Add one to categorize classes."), modifier = Modifier.padding(16.dp), color = palette.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(courseTypes, key = { it.name }) { type ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(), color = palette.surface,
                        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, palette.border),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(displayCourseType(type.name), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            TextButton(
                                onClick = { deleteTarget = type },
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = palette.error),
                            ) { Text(tr("删除", "Delete")) }
                        }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp).background(palette.surfaceAlt, RoundedCornerShape(17.dp)).padding(horizontal = 15.dp, vertical = 14.dp),
                ) {
                    Text(tr("删除课程类型不会删除课堂", "Deleting a course type won't delete classes"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("使用该类型的已有课堂会显示为“未分类”。", "Classes using this type will be shown as “Uncategorized”."),
                        style = MaterialTheme.typography.bodySmall, color = palette.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (manageHotwords) HotwordCourseManager { manageHotwords = false }

    deleteTarget?.let { target ->
        var deleting by remember(target.name) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            icon = { Icon(ClassMemoryIcons.Trash, contentDescription = null, tint = palette.error) },
            title = { Text(tr("删除“${target.name}”？", "Delete “${target.name}”?")) },
            text = { Text(tr("删除类型不会删除课堂；已有课堂会变为“未分类”。", "Deleting this type won't delete classes. Existing classes will become “Uncategorized”.")) },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        runCatching { repo.deleteCourseType(target.name) }
                            .onSuccess { deleteTarget = null }
                            .onFailure { snackbar.showSnackbar(it.message ?: tr("删除失败", "Delete failed")) }
                        deleting = false
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = palette.error)) {
                    Text(if (deleting) tr("删除中…", "Deleting…") else tr("删除类型", "Delete type"))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !deleting) { Text(tr("取消", "Cancel")) } },
        )
    }
}
