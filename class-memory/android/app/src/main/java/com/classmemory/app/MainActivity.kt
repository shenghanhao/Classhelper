package com.classmemory.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.classmemory.app.service.RecordingService
import com.classmemory.app.i18n.tr
import com.classmemory.app.ui.*
import com.classmemory.app.ui.theme.ClassMemoryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
          ClassMemoryTheme {
            var page by rememberSaveable(stateSaver = screenSaver) { mutableStateOf<Screen>(Screen.Home) }
            var openHotwordManager by rememberSaveable { mutableStateOf(false) }
            var permissionMessage by remember { mutableStateOf<String?>(null) }
            var startupError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (RecordingService.state.value.sessionId != null) {
                    page = Screen.Recording
                } else runCatching {
                    (application as ClassMemoryApp).repository.recoverAbandonedSessions()
                }.onFailure { startupError = tr("无法恢复上次课堂记录：${it.message ?: "数据库初始化失败"}", "Couldn't restore the previous class: ${it.message ?: "Database initialization failed"}") }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                if (result[Manifest.permission.RECORD_AUDIO] == true) {
                    permissionMessage = null
                    runCatching { RecordingService.start(this); page = Screen.Recording }
                        .onFailure { permissionMessage = tr("无法启动课堂记录服务：${it.message ?: "系统拒绝启动"}", "Couldn't start the recording service: ${it.message ?: "The system refused to start it"}") }
                } else permissionMessage = tr("未授予麦克风权限，无法开始记录。请在系统设置中允许麦克风。", "Microphone permission is required to start recording. Allow it in system settings.")
            }
            LaunchedEffect(permissionMessage) {
                permissionMessage?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            LaunchedEffect(startupError) {
                startupError?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            BackHandler(enabled = page is Screen.Detail || page is Screen.Settings) { page = Screen.Home }
            when (val current = page) {
                Screen.Home -> HomeScreen(
                    repo = (application as ClassMemoryApp).repository,
                    onStart = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
                        } else runCatching { RecordingService.start(this); page = Screen.Recording }
                            .onFailure { permissionMessage = tr("无法启动课堂记录服务：${it.message ?: "系统拒绝启动"}", "Couldn't start the recording service: ${it.message ?: "The system refused to start it"}") }
                    },
                    onImport = {
                        runCatching {
                            startActivity(
                                Intent().setClassName(
                                    this,
                                    "com.classmemory.app.benchmark.DebugBenchmarkActivity",
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(this, tr("当前安装包不支持导入音频", "Audio import is not supported in this build"), Toast.LENGTH_LONG).show()
                        }
                    },
                    showImport = BuildConfig.DEBUG,
                    onDetail = { page = Screen.Detail(it) },
                    onSettings = { page = Screen.Settings },
                    onManageHotwords = { openHotwordManager = true; page = Screen.Settings },
                )
                Screen.Recording -> RecordingScreen(
                    repo = (application as ClassMemoryApp).repository,
                    onStop = { RecordingService.stop(this) },
                    onFinished = { page = Screen.Home },
                    onBoardPhotoCaptured = { sessionId, capturedAtMs, relativePath ->
                        (application as ClassMemoryApp).repository.addBoardPhoto(sessionId, capturedAtMs, relativePath)
                    },
                )
                is Screen.Detail -> SessionDetailScreen((application as ClassMemoryApp).repository, current.id) { page = Screen.Home }
                Screen.Settings -> SettingsScreen(
                    (application as ClassMemoryApp).repository,
                    onBack = { page = Screen.Home },
                    openHotwordManager = openHotwordManager,
                    onHotwordManagerOpened = { openHotwordManager = false },
                )
            }
          }
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Recording : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

private val screenSaver = Saver<Screen, String>(
    save = { screen -> when (screen) {
        Screen.Home -> "home"
        Screen.Recording -> "home"
        Screen.Settings -> "settings"
        is Screen.Detail -> "detail:${screen.id}"
    } },
    restore = { route -> when {
        route == "home" -> Screen.Home
        route == "recording" -> Screen.Home
        route == "settings" -> Screen.Settings
        route.startsWith("detail:") -> Screen.Detail(route.removePrefix("detail:"))
        else -> Screen.Home
    } },
)
