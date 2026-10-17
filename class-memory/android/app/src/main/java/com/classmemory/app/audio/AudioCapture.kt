package com.classmemory.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns one microphone only. Call stop(), await the reading loop, then release(). */
class AudioCapture {
    companion object { const val SAMPLE_RATE = 16_000; const val FRAME_SAMPLES = 1_600; const val FRAME_BYTES = FRAME_SAMPLES * 2 }
    private val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_BYTES * 2))
    private val samples = ShortArray(FRAME_SAMPLES)
    private val bytes = ByteArray(FRAME_BYTES)
    @Volatile var lastReadSamples: Int = 0
    fun start() {
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            record.startRecording()
        } catch (t: Throwable) {
            release()
            throw t
        }
    }
    suspend fun readFrame(): ByteArray? = withContext(Dispatchers.IO) {
        val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        if (count <= 0) return@withContext null
        lastReadSamples = count
        for (i in 0 until count) { val v = samples[i].toInt(); bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
        bytes.copyOf(count * 2) // ownership changes at async network boundary; capture arrays remain reusable.
    }
    fun stop() { runCatching { record.stop() } }
    fun release() { runCatching { record.release() } }
}
