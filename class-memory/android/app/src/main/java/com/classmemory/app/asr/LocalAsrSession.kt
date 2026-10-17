package com.classmemory.app.asr

import android.content.Context
import com.classmemory.app.BuildConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Serial owner of the selected local recognizer. PCM arrays are consumed before return. */
class LocalAsrSession private constructor(private val backend: Backend) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
        private const val HOTWORDS = "COS/SIN"

        fun create(
            context: Context,
            hotwordSnapshot: AsrHotwordSessionSnapshot = AsrHotwordPreferences.snapshot(context),
            onEvent: suspend (LocalAsrEvent) -> Unit,
        ): LocalAsrSession {
            val modelDir = BuildConfig.ASR_MODEL_DIR
            val backend = when (BuildConfig.ASR_MODEL_ID) {
                "zipformer" -> createOnline(
                    context,
                    zipformerFiles(modelDir),
                    hotwords = HOTWORDS,
                    modelType = "",
                    decodingMethod = "modified_beam_search",
                    modelingUnit = "cjkchar+bpe",
                )
                "xasr" -> {
                    val settings = hotwordSnapshot.settings
                    createOnline(
                        context,
                        findOnlineModelFiles(context, modelDir, settings.requiresBpeVocab),
                        hotwords = settings.tokenizerInput,
                        modelType = "zipformer2",
                        decodingMethod = settings.decodingMethod,
                        modelingUnit = settings.modelingUnit,
                        hotwordsScore = settings.hotwordsScore,
                        maxActivePaths = settings.maxActivePaths,
                    )
                }
                "qwen3" -> createQwen(context, modelDir)
                else -> error("Unsupported ASR model id: ${BuildConfig.ASR_MODEL_ID}")
            }
            return LocalAsrSession(backend.also { it.onEvent = onEvent })
        }

        private fun zipformerFiles(dir: String) = OnlineModelFiles(
            encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
            decoder = "$dir/decoder-epoch-99-avg-1.onnx",
            joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
            tokens = "$dir/tokens.txt",
            bpeVocab = "$dir/bpe.vocab",
        )

        private fun findOnlineModelFiles(context: Context, dir: String, requireBpeVocab: Boolean): OnlineModelFiles {
            val files = context.assets.list(dir)?.toList().orEmpty()
            fun required(name: String) = "$dir/$name".also {
                require(name in files) { "Missing $name in ASR model assets" }
            }
            return OnlineModelFiles(
                encoder = required("encoder.int8.onnx"),
                decoder = required("decoder.onnx"),
                joiner = required("joiner.int8.onnx"),
                tokens = required("tokens.txt"),
                bpeVocab = if (requireBpeVocab) required("bpe.vocab") else null,
            )
        }

        private fun createOnline(
            context: Context,
            files: OnlineModelFiles,
            hotwords: String,
            modelType: String,
            decodingMethod: String,
            modelingUnit: String = "",
            hotwordsScore: Float = if (hotwords.isEmpty()) 0.0f else 2.0f,
            maxActivePaths: Int = 4,
        ): Backend {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = files.encoder,
                        decoder = files.decoder,
                        joiner = files.joiner,
                    ),
                    tokens = files.tokens,
                    modelingUnit = modelingUnit,
                    bpeVocab = files.bpeVocab.orEmpty(),
                    modelType = modelType,
                    numThreads = 2,
                    provider = "cpu",
                ),
                endpointConfig = EndpointConfig(),
                enableEndpoint = true,
                decodingMethod = decodingMethod,
                maxActivePaths = maxActivePaths,
                hotwordsScore = hotwordsScore,
            )
            val recognizer = OnlineRecognizer(context.assets, config)
            return try {
                OnlineBackend(recognizer, recognizer.createStream(hotwords))
            } catch (t: Throwable) {
                recognizer.release()
                throw t
            }
        }

        private fun createQwen(context: Context, modelDir: String): Backend {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = "$modelDir/conv_frontend.onnx",
                        encoder = "$modelDir/encoder.int8.onnx",
                        decoder = "$modelDir/decoder.int8.onnx",
                        tokenizer = "$modelDir/tokenizer",
                        maxTotalLen = 512,
                        maxNewTokens = 512,
                    ),
                    numThreads = 3,
                    provider = "cpu",
                ),
            )
            val recognizer = OfflineRecognizer(context.assets, config)
            return try {
                val vad = Vad(
                    context.assets,
                    VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "$modelDir/silero_vad.onnx",
                            maxSpeechDuration = 20.0f,
                        ),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                    ),
                )
                QwenBackend(recognizer, vad)
            } catch (t: Throwable) {
                recognizer.release()
                throw t
            }
        }
    }

    private var closed = false
    private var finished = false

    suspend fun acceptPcm16(samples: ShortArray, count: Int, sourceStartSample: Long) {
        check(!closed) { "Local ASR session is closed" }
        check(!finished) { "Local ASR session is finished" }
        require(count in 0..samples.size) { "invalid PCM sample count" }
        require(sourceStartSample >= backend.receivedSamples) { "non-monotonic PCM clock" }
        if (count == 0) return
        backend.accept(samples, count, sourceStartSample)
    }

    suspend fun finish() {
        if (closed || finished) return
        finished = true
        backend.finish()
    }

    fun snapshot() = backend.snapshot()

    fun close() {
        if (closed) return
        closed = true
        backend.close()
    }
}

private data class OnlineModelFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val bpeVocab: String? = null,
)

private abstract class Backend {
    lateinit var onEvent: suspend (LocalAsrEvent) -> Unit
    var receivedSamples = 0L
        protected set
    protected var processedSamples = 0L
    protected var asrNanos = 0L
    protected var decodePasses = 0L

    abstract suspend fun accept(samples: ShortArray, count: Int, sourceStartSample: Long)
    abstract suspend fun finish()
    abstract fun close()

    fun snapshot() = LocalAsrMetrics(receivedSamples, processedSamples, asrNanos, decodePasses)
}

private class OnlineBackend(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : Backend() {
    private var utteranceStartSample = 0L
    private var closed = false

    override suspend fun accept(samples: ShortArray, count: Int, sourceStartSample: Long) {
        val started = System.nanoTime()
        val floats = FloatArray(count) { samples[it] / 32768.0f }
        stream.acceptWaveform(floats, LocalAsrSession.SAMPLE_RATE)
        receivedSamples = sourceStartSample + count
        decodeReady()
        asrNanos += System.nanoTime() - started
    }

    override suspend fun finish() {
        val started = System.nanoTime()
        stream.inputFinished()
        decodeReady()
        emitFinal(receivedSamples)
        asrNanos += System.nanoTime() - started
    }

    private suspend fun decodeReady() {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
            decodePasses++
            processedSamples = receivedSamples
            val text = recognizer.getResult(stream).text.trim()
            if (recognizer.isEndpoint(stream)) {
                emitFinal(receivedSamples, text)
                recognizer.reset(stream)
                utteranceStartSample = receivedSamples
            } else if (text.isNotEmpty()) {
                onEvent(LocalAsrEvent.Partial(text, processedSamples))
            }
        }
    }

    private suspend fun emitFinal(endSample: Long, knownText: String? = null) {
        val text = (knownText ?: recognizer.getResult(stream).text).trim()
        if (text.isNotEmpty() && endSample >= utteranceStartSample) {
            onEvent(LocalAsrEvent.Final(text, utteranceStartSample, endSample))
        }
        utteranceStartSample = endSample
    }

    override fun close() {
        if (closed) return
        closed = true
        try { stream.release() } finally { recognizer.release() }
    }
}

/**
 * Qwen is offline. Silero VAD queues speech regions capped at 20 seconds, and
 * each region is decoded once then released, so course length does not grow RAM.
 */
private class QwenBackend(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
) : Backend() {
    private var timelineOrigin: Long? = null
    private var closed = false

    override suspend fun accept(samples: ShortArray, count: Int, sourceStartSample: Long) {
        if (timelineOrigin == null) {
            timelineOrigin = sourceStartSample
            receivedSamples = sourceStartSample
        }
        require(sourceStartSample >= receivedSamples) { "non-monotonic PCM clock" }
        val started = System.nanoTime()
        var gap = sourceStartSample - receivedSamples
        while (gap > 0) {
            val silenceCount = minOf(QWEN_VAD_WINDOW_SAMPLES.toLong(), gap).toInt()
            feedVad(FloatArray(silenceCount))
            gap -= silenceCount
        }
        // VAD consumes its 512-sample windows without allocating an array
        // proportional to caller chunk size. Drain as segments become ready.
        var offset = 0
        while (offset < count) {
            val windowCount = qwenVadChunkSize(count - offset)
            val floats = FloatArray(windowCount) { samples[offset + it] / 32768.0f }
            feedVad(floats)
            offset += windowCount
        }
        asrNanos += System.nanoTime() - started
    }

    private suspend fun feedVad(samples: FloatArray) {
        vad.acceptWaveform(samples)
        receivedSamples = qwenVadReceivedThroughSample(receivedSamples, samples.size)
        drainSegments()
    }

    override suspend fun finish() {
        val started = System.nanoTime()
        vad.flush()
        drainSegments()
        processedSamples = receivedSamples
        asrNanos += System.nanoTime() - started
    }

    private suspend fun drainSegments() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (segment.samples.isEmpty()) continue
            val origin = timelineOrigin ?: 0L
            val range = qwenSegmentSampleRange(origin, segment.start, segment.samples.size, receivedSamples)
            val stream: OfflineStream = recognizer.createStream()
            try {
                stream.acceptWaveform(segment.samples, LocalAsrSession.SAMPLE_RATE)
                recognizer.decode(stream)
                decodePasses++
                val text = recognizer.getResult(stream).text.trim()
                processedSamples = range.endSample
                if (text.isNotEmpty() && range.endSample > range.startSample) {
                    onEvent(LocalAsrEvent.Final(text, range.startSample, range.endSample))
                }
            } finally {
                stream.release()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { vad.release() } finally { recognizer.release() }
    }
}

internal data class QwenSegmentSampleRange(val startSample: Long, val endSample: Long)

private const val QWEN_VAD_WINDOW_SAMPLES = 512

internal fun qwenVadChunkSize(remainingSamples: Int): Int =
    remainingSamples.coerceAtLeast(0).coerceAtMost(QWEN_VAD_WINDOW_SAMPLES)

internal fun qwenVadReceivedThroughSample(currentEndSample: Long, acceptedSamples: Int): Long =
    currentEndSample + acceptedSamples.coerceAtLeast(0)

internal fun qwenSegmentSampleRange(
    originSample: Long,
    segmentStartOffset: Int,
    segmentSampleCount: Int,
    receivedThroughSample: Long,
): QwenSegmentSampleRange {
    val start = originSample + segmentStartOffset.coerceAtLeast(0).toLong()
    val end = (start + segmentSampleCount.coerceAtLeast(0)).coerceAtMost(receivedThroughSample)
    return QwenSegmentSampleRange(start, end.coerceAtLeast(start))
}

sealed interface LocalAsrEvent {
    data class Partial(val text: String, val processedSamples: Long) : LocalAsrEvent
    /** Endpoint-level input range, not token-level alignment. */
    data class Final(val text: String, val startSample: Long, val endSample: Long) : LocalAsrEvent
}

data class LocalAsrMetrics(
    val receivedSamples: Long,
    val processedSamples: Long,
    val asrNanos: Long,
    val decodePasses: Long,
)
