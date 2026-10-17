package com.classmemory.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.CustomHotwordDraft
import com.classmemory.app.asr.CustomHotwordLibrary
import com.classmemory.app.asr.CustomHotwordLibraryCodec
import com.classmemory.app.asr.CustomHotwordLibraryPreferences
import com.classmemory.app.asr.CustomHotwordSeed
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.theme.ClassMemoryColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HotwordCourseManager(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = ClassMemoryColors.current
    val hotwordUiState = rememberHotwordUiState()
    var libraries by remember { mutableStateOf<List<CustomHotwordLibrary>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var seedMessage by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftTerms by rememberSaveable { mutableStateOf("") }
    var importName by rememberSaveable { mutableStateOf<String?>(null) }
    var importTerms by rememberSaveable { mutableStateOf("") }
    var pendingExportJson by rememberSaveable { mutableStateOf<String?>(null) }

    fun refreshLibraries() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CustomHotwordLibraryPreferences.libraries(context) } }
                .onSuccess { libraries = it; loadError = null }
                .onFailure { loadError = it.message ?: tr("自定义词库读取失败", "Failed to load custom hotword libraries") }
            seedMessage = withContext(Dispatchers.IO) { CustomHotwordSeed.statusMessage(context) }
        }
    }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("asr_hotword_settings", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "custom_libraries_v1") {
                refreshLibraries()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        refreshLibraries()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                            ?: error(tr("无法创建导出文件", "Couldn't create the export file"))
                    }
                }.onFailure { Toast.makeText(context, it.message ?: tr("导出失败", "Export failed"), Toast.LENGTH_LONG).show() }
                pendingExportJson = null
            }
        } else if (uri == null) {
            pendingExportJson = null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val displayName = queryDisplayName(context, uri)
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input -> CustomHotwordLibraryCodec.readBounded(input) }
                            ?: error(tr("无法读取所选文件", "Couldn't read the selected file"))
                        CustomHotwordLibraryCodec.decode(bytes, displayName.substringBeforeLast('.', displayName))
                    }
                }.onSuccess { draft ->
                    importName = draft.name
                    importTerms = draft.terms.joinToString("\n")
                }.onFailure { Toast.makeText(context, it.message ?: tr("导入失败", "Import failed"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun startEditing(name: String, terms: List<String>, id: String?) {
        draftName = name
        draftTerms = terms.joinToString("\n")
        editingId = id
        editing = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp), color = palette.background,
            border = BorderStroke(1.dp, palette.border),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("编辑课程热词", "Manage course hotwords"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("完成", "Done")) }
                }
                Text(tr("新建、导入和编辑不会自动启用或切换当前词库。", "Creating, importing, or editing won't automatically enable or switch the active library."), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                seedMessage?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { startEditing(tr("自定义课程", "Custom course"), emptyList(), null) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr("新建", "New")) }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr("导入 JSON/TXT", "Import JSON/TXT"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                loadError?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item { Text(tr("自定义课程", "Custom courses"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)) }
                    items(libraries, key = { it.id }) { library ->
                        CourseManagerRow(
                            title = library.name,
                            subtitle = if (hotwordUiState.selectedCourse.id == library.id) tr("自定义 · 当前选择 · ${library.terms.size} 条", "Custom · Selected · ${library.terms.size} terms") else tr("自定义 · ${library.terms.size} 条", "Custom · ${library.terms.size} terms"),
                            actions = listOf(
                                tr("选用", "Select") to { AsrHotwordPreferences.selectHotwordCourse(context, library.id); onDismiss() },
                                tr("编辑", "Edit") to { startEditing(library.name, library.terms, library.id) },
                                tr("导出", "Export") to {
                                    runCatching {
                                        pendingExportJson = CustomHotwordLibraryCodec.encode(CustomHotwordDraft(library.name, library.terms)).toString(Charsets.UTF_8)
                                        exportLauncher.launch("${safeExportName(library.name)}.json")
                                    }.onFailure { Toast.makeText(context, it.message ?: tr("导出失败", "Export failed"), Toast.LENGTH_LONG).show() }
                                },
                            ),
                        )
                    }
                    if (libraries.isEmpty() && loadError == null) item {
                        Text(tr("还没有自定义词库。新建或导入后，返回课程热词开关处选择并启用。", "No custom libraries yet. Create or import one, then return to course hotwords to select and enable it."), color = palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (editingId == null) tr("新建自定义词库", "Create custom library") else tr("编辑自定义词库", "Edit custom library")) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(draftName, { draftName = it }, label = { Text(tr("课程名称", "Course name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        draftTerms, { draftTerms = it }, label = { Text(tr("词条（每行一个，最多 200 条）", "Terms (one per line, up to 200)")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 8,
                    )
                    Text(tr("保存会校验 X-ASR BPE 词表，不会自动选择或启用。", "Saving validates terms against the X-ASR BPE vocabulary; it won't select or enable the library."), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = editingId
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val terms = draftTerms.split(Regex("\\r?\\n"))
                                val draft = CustomHotwordDraft(draftName, terms)
                                if (id == null) CustomHotwordLibraryPreferences.create(context, draft.name, draft.terms)
                                else CustomHotwordLibraryPreferences.save(context, CustomHotwordLibrary(id, draft.name, draft.terms))
                            }
                        }.onSuccess { editing = false; refreshLibraries() }
                            .onFailure { Toast.makeText(context, it.message ?: tr("保存失败", "Save failed"), Toast.LENGTH_LONG).show() }
                    }
                }) { Text(tr("保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(tr("取消", "Cancel")) } },
        )
    }

    importName?.let { initialName ->
        AlertDialog(
            onDismissRequest = { importName = null; importTerms = "" },
            title = { Text(tr("确认导入词库", "Review imported library")) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(initialName, { importName = it }, label = { Text(tr("课程名称", "Course name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        importTerms,
                        { importTerms = it },
                        label = { Text(tr("预览并编辑词条（每行一个）", "Review and edit terms (one per line)")) },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    )
                    Text(tr("当前 ${importTerms.lineSequence().count()} 条；保存时会执行格式、重复项和模型词表校验。", "${importTerms.lineSequence().count()} terms. Saving checks formatting, duplicates, and the model vocabulary."), style = MaterialTheme.typography.bodySmall)
                    Text(tr("确认后保存为自定义词库，不会自动选择或启用。", "This will save a custom library without selecting or enabling it."), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = importName.orEmpty()
                    val terms = importTerms.split('\n')
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { CustomHotwordLibraryPreferences.create(context, name, terms) }
                        }.onSuccess { importName = null; importTerms = ""; refreshLibraries() }
                            .onFailure { Toast.makeText(context, it.message ?: tr("导入失败", "Import failed"), Toast.LENGTH_LONG).show() }
                    }
                }) { Text(tr("保存词库", "Save library")) }
            },
            dismissButton = { TextButton(onClick = { importName = null; importTerms = "" }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun CourseManagerRow(title: String, subtitle: String, actions: List<Pair<String, () -> Unit>>) {
    val palette = ClassMemoryColors.current
    Surface(color = palette.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, palette.border)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 8.dp, bottom = 4.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                actions.forEach { (label, action) ->
                    TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 7.dp)) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: tr("导入课程", "Imported course")
}

private fun safeExportName(name: String): String = name
    .replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]"), "_")
    .trim().take(60).ifBlank { tr("自定义词库", "custom-library") }
