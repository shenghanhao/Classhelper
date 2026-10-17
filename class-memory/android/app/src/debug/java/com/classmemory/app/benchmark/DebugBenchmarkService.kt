package com.classmemory.app.benchmark

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.classmemory.app.ClassMemoryApp
import com.classmemory.app.BuildConfig
import com.classmemory.app.asr.LocalAsrEvent
import com.classmemory.app.asr.LocalAsrSession
import com.classmemory.app.asr.AsrHotwordSettings
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.AsrHotwordSessionSnapshot
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.data.audioDurationSec
import com.classmemory.app.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.math.min

/** Debug-only modes. FAST decodes one complete input; REALTIME loops a short fixture to the target. */
enum class DebugBenchmarkMode { FAST, REALTIME }

data class DebugBenchmarkUiState(
    val sessionId: String? = null,
    val mode: DebugBenchmarkMode? = null,
    val targetMinutes: Int? = null,
    val inputSeconds: Double = 0.0,
    val finalCharacters: Int = 0,
    val status: String = tr("空闲", "Idle"),
    val running: Boolean = false,
    val error: String? = null,
    val textReportPath: String? = null,
    val jsonReportPath: String? = null,
    val courseId: String? = null,
    val courseName: String? = null,
    val hotwordsEnabled: Boolean? = null,
)

/**
 * The complete Debug file-import owner.  It is deliberately not exported: only the debug
 * activity accepts an automation intent, persists a user-selected URI grant, then starts this
 * foreground service. Audio exists only in the decoder and this bounded eight-frame queue.
 */
class DebugBenchmarkService : Service() {
    companion object {
        const val ACTION_START = "com.classmemory.app.benchmark.START"
        const val ACTION_STOP = "com.classmemory.app.benchmark.STOP"
        const val ACTION_CANCEL = "com.classmemory.app.benchmark.CANCEL"
        const val EXTRA_URI = "benchmark_uri"
        const val EXTRA_MODE = "benchmark_mode"
        const val EXTRA_TARGET_MINUTES = "benchmark_target_minutes"
        const val MAX_TARGET_MINUTES = 90
        val allowedTargetMinutes = setOf(10, 30, 60, 90)

        private const val CHANNEL_ID = "debug_benchmark"
        private const val NOTIFICATION_ID = 18
        private const val QUEUE_FRAMES = 8 // 0.8 seconds at the decoder's 100 ms frame size.
        private const val MEMORY_SAMPLE_MILLIS = 5_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val REPORT_DIRECTORY = "benchmark-reports"

        private val _state = MutableStateFlow(DebugBenchmarkUiState())
        val state = _state.asStateFlow()

        fun start(context: Context, uri: Uri, mode: DebugBenchmarkMode, targetMinutes: Int) {
            require(mode == DebugBenchmarkMode.FAST || targetMinutes in allowedTargetMinutes)
            val intent = Intent(context, DebugBenchmarkService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TARGET_MINUTES, targetMinutes)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) = context.startService(
            Intent(context, DebugBenchmarkService::class.java).setAction(ACTION_STOP),
        )

        fun cancel(context: Context) = context.startService(
            Intent(context, DebugBenchmarkService::class.java).setAction(ACTION_CANCEL),
        )
    }

    private data class PcmFrame(val samples: ShortArray, val count: Int, val startSample: Long)

    private enum class FinishReason(val outcome: String, val sessionStatus: SessionStatus) {
        SUCCESS("completed", SessionStatus.completed),
        STOPPED("stopped", SessionStatus.completed),
        CANCELLED("cancelled", SessionStatus.failed),
        FAILED("failed", SessionStatus.failed),
    }

    /** Stops the current decoder pass after the target's final partial frame was queued. */
    private class TargetReached : CancellationException()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val acceptingFinals = AtomicBoolean(false)
    private val completedSamples = AtomicLong(0)
    private val queuedSamples = AtomicLong(0)
    private val pacingNanos = AtomicLong(0)
    private val finalCharacters = AtomicInteger(0)
    private val queuedFrames = AtomicInteger(0)
    private val transcript = StringBuilder()

    private var runJob: Job? = null
    private var consumerJob: Job? = null
    private var memoryJob: Job? = null
    private var frames: Channel<PcmFrame>? = null
    private var recognizer: LocalAsrSession? = null
    private var hotwordSettingsSnapshot = AsrHotwordSessionSnapshot(
        enabled = false, courseId = "", courseName = "", settings = AsrHotwordSettings.forProfile("none"),
    )
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId = ""
    private var startedAtMillis = 0L
    private var pipelineStartedNanos = 0L
    private var audioClockNanos = 0L
    private var initializationNanos = 0L
    private var targetSamples = 0L
    private lateinit var sourceUri: Uri
    private lateinit var mode: DebugBenchmarkMode
    private val metrics = BenchmarkMetrics(LocalAsrSession.SAMPLE_RATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running.get()) begin(intent) else {
                _state.value = _state.value.copy(error = tr("已有 Benchmark 正在运行", "A benchmark is already running"))
            }
            ACTION_STOP -> requestFinish(FinishReason.STOPPED, tr("用户停止", "Stopped by user"))
            ACTION_CANCEL -> requestFinish(FinishReason.CANCELLED, tr("用户取消", "Cancelled by user"))
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun begin(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        val selectedMode = intent.getStringExtra(EXTRA_MODE)
            ?.let { runCatching { DebugBenchmarkMode.valueOf(it) }.getOrNull() }
        val minutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, 10)
        if (uri == null || selectedMode == null ||
            (selectedMode == DebugBenchmarkMode.REALTIME && minutes !in allowedTargetMinutes)
        ) {
            _state.value = DebugBenchmarkUiState(status = tr("无法启动", "Cannot start"), error = tr("Debug Benchmark 参数无效", "Invalid debug benchmark parameters"))
            stopSelf()
            return
        }

        val hotwordSnapshot = runCatching { AsrHotwordPreferences.snapshot(this) }.getOrElse {
            _state.value = DebugBenchmarkUiState(status = tr("配置不可用", "Settings unavailable"), error = it.message ?: tr("请检查课程热词配置", "Check course hotword settings"))
            stopSelf()
            return
        }
        hotwordSettingsSnapshot = hotwordSnapshot
        running.set(true)
        finishing.set(false)
        acceptingFinals.set(false)
        completedSamples.set(0)
        queuedSamples.set(0)
        pacingNanos.set(0)
        finalCharacters.set(0)
        queuedFrames.set(0)
        transcript.setLength(0)
        sourceUri = uri
        mode = selectedMode
        targetSamples = if (mode == DebugBenchmarkMode.REALTIME) {
            minutes.toLong() * 60L * LocalAsrSession.SAMPLE_RATE
        } else 0L
        sessionId = UUID.randomUUID().toString()
        startedAtMillis = System.currentTimeMillis()
        pipelineStartedNanos = SystemClock.elapsedRealtimeNanos()
        _state.value = DebugBenchmarkUiState(
            sessionId = sessionId,
            mode = mode,
            targetMinutes = if (mode == DebugBenchmarkMode.REALTIME) minutes else null,
            status = tr("正在准备本地识别", "Preparing local recognition"),
            running = true,
            courseId = hotwordSettingsSnapshot.courseId,
            courseName = hotwordSettingsSnapshot.courseName,
            hotwordsEnabled = hotwordSettingsSnapshot.enabled,
        )
        createChannel()
        startForeground(NOTIFICATION_ID, notification(tr("正在准备 Debug 音频 Benchmark", "Preparing debug audio benchmark")))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClassMemory:debug-benchmark")
            .apply { setReferenceCounted(false); acquire() }
        runJob = scope.launch { runBenchmark() }
    }

    private suspend fun runBenchmark() {
        try {
            val repository = (application as ClassMemoryApp).repository
            repository.begin(sessionId, startedAtMillis)
            if (BuildConfig.ASR_COMPARISON) repository.updateMetadata(sessionId, tr("${actualModelLabel()} 音频测试", "${actualModelLabel()} audio test"), null)
            recognizer = LocalAsrSession.create(this, hotwordSettingsSnapshot) { event -> storeFinal(repository, event) }
            acceptingFinals.set(true)
            audioClockNanos = SystemClock.elapsedRealtimeNanos()
            initializationNanos = audioClockNanos - pipelineStartedNanos
            startMemorySampling()
            val queue = Channel<PcmFrame>(QUEUE_FRAMES)
            frames = queue
            consumerJob = scope.launch { consume(queue) }
            produce(queue)
            queue.close()
            consumerJob?.join()
            recognizer?.finish()
            recognizer?.snapshot()?.let { metrics.asrDuration(it.asrNanos) }
            requestFinish(FinishReason.SUCCESS)
        } catch (_: CancellationException) {
            // The finish coordinator owns cancellation outcome and cleanup.
        } catch (_: Throwable) {
            metrics.error()
            requestFinish(FinishReason.FAILED, tr("解码、识别或落库失败", "Audio decoding, recognition, or saving failed"))
        } finally {
            frames?.close()
        }
    }

    private suspend fun produce(queue: Channel<PcmFrame>) {
        val decoder = DebugAudioDecoder(this)
        var emitted = 0L
        var decodedAnySamples = false
        do {
            try {
                val summary = decoder.decode(sourceUri) { samples, count ->
                    if (count == 0) return@decode
                    decodedAnySamples = true
                    val remaining = if (mode == DebugBenchmarkMode.REALTIME) targetSamples - emitted else Long.MAX_VALUE
                    if (remaining <= 0L) throw TargetReached()
                    val accepted = min(count.toLong(), remaining).toInt()
                    paceBefore(enqueuedStartSample = emitted)
                    // The decoder owns its callback array. Copy only the bounded queued frame.
                    val depth = queuedFrames.incrementAndGet()
                    metrics.queueDepth(depth)
                    try {
                        queue.send(PcmFrame(samples.copyOf(accepted), accepted, emitted))
                    } catch (failure: Throwable) {
                        queuedFrames.decrementAndGet()
                        throw failure
                    }
                    emitted += accepted
                    queuedSamples.set(emitted)
                    metrics.input(accepted)
                    updateUiProgress()
                    if (mode == DebugBenchmarkMode.REALTIME && emitted >= targetSamples) throw TargetReached()
                }
                if (summary.totalSamples == 0L) throw IllegalArgumentException(tr("输入音频未产生 16 kHz PCM", "Input audio produced no 16 kHz PCM"))
            } catch (_: TargetReached) {
                break
            }
        } while (mode == DebugBenchmarkMode.REALTIME && emitted < targetSamples)
        check(decodedAnySamples) { tr("输入音频为空", "Input audio is empty") }
        // The final frame is scheduled at its start time. Keep the producer alive through its
        // end time so a requested 10/30/60/90-minute realtime run is not 100 ms short.
        if (mode == DebugBenchmarkMode.REALTIME) paceBefore(enqueuedStartSample = emitted)
    }

    private suspend fun paceBefore(enqueuedStartSample: Long) {
        if (mode != DebugBenchmarkMode.REALTIME) return
        val dueNanos = audioClockNanos + enqueuedStartSample * NANOS_PER_SECOND / LocalAsrSession.SAMPLE_RATE
        while (true) {
            val remaining = dueNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            val before = SystemClock.elapsedRealtimeNanos()
            delay((remaining / NANOS_PER_MILLI).coerceAtLeast(1L))
            pacingNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - before)
        }
    }

    private suspend fun consume(queue: Channel<PcmFrame>) {
        val local = recognizer ?: error("recognizer was not initialized")
        for (frame in queue) {
            try {
                local.acceptPcm16(frame.samples, frame.count, frame.startSample)
            } finally {
                queuedFrames.decrementAndGet()
            }
            completedSamples.set(frame.startSample + frame.count)
            local.snapshot().let { metrics.asrDuration(it.asrNanos) }
            if (mode == DebugBenchmarkMode.FAST) metrics.backlog(queuedSamples.get(), completedSamples.get())
            updateUiProgress()
        }
    }

    private suspend fun storeFinal(repository: com.classmemory.app.repository.SessionRepository, event: LocalAsrEvent) {
        if (event !is LocalAsrEvent.Final || !acceptingFinals.get()) return
        val text = event.text.trim().replace(Regex("\\s+"), " ")
        val stored = repository.storeFinal(
            sessionId = sessionId,
            generation = 0L,
            startMs = event.startSample * 1000L / LocalAsrSession.SAMPLE_RATE,
            endMs = event.endSample * 1000L / LocalAsrSession.SAMPLE_RATE,
            text = text,
        )
        if (stored) synchronized(transcript) {
            metrics.final(text, event.startSample, event.endSample)
            finalCharacters.addAndGet(text.length)
            transcript.append(event.startSample * 1000L / LocalAsrSession.SAMPLE_RATE)
                .append('-')
                .append(event.endSample * 1000L / LocalAsrSession.SAMPLE_RATE)
                .append(" ms\t")
                .append(text)
                .append('\n')
        }
    }

    private fun startMemorySampling() {
        memoryJob = scope.launch {
            while (isActive && running.get() && !finishing.get()) {
                recordMemoryAndBacklog()
                delay(MEMORY_SAMPLE_MILLIS)
            }
        }
    }

    private fun recordMemoryAndBacklog() {
        val now = SystemClock.elapsedRealtimeNanos()
        val runtime = Runtime.getRuntime()
        val javaBytes = runtime.totalMemory() - runtime.freeMemory()
        val nativeBytes = Debug.getNativeHeapAllocatedSize()
        val pssBytes = getSystemService(ActivityManager::class.java)
            .getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull()?.totalPss?.toLong()?.times(1024L) ?: 0L
        metrics.memory(
            (now - pipelineStartedNanos).coerceAtLeast(0),
            javaBytes,
            nativeBytes,
            pssBytes,
            pacingNanos.get(),
        )
        if (mode == DebugBenchmarkMode.REALTIME && audioClockNanos != 0L) {
            val planned = min(
                targetSamples,
                ((now - audioClockNanos).coerceAtLeast(0) * LocalAsrSession.SAMPLE_RATE / NANOS_PER_SECOND),
            )
            metrics.backlog(planned, completedSamples.get())
        }
    }

    private fun updateUiProgress() {
        _state.value = _state.value.copy(
            inputSeconds = queuedSamples.get().toDouble() / LocalAsrSession.SAMPLE_RATE,
            finalCharacters = finalCharacters.get(),
            status = if (mode == DebugBenchmarkMode.REALTIME) tr("按样本时钟导入中", "Importing in real time") else tr("不限速导入中", "Importing without pacing"),
        )
    }

    private fun requestFinish(reason: FinishReason, message: String? = null) {
        if (!running.get() || !finishing.compareAndSet(false, true)) return
        _state.value = _state.value.copy(status = tr("正在收尾", "Finishing"), error = message)
        if (reason != FinishReason.SUCCESS) runJob?.cancel(CancellationException(reason.outcome))
        scope.launch { cleanup(reason, message) }
    }

    private suspend fun cleanup(reason: FinishReason, message: String?) {
        try {
            val current = kotlin.coroutines.coroutineContext[Job]
            if (runJob != current) runJob?.join()
            if (reason == FinishReason.STOPPED) {
                // A normal stop drains already queued PCM, preserving final timestamps.
                consumerJob?.join()
                recognizer?.finish()
            } else {
                acceptingFinals.set(false)
                consumerJob?.cancelAndJoin()
            }
            acceptingFinals.set(false)
            recognizer?.snapshot()?.let { metrics.asrDuration(it.asrNanos) }
            recordMemoryAndBacklog()
            val wallNanos = (SystemClock.elapsedRealtimeNanos() - pipelineStartedNanos).coerceAtLeast(0)
            val passed = reason == FinishReason.SUCCESS && metrics.hasTranscript() && !metrics.hasErrors()
            val outcome = if (reason == FinishReason.SUCCESS && !passed) "no_transcript" else reason.outcome
            writeReports(outcome, passed, wallNanos)
            val persistedStatus = if (reason == FinishReason.SUCCESS && !passed) {
                SessionStatus.failed
            } else {
                reason.sessionStatus
            }
            (application as ClassMemoryApp).repository.finish(
                sessionId,
                persistedStatus,
                System.currentTimeMillis(),
                audioDurationSec(queuedSamples.get(), LocalAsrSession.SAMPLE_RATE),
            )
            _state.value = _state.value.copy(
                running = false,
                status = if (passed) tr("完成", "Complete") else tr("未通过：$outcome", "Failed: $outcome"),
                error = message ?: if (passed) null else tr("Benchmark 未产生有效最终转写或未完整完成", "Benchmark produced no valid final transcript or did not complete"),
                inputSeconds = queuedSamples.get().toDouble() / LocalAsrSession.SAMPLE_RATE,
                finalCharacters = finalCharacters.get(),
                textReportPath = reportTextFile().absolutePath,
                jsonReportPath = reportJsonFile().absolutePath,
            )
        } catch (_: Throwable) {
            metrics.error()
            _state.value = _state.value.copy(running = false, status = tr("收尾失败", "Finalization failed"), error = tr("Benchmark 收尾或报告导出失败", "Benchmark finalization or report export failed"))
        } finally {
            memoryJob?.cancelAndJoin()
            recognizer?.close()
            recognizer = null
            frames?.close()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            scope.cancel()
        }
    }

    private fun writeReports(outcome: String, passed: Boolean, wallNanos: Long) {
        val directory = File(filesDir, REPORT_DIRECTORY).apply { mkdirs() }
        val hotwordSnapshot = hotwordSettingsSnapshot
        val hotwordSettings = hotwordSnapshot.settings
        val domainHotwords = BuildConfig.ASR_MODEL_ID == "xasr" && hotwordSnapshot.enabled
        val text = buildString {
            append("ClassMemory Debug ASR Benchmark\n")
            append("model=").append(actualModelLabel()).append('\n')
            append("modelDirectory=").append(BuildConfig.ASR_MODEL_DIR).append('\n')
            append("hotwordProfile=").append(hotwordSettings.profile).append('\n')
            append("courseId=").append(hotwordSnapshot.courseId).append('\n')
            append("courseName=").append(hotwordSnapshot.courseName).append('\n')
            append("hotwordsEnabled=").append(hotwordSnapshot.enabled).append('\n')
            append("domainHotwords=").append(domainHotwords).append('\n')
            if (BuildConfig.ASR_MODEL_ID == "xasr") {
                append("hotwordCount=").append(hotwordSettings.nativeHotwords.size).append('\n')
                append("hotwordsScore=").append(hotwordSettings.hotwordsScore).append('\n')
                append("decodingMethod=").append(hotwordSettings.decodingMethod).append('\n')
                append("maxActivePaths=").append(hotwordSettings.maxActivePaths).append('\n')
            }
            append("applicationId=").append(packageName).append('\n')
            append("segmentation=").append(if (BuildConfig.ASR_MODEL_ID == "qwen3") "vad_non_streaming" else "streaming_endpoint").append('\n')
            append("sessionId=").append(sessionId).append('\n')
            append("outcome=").append(outcome).append(" passed=").append(passed).append("\n\n")
            append(synchronized(transcript) { transcript.toString() })
        }
        reportTextFile(directory).writeText(text, Charsets.UTF_8)
        reportJsonFile(directory).writeText(
            JSONObject(metrics.json(
                mode.name.lowercase(),
                outcome,
                passed,
                wallNanos,
                pacingNanos.get(),
                initializationNanos,
                QUEUE_FRAMES,
            )).apply {
                put("modelId", BuildConfig.ASR_MODEL_ID)
                put("modelDirectory", BuildConfig.ASR_MODEL_DIR)
                put("hotwordProfile", hotwordSettings.profile)
                put("courseId", hotwordSnapshot.courseId)
                put("courseName", hotwordSnapshot.courseName)
                put("hotwordsEnabled", hotwordSnapshot.enabled)
                put("domainHotwords", domainHotwords)
                if (BuildConfig.ASR_MODEL_ID == "xasr") {
                    put("hotwordCount", hotwordSettings.nativeHotwords.size)
                    put("hotwordsScore", hotwordSettings.hotwordsScore)
                    put("decodingMethod", hotwordSettings.decodingMethod)
                    put("maxActivePaths", hotwordSettings.maxActivePaths)
                }
                put("applicationId", packageName)
                put("segmentation", if (BuildConfig.ASR_MODEL_ID == "qwen3") "vad_non_streaming" else "streaming_endpoint")
            }.toString(2),
            Charsets.UTF_8,
        )
    }

    private fun reportTextFile(directory: File = File(filesDir, REPORT_DIRECTORY)) = File(directory, "$sessionId.txt")
    private fun reportJsonFile(directory: File = File(filesDir, REPORT_DIRECTORY)) = File(directory, "$sessionId.json")

    private fun actualModelLabel(): String = when {
        BuildConfig.ASR_HOTWORD_PROFILE == AsrHotwordSettings.DATA_STRUCTURE && !hotwordSettingsSnapshot.enabled -> "X-ASR"
        BuildConfig.ASR_HOTWORD_PROFILE == AsrHotwordSettings.DATA_STRUCTURE -> tr("X-ASR ${hotwordSettingsSnapshot.courseName}热词", "X-ASR ${hotwordSettingsSnapshot.courseName} hotwords")
        else -> BuildConfig.ASR_MODEL_LABEL
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("Debug ASR Benchmark", "Debug ASR benchmark"), NotificationManager.IMPORTANCE_LOW),
        )
    }

    // Keep the platform notification type explicit in the generated Java stub.
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(tr("Debug 音频 Benchmark", "Debug audio benchmark"))
        .setContentText(text)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        if (running.get() && !finishing.get()) requestFinish(FinishReason.FAILED, tr("服务被系统销毁", "Service was destroyed by the system"))
        super.onDestroy()
    }

}
