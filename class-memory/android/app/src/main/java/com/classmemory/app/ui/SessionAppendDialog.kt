package com.classmemory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.displayCourseType
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.theme.ClassMemoryColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionAppendDialog(
    target: Session?,
    candidates: List<Session>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val palette = ClassMemoryColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("追加其他笔记", "Append notes from another class")) },
        text = {
            Column {
                Text(tr("选择要接在“${target?.title ?: "未命名课堂"}”后面的课堂。可跨课程类型选择。", "Choose a class to append after “${target?.title ?: "Untitled class"}”. Course types may differ."), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                if (target == null || target.status == SessionStatus.recording) {
                    Text(tr("课堂已不存在或正在记录，无法追加。", "This class no longer exists or is recording, so notes cannot be appended."), modifier = Modifier.padding(top = 12.dp), color = palette.error)
                } else if (candidates.isEmpty()) {
                    Text(tr("没有可追加的其他课堂。", "No other classes are available to append."), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                        items(candidates, key = { it.id }) { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp).clickable(enabled = !busy) { onSelect(item.id) },
                                color = palette.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, palette.border),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(item.title ?: tr("未命名课堂", "Untitled class"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${item.courseType?.let(::displayCourseType) ?: tr("未分类", "Uncategorized")} · ${dateLabel(item.startedAt)} · ${formatDuration(item.durationSec)}",
                                        modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = palette.muted,
                                    )
                                }
                            }
                        }
                    }
                }
                if (error != null) Text(error, modifier = Modifier.padding(top = 10.dp), color = palette.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
internal fun SessionAppendConfirmDialog(
    target: Session?,
    source: Session?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = ClassMemoryColors.current
    val valid = source != null && target != null && source.id != target.id && source.status != SessionStatus.recording && target.status != SessionStatus.recording
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("确认追加笔记", "Confirm note append")) },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(appendDirectionLabel(target, source), style = MaterialTheme.typography.titleSmall)
                Text(tr("将 B 的笔记内容写入 A；B 原笔记保持不变、不删除。名称和课程类型沿用 A，时长相加；合并后标记为未整理。", "Notes from B will be copied into A; B remains unchanged. A keeps its name and course type, durations are added, and the result is marked unorganized."), modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                if (error != null) Text(error, modifier = Modifier.padding(top = 10.dp), color = palette.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid && !busy) { Text(if (busy) tr("追加中…", "Appending…") else tr("确认追加", "Append notes")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBack, enabled = !busy) { Text(tr("返回选择", "Back to selection")) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("取消", "Cancel")) }
            }
        },
    )
}

private fun dateLabel(timestamp: Long): String = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(Date(timestamp))
