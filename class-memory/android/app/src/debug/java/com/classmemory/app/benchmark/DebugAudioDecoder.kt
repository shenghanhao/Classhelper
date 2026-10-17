package com.classmemory.app.benchmark

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Metadata for a completed decode. [totalSamples] is the number of 16 kHz mono samples emitted. */
data class DecodeSummary(
    val totalSamples: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
)

/**
 * Debug-only URI decoder used by the ASR benchmark.
 *
 * It owns neither the URI nor the callback's samples. The callback is invoked serially and a
 * returned [ShortArray] may be reused immediately after that invocation returns.
 */
class DebugAudioDecoder(context: Context) {
    private val appContext = context.applicationContext

    suspend fun decode(
        uri: Uri,
        onPcm: suspend (ShortArray, Int) -> Unit,
    ): DecodeSummary {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val track = selectAudioTrack(extractor)
            if (track < 0) throw IllegalArgumentException("Unsupported audio: no audio track")
            extractor.selectTrack(track)

            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Unsupported audio: missing MIME type")
            val sourceRate = inputFormat.requiredPositiveInt(MediaFormat.KEY_SAMPLE_RATE, "sample rate")
            val sourceChannels = inputFormat.requiredPositiveInt(MediaFormat.KEY_CHANNEL_COUNT, "channel count")
            val output = PcmOutput(onPcm)

            // WAV/other PCM tracks are already decoded by the platform extractor. Avoid a
            // device-specific audio/raw codec, but retain the exact same streaming resampler.
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                requirePcm16(inputFormat)
                decodeRaw(extractor, sourceRate, sourceChannels, output)
            } else {
                codec = createSupportedDecoder(mime)
                decodeCompressed(extractor, codec, inputFormat, sourceRate, sourceChannels, output)
            }
            output.finish()
            return DecodeSummary(output.totalSamples, sourceRate, sourceChannels)
        } finally {
            // This also runs when a suspending callback is cancelled. No source audio or decoded
            // PCM is copied to disk; only a small codec/input/output working set exists in memory.
            try {
                codec?.stop()
            } catch (_: IllegalStateException) {
                // Codec creation/configure may have failed before start.
            }
            codec?.release()
            extractor.release()
        }
    }

    private suspend fun decodeRaw(
        extractor: MediaExtractor,
        sampleRate: Int,
        channels: Int,
        output: PcmOutput,
    ) {
        val buffer = ByteBuffer.allocateDirect(RAW_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        output.configure(sampleRate, channels)
        while (true) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val bytes = extractor.readSampleData(buffer, 0)
            if (bytes < 0) return
            if (bytes == 0 || bytes % (channels * PCM16_BYTES) != 0) {
                throw IllegalArgumentException("Unsupported PCM payload: not 16-bit interleaved audio")
            }
            buffer.limit(bytes)
            buffer.position(0)
            output.acceptPcm16(buffer, bytes / PCM16_BYTES)
            extractor.advance()
        }
    }

    private suspend fun decodeCompressed(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        initialRate: Int,
        initialChannels: Int,
        output: PcmOutput,
    ) {
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        var inputEos = false
        var outputEos = false
        var outputFormatSeen = false
        var activeRate = initialRate
        var activeChannels = initialChannels
        val info = MediaCodec.BufferInfo()

        while (!outputEos) {
            currentCoroutineContext().ensureActive()
            if (!inputEos) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index)
                        ?: throw IllegalStateException("Decoder supplied a null input buffer")
                    input.clear()
                    val bytes = extractor.readSampleData(input, 0)
                    if (bytes < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(index, 0, bytes, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val newRate = format.requiredPositiveInt(MediaFormat.KEY_SAMPLE_RATE, "decoded sample rate")
                    val newChannels = format.requiredPositiveInt(MediaFormat.KEY_CHANNEL_COUNT, "decoded channel count")
                    requirePcm16(format)
                    if (output.isConfigured && (newRate != activeRate || newChannels != activeChannels)) {
                        // Finish the old stream before changing its time base. Format changes are
                        // rare for AAC, but silently interpreting old samples with new metadata is worse.
                        output.finishSegment()
                    }
                    activeRate = newRate
                    activeChannels = newChannels
                    output.configure(activeRate, activeChannels)
                    outputFormatSeen = true
                }

                else -> if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            if (!outputFormatSeen || !output.isConfigured) {
                                throw IllegalArgumentException("Unsupported decoder output: missing PCM output format")
                            }
                            val pcm = codec.getOutputBuffer(index)
                                ?: throw IllegalStateException("Decoder supplied a null output buffer")
                            if (info.size % (activeChannels * PCM16_BYTES) != 0) {
                                throw IllegalArgumentException("Unsupported decoder output: not PCM16 interleaved audio")
                            }
                            pcm.order(ByteOrder.LITTLE_ENDIAN)
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            output.acceptPcm16(pcm, info.size / PCM16_BYTES)
                        }
                        outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun createSupportedDecoder(mime: String): MediaCodec {
        if (mime != MediaFormat.MIMETYPE_AUDIO_AAC && mime != "audio/mp4a-latm") {
            throw IllegalArgumentException("Unsupported audio codec: $mime (only PCM WAV and AAC/M4A are supported)")
        }
        return try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            throw IllegalArgumentException("Unsupported audio codec: $mime", error)
        }
    }

    private fun requirePcm16(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
        ) {
            throw IllegalArgumentException("Unsupported PCM encoding: only 16-bit PCM is supported")
        }
    }

    private fun MediaFormat.requiredPositiveInt(key: String, label: String): Int {
        if (!containsKey(key)) throw IllegalArgumentException("Unsupported audio: missing $label")
        return getInteger(key).takeIf { it > 0 }
            ?: throw IllegalArgumentException("Unsupported audio: invalid $label")
    }

    /** Bounded output staging plus a stateful windowed-sinc resampler. */
    private class PcmOutput(private val callback: suspend (ShortArray, Int) -> Unit) {
        private val frame = ShortArray(OUTPUT_FRAME_SAMPLES)
        private var frameSize = 0
        private var resampler: StreamingResampler? = null
        var totalSamples: Long = 0
            private set
        val isConfigured: Boolean get() = resampler != null

        suspend fun configure(rate: Int, channels: Int) {
            if (resampler == null) resampler = StreamingResampler(rate, channels, ::emit)
        }

        suspend fun acceptPcm16(buffer: ByteBuffer, shortCount: Int) {
            val current = resampler ?: throw IllegalStateException("Decoder emitted PCM before its format")
            current.accept(buffer, shortCount)
        }

        suspend fun finishSegment() {
            resampler?.finish()
            resampler = null
        }

        suspend fun finish() {
            finishSegment()
            flush()
        }

        private suspend fun emit(sample: Short) {
            frame[frameSize++] = sample
            totalSamples++
            if (frameSize == frame.size) flush()
        }

        private suspend fun flush() {
            if (frameSize == 0) return
            currentCoroutineContext().ensureActive()
            callback(frame, frameSize) // Serial suspension is intentional backpressure.
            frameSize = 0
        }
    }

    /**
     * Produces a 16 kHz stream from PCM16 input without retaining audio beyond a 32-tap FIR
     * history. A Hann-windowed sinc low-pass filter prevents simple-decimation aliasing.
     */
    internal class StreamingResampler(
        private val inputRate: Int,
        private val channels: Int,
        private val emit: suspend (Short) -> Unit,
    ) {
        private val sourceStep = inputRate.toDouble() / TARGET_SAMPLE_RATE
        private val cutoff = 0.5 * min(1.0, TARGET_SAMPLE_RATE.toDouble() / inputRate)
        private val samples = FloatArray(FIR_TAPS * 3)
        private var bufferStart = 0L
        private var size = 0
        private var inputCount = 0L
        private var nextPosition = 0.0

        suspend fun accept(buffer: ByteBuffer, shortCount: Int) {
            if (shortCount % channels != 0) throw IllegalArgumentException("PCM frame is not channel aligned")
            repeat(shortCount / channels) {
                var sum = 0L
                repeat(channels) { sum += buffer.short.toLong() }
                append((sum / channels).toFloat()) // Long accumulator avoids multi-channel overflow.
                emitAvailable(allowRightZeroPadding = false)
            }
        }

        suspend fun finish() {
            emitAvailable(allowRightZeroPadding = true)
        }

        private suspend fun append(value: Float) {
            if (size == samples.size) discardBefore((floor(nextPosition).toLong() - FIR_HALF - 1).coerceAtLeast(0))
            check(size < samples.size) { "Resampler history was not drained" }
            samples[size++] = value
            inputCount++
        }

        private suspend fun emitAvailable(allowRightZeroPadding: Boolean) {
            while (nextPosition < inputCount &&
                (allowRightZeroPadding || nextPosition + FIR_HALF <= inputCount - 1)
            ) {
                emit(filteredSample(nextPosition))
                nextPosition += sourceStep
                discardBefore((floor(nextPosition).toLong() - FIR_HALF - 1).coerceAtLeast(0))
            }
        }

        private fun filteredSample(position: Double): Short {
            val center = floor(position).toLong()
            var value = 0.0
            var weight = 0.0
            for (tap in 0 until FIR_TAPS) {
                val index = center + tap - FIR_HALF + 1
                val distance = index - position
                val window = 0.5 + 0.5 * cos(PI * distance / FIR_HALF)
                val x = 2.0 * cutoff * distance
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val coefficient = 2.0 * cutoff * sinc * window
                value += sampleAt(index) * coefficient
                weight += coefficient
            }
            val normalized = if (weight == 0.0) value else value / weight
            return normalized.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }

        private fun sampleAt(index: Long): Double {
            val offset = index - bufferStart
            return if (offset in 0 until size) samples[offset.toInt()].toDouble() else 0.0
        }

        private fun discardBefore(index: Long) {
            val count = (index - bufferStart).coerceIn(0, size.toLong()).toInt()
            if (count == 0) return
            samples.copyInto(samples, 0, count, size)
            size -= count
            bufferStart += count
        }
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val OUTPUT_FRAME_SAMPLES = 1_600
        const val PCM16_BYTES = 2
        const val RAW_BUFFER_BYTES = 64 * 1024
        const val CODEC_TIMEOUT_US = 10_000L
        const val FIR_TAPS = 32
        const val FIR_HALF = FIR_TAPS / 2
    }
}
