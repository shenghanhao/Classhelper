package com.classmemory.app.benchmark

import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.classmemory.app.BuildConfig
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.theme.ClassMemoryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug APK's visible and automation-safe entry point. It accepts only a caller supplied
 * content URI plus a bounded mode/target, obtains a read grant, and never exposes the service.
 */
class DebugBenchmarkActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN = "com.classmemory.app.benchmark.OPEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClassMemoryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    BenchmarkScreen()
                }
            }
        }
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
    }

    private fun handleAutomationIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN) return
        val source = intent.data ?: intent.getStringExtra(DebugBenchmarkService.EXTRA_URI)?.toUri()
        val mode = intent.getStringExtra(DebugBenchmarkService.EXTRA_MODE)
            ?.let { runCatching { DebugBenchmarkMode.valueOf(it) }.getOrNull() }
            ?: DebugBenchmarkMode.FAST
        val minutes = intent.getIntExtra(DebugBenchmarkService.EXTRA_TARGET_MINUTES, 10)
        if (source?.scheme != "content" ||
            (mode == DebugBenchmarkMode.REALTIME && minutes !in DebugBenchmarkService.allowedTargetMinutes)
        ) {
            Toast.makeText(this, tr("自动化入口需要 content:// URI 和有效模式/时长", "Automation entry requires a content:// URI and valid mode/duration"), Toast.LENGTH_LONG).show()
            return
        }
        startWithGrant(source, mode, minutes)
    }

    private fun startWithGrant(uri: Uri, mode: DebugBenchmarkMode, minutes: Int) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { DebugBenchmarkService.start(this, uri, mode, minutes) }
            .onFailure { Toast.makeText(this, tr("无法启动 Benchmark：${it.message ?: "系统拒绝"}", "Could not start benchmark: ${it.message ?: "System denied the request"}"), Toast.LENGTH_LONG).show() }
    }

    @androidx.compose.runtime.Composable
    private fun BenchmarkScreen() {
        var uri by remember { mutableStateOf<Uri?>(null) }
        var mode by remember { mutableStateOf(DebugBenchmarkMode.FAST) }
        var targetIndex by remember { mutableIntStateOf(0) }
        val targetOptions = remember { DebugBenchmarkService.allowedTargetMinutes.sorted() }
        val state by DebugBenchmarkService.state.collectAsState()
        val currentCourse = AsrHotwordPreferences.selectedHotwordCourse(this, tolerateUnreadableCustom = true)
        val currentHotwordsEnabled = AsrHotwordPreferences.isDataStructureEnabled(this)
        val showingCurrentSession = state.running
        val actualCourseName = if (showingCurrentSession) state.courseName ?: currentCourse.name else currentCourse.name
        val actualHotwordsEnabled = if (showingCurrentSession) state.hotwordsEnabled ?: currentHotwordsEnabled else currentHotwordsEnabled
        val modelLabel = if (BuildConfig.ASR_HOTWORD_PROFILE == "data_structure") {
            if (actualHotwordsEnabled) tr("X-ASR ${actualCourseName}热词", "X-ASR $actualCourseName hotwords") else "X-ASR"
        } else BuildConfig.ASR_MODEL_LABEL
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) {
                uri = chosen
                runCatching {
                    contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        MaterialTheme {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("导入音频识别", "Import audio for recognition"), style = MaterialTheme.typography.headlineSmall)
                Text(tr("模型：$modelLabel", "Model: $modelLabel"))
                if (BuildConfig.ASR_HOTWORD_PROFILE == "data_structure") {
                    val timing = if (showingCurrentSession) tr("本次识别", "this run") else tr("下次识别", "next run")
                    Text(tr("课程热词：$actualCourseName · ${if (actualHotwordsEnabled) "开启" else "关闭"}（$timing）", "Course hotwords: $actualCourseName · ${if (actualHotwordsEnabled) "On" else "Off"} ($timing)"))
                }
                if (BuildConfig.ASR_MODEL_ID == "qwen3") Text(tr("按语音短段出字，不是逐字实时出字。", "Text appears in short speech segments, not character by character."))
                Text(tr("选择 WAV、M4A 等音频后，本机完成识别并保存为课堂记录；输入音频不会被复制或长期保存。", "Choose WAV, M4A, or another audio file to transcribe it on this device and save it as a class record. The input audio is not copied or retained."))
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !state.running) {
                    Text(if (uri == null) tr("选择音频文件", "Choose audio file") else tr("已选择：${uri?.lastPathSegment ?: "音频文件"}", "Selected: ${uri?.lastPathSegment ?: "Audio file"}"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mode = DebugBenchmarkMode.FAST }, enabled = !state.running) {
                        Text(if (mode == DebugBenchmarkMode.FAST) tr("✓ 普通识别", "✓ Standard") else tr("普通识别", "Standard"))
                    }
                    OutlinedButton(onClick = { mode = DebugBenchmarkMode.REALTIME }, enabled = !state.running) {
                        Text(if (mode == DebugBenchmarkMode.REALTIME) tr("✓ 压力测试", "✓ Stress test") else tr("压力测试", "Stress test"))
                    }
                }
                if (mode == DebugBenchmarkMode.REALTIME) {
                    OutlinedButton(
                        onClick = { targetIndex = (targetIndex + 1) % targetOptions.size },
                        enabled = !state.running,
                    ) { Text(tr("目标：${targetOptions[targetIndex]} 分钟（点击切换）", "Target: ${targetOptions[targetIndex]} minutes (tap to change)")) }
                }
                Button(
                    enabled = uri != null && !state.running,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { startWithGrant(uri ?: return@Button, mode, targetOptions[targetIndex]) },
                ) { Text(tr("开始本地识别", "Start local recognition")) }
                if (state.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { DebugBenchmarkService.stop(this@DebugBenchmarkActivity) }) { Text(tr("正常停止", "Stop normally")) }
                        OutlinedButton(onClick = { DebugBenchmarkService.cancel(this@DebugBenchmarkActivity) }) { Text(tr("取消", "Cancel")) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(tr("状态：${state.status}", "Status: ${state.status}"))
                Text(tr("已输入：${"%.1f".format(state.inputSeconds)} 秒；最终文字：${state.finalCharacters} 字", "Input: ${"%.1f".format(state.inputSeconds)} s; final characters: ${state.finalCharacters}"))
                state.error?.let { Text(tr("提示：$it", "Notice: $it"), color = MaterialTheme.colorScheme.error) }
                state.textReportPath?.let { Text(tr("文本报告：$it", "Text report: $it"), style = MaterialTheme.typography.bodySmall) }
                state.jsonReportPath?.let { Text(tr("JSON 报告：$it", "JSON report: $it"), style = MaterialTheme.typography.bodySmall) }
                if (!state.running && state.textReportPath != null && state.jsonReportPath != null) {
                    OutlinedButton(onClick = { shareReports(state) }) { Text(tr("分享本次测试报告（文字 + 指标）", "Share this test report (transcript + metrics)")) }
                }
            }
        }
    }

    private fun shareReports(state: DebugBenchmarkUiState) {
        lifecycleScope.launch {
            runCatching {
                val uris = withContext(Dispatchers.IO) {
                    val directory = File(cacheDir, "benchmark-exports").apply { mkdirs() }
                    listOfNotNull(state.textReportPath, state.jsonReportPath).map { path ->
                        val source = File(path)
                        require(source.isFile && source.canonicalFile.parentFile == File(filesDir, "benchmark-reports").canonicalFile)
                        val target = File(directory, "${BuildConfig.ASR_MODEL_ID}-${source.name}")
                        source.copyTo(target, overwrite = true)
                        FileProvider.getUriForFile(this@DebugBenchmarkActivity, "$packageName.fileprovider", target)
                    }
                }
                check(uris.size == 2)
                val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    clipData = ClipData.newRawUri(tr("ASR 测试报告", "ASR test report"), uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, tr("分享 ${BuildConfig.ASR_MODEL_LABEL} 测试报告", "Share ${BuildConfig.ASR_MODEL_LABEL} test report")))
            }.onFailure {
                Toast.makeText(this@DebugBenchmarkActivity, tr("无法分享测试报告", "Could not share test report"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
