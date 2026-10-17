package com.classmemory.app.service

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.classmemory.app.ClassMemoryApp
import com.classmemory.app.audio.AudioCapture
import com.classmemory.app.asr.LocalAsrEvent
import com.classmemory.app.asr.LocalAsrSession
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.AsrHotwordSettings
import com.classmemory.app.asr.AsrHotwordSessionSnapshot
import com.classmemory.app.data.audioDurationSec
import com.classmemory.app.data.SessionStatus
import com.classmemory.app.i18n.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.*

data class RecordingUiState(
    val sessionId: String? = null,
    val startedAt: Long = 0,
    val asrReady: Boolean = false,
    val partial: String = "",
    val finalCount: Int = 0,
    val ending: Boolean = false,
    val error: String? = null
)

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.classmemory.START"
        const val ACTION_STOP = "com.classmemory.STOP"
        private const val TAG = "ClassMemory.Recording"
        private val _state = MutableStateFlow(RecordingUiState())
        val state = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val acceptingFinals = AtomicBoolean(false)
    private val queue = Channel<Frame>(4)
    private val finalJobs = CopyOnWriteArrayList<Job>()
    private val finalLock = Any()
    private val framesRead = AtomicLong()
    private val audioSamples = AtomicLong()
    private val partials = AtomicLong()
    private val finals = AtomicLong()
    private val finalCharacters = AtomicLong()

    private var capture: AudioCapture? = null
    private var captureJob: Job? = null
    private var asrJob: Job? = null
    private var beginJob: Job? = null
    private var heartbeatJob: Job? = null
    private var localAsr: LocalAsrSession? = null
    private var hotwordSettingsSnapshot = AsrHotwordSessionSnapshot(
        enabled = false, courseId = "", courseName = "", settings = AsrHotwordSettings.forProfile("none"),
    )
    private var wake: PowerManager.WakeLock? = null
    private var id = ""
    private var begun = 0L

    private data class Frame(val bytes: ByteArray, val startSample: Long)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!running.get()) {
                    begin()
                }
            }
            ACTION_STOP -> scope.launch { finish(SessionStatus.completed) }
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = RecordingUiState(error = tr("未授予麦克风权限", "Microphone permission was not granted"))
            stopSelf()
            return
        }
        val hotwordSnapshot = runCatching { AsrHotwordPreferences.snapshot(this) }.getOrElse {
            _state.value = RecordingUiState(error = tr("课程热词配置不可用：${it.message ?: "请检查自定义词库"}", "Course hotword settings are unavailable: ${it.message ?: "Check the custom library"}"))
            stopSelf()
            return
        }
        hotwordSettingsSnapshot = hotwordSnapshot
        running.set(true)
        finishing.set(false)
        acceptingFinals.set(false)
        audioSamples.set(0)
        id = UUID.randomUUID().toString()
        begun = System.currentTimeMillis()
        _state.value = RecordingUiState(id, begun)
        Log.i(TAG, "recordingStarted sessionId=" + id)
        wake = null

        beginJob = scope.launch {
            try {
                channel()
                startForeground(9, note(tr("正在准备课堂录音", "Preparing classroom recording")))
                wake = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClassMemory:recording")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                (application as ClassMemoryApp).repository.begin(id, begun)
                if (!running.get() || finishing.get()) return@launch
                acceptingFinals.set(true)
                heartbeatJob = scope.launch {
                    while (isActive && running.get()) {
                        delay(60_000)
                        runCatching {
                            (application as ClassMemoryApp).repository.heartbeat(id)
                        }
                        Log.i(
                            TAG,
                            "heartbeat sessionId=" + id +
                                " recordingStarted=" + begun +
                                " audioFramesRead=" + framesRead.get() +
                                " audioDurationSec=" + audioSamples.get() / AudioCapture.SAMPLE_RATE +
                                " partialTranscriptCount=" + partials.get() +
                                " finalTranscriptCount=" + finals.get() +
                                " finalCharacters=" + finalCharacters.get()
                        )
                    }
                }
                capture = AudioCapture().also { it.start() }
                captureJob = scope.launch { captureLoop() }
                asrJob = scope.launch { localAsrLoop() }
                Log.i(TAG, "started session=" + id)
            } catch (t: Throwable) {
                Log.e(TAG, "recording initialization failed")
                requestFinish(SessionStatus.failed, tr("录音初始化失败", "Recording initialization failed"))
            }
        }
    }

    private suspend fun captureLoop() {
        try {
            while (running.get() && !finishing.get()) {
                val currentCapture = capture ?: break
                val bytes = currentCapture.readFrame() ?: break
                framesRead.incrementAndGet()
                val firstSample = audioSamples.getAndAdd(currentCapture.lastReadSamples.toLong())
                queue.send(Frame(bytes, firstSample))
            }
            if (running.get() && !finishing.get()) {
                requestFinish(SessionStatus.failed, tr("麦克风读取失败", "Microphone input failed"))
            }
        } catch (t: Throwable) {
            if (running.get() && !finishing.get()) {
                requestFinish(SessionStatus.failed, tr("麦克风读取失败", "Microphone input failed"))
            }
            Log.e(TAG, "capture loop failed")
        }
    }

    /** AudioRecord feeds this bounded PCM queue before local ASR consumes it. */
    private suspend fun localAsrLoop() {
        val recognizer = try {
            LocalAsrSession.create(this, hotwordSettingsSnapshot) { event ->
                when (event) {
                    is LocalAsrEvent.Partial -> {
                        partials.incrementAndGet()
                        _state.update { it.copy(partial = event.text) }
                    }
                    is LocalAsrEvent.Final -> storeLocalFinal(event)
                }
            }
        } catch (t: Throwable) {
            if (running.get() && !finishing.get()) {
                requestFinish(SessionStatus.failed, tr("本地语音识别初始化失败", "Local speech recognition initialization failed"))
            }
            return
        }
        localAsr = recognizer
        _state.update { it.copy(asrReady = true, error = null) }
        try {
            while (true) {
                val frame = queue.receiveCatching().getOrNull() ?: break
                val samples = ShortArray(frame.bytes.size / 2) { index ->
                    ((frame.bytes[index * 2].toInt() and 0xff) or
                        (frame.bytes[index * 2 + 1].toInt() shl 8)).toShort()
                }
                recognizer.acceptPcm16(samples, samples.size, frame.startSample)
            }
            recognizer.finish()
        } finally {
            _state.update { it.copy(asrReady = false) }
            recognizer.close()
            if (localAsr === recognizer) localAsr = null
        }
    }

    private fun storeLocalFinal(event: LocalAsrEvent.Final) {
        val text = event.text.trim().replace(Regex("\\s+"), " ")
        val job = synchronized(finalLock) {
            if (!acceptingFinals.get() || !running.get()) return
            scope.launch(start = CoroutineStart.LAZY) {
                runCatching {
                    (application as ClassMemoryApp).repository.storeFinal(
                        id, 0L,
                        event.startSample * 1000L / AudioCapture.SAMPLE_RATE,
                        event.endSample * 1000L / AudioCapture.SAMPLE_RATE,
                        text,
                    )
                }.onSuccess { stored ->
                    if (stored) {
                        finals.incrementAndGet(); finalCharacters.addAndGet(text.length.toLong())
                        _state.update { it.copy(finalCount = it.finalCount + text.length, partial = "") }
                    }
                }.onFailure { Log.e(TAG, "local final storage failed") }
            }.also { registered ->
                finalJobs += registered
                registered.invokeOnCompletion { finalJobs.remove(registered) }
            }
        }
        job.start()
    }

    private fun requestFinish(status: SessionStatus, reason: String? = null) {
        if (running.get() && !finishing.get()) {
            scope.launch { finish(status, reason) }
        }
    }

    private fun closeFinalAdmission(): List<Job> =
        synchronized(finalLock) {
            acceptingFinals.set(false)
            finalJobs.toList()
        }

    private suspend fun finish(status: SessionStatus, reason: String? = null) {
        if (!running.get() || !finishing.compareAndSet(false, true)) return
        _state.update { it.copy(ending = true) }
        try {
            capture?.stop()
            if (currentCoroutineContext()[Job] !== captureJob) captureJob?.join()
            capture?.release()
            capture = null
            queue.close()
            asrJob?.join()
            val pendingFinals = closeFinalAdmission()
            pendingFinals.joinAll()
            beginJob?.join()
            val ended = System.currentTimeMillis()
            (application as ClassMemoryApp).repository.finish(
                id,
                status,
                ended,
                audioDurationSec(audioSamples.get(), AudioCapture.SAMPLE_RATE),
            )
            val error = reason ?: if (_state.value.partial.isNotBlank()) {
                tr("尾句未确认，未保存为最终文本", "Final phrase unconfirmed; it was not saved as final text")
            } else {
                null
            }
            val confirmedCharacters = _state.value.finalCount
            _state.value = RecordingUiState(error = error, finalCount = confirmedCharacters)
            Log.i(
                TAG,
                "recordingStopped sessionId=" + id +
                    " status=" + status +
                    " audioDurationSec=" + audioDurationSec(audioSamples.get(), AudioCapture.SAMPLE_RATE) +
                    " audioFramesRead=" + framesRead.get() +
                    " partialTranscriptCount=" + partials.get() +
                    " finalTranscriptCount=" + finals.get() +
                    " finalCharacters=" + finalCharacters.get()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "finish failed")
            _state.value = RecordingUiState(error = tr("录音结束失败", "Failed to finish recording"))
        } finally {
            running.set(false)
            heartbeatJob?.cancel()
            asrJob?.cancelAndJoin()
            queue.close()
            capture?.release()
            capture = null
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            scope.cancel()
        }
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "recording",
                    tr("课堂记录", "Classroom recording"),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    private fun note(text: String) =
        NotificationCompat.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(tr("正在记录课堂", "Recording class"))
            .setContentText(text)
            .setOngoing(true)
            .setWhen(begun)
            .setUsesChronometer(true)
            .build()

    override fun onDestroy() {
        capture?.stop()
        if (!finishing.get()) {
            scope.launch { finish(SessionStatus.failed) }
        }
        super.onDestroy()
    }
}
