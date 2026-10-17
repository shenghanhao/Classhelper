package com.classmemory.app.benchmark

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Auditable benchmark aggregate.  It intentionally contains timing and memory samples only;
 * decoded audio is never retained here.
 */
class BenchmarkMetrics(private val sampleRate: Int = 16_000) {
    private var inputSamples = 0L
    private var asrNanos = 0L
    private var characters = 0
    private var segments = 0
    private var segmentDurationSamples = 0L
    private var maxBacklogSamples = 0L
    private var errors = 0
    private var maxQueuedFrames = 0
    private var droppedFrames = 0L
    private var peakJavaBytes = 0L
    private var peakNativeBytes = 0L
    private var peakPssBytes = 0L
    private val memoryWindows = mutableListOf<MemoryWindow>()

    data class MemoryWindow(
        val elapsedSec: Double,
        val javaBytes: Long,
        val nativeBytes: Long,
        val pssBytes: Long,
        val inputSamples: Long,
        val pacingNanos: Long,
    )

    @Synchronized fun input(count: Int) {
        require(count >= 0)
        inputSamples += count
    }

    @Synchronized fun asrDuration(nanos: Long) {
        asrNanos = max(asrNanos, nanos)
    }

    @Synchronized fun final(text: String, startSample: Long, endSample: Long) {
        characters += text.length
        segments++
        segmentDurationSamples += (endSample - startSample).coerceAtLeast(0)
    }

    /**
     * [plannedSamples] is driven by the realtime sample clock, not producer progress.  This is
     * deliberately callable from a timer while the producer is blocked, so serial backpressure
     * cannot make the reported backlog look like zero.
     */
    @Synchronized fun backlog(plannedSamples: Long, completedSamples: Long) {
        maxBacklogSamples = max(maxBacklogSamples, (plannedSamples - completedSamples).coerceAtLeast(0))
    }

    @Synchronized fun error() { errors++ }

    /** The import runner uses a bounded PCM queue and never drops frames during a normal run. */
    @Synchronized fun queueDepth(depth: Int) { maxQueuedFrames = max(maxQueuedFrames, depth) }

    @Synchronized fun droppedFrames(count: Long) { droppedFrames += count }

    @Synchronized fun hasTranscript(): Boolean = segments > 0 && characters > 0

    @Synchronized fun hasErrors(): Boolean = errors > 0

    @Synchronized fun memory(
        elapsedNanos: Long,
        javaBytes: Long,
        nativeBytes: Long,
        pssBytes: Long,
        pacingNanos: Long,
    ) {
        peakJavaBytes = max(peakJavaBytes, javaBytes)
        peakNativeBytes = max(peakNativeBytes, nativeBytes)
        peakPssBytes = max(peakPssBytes, pssBytes)
        memoryWindows += MemoryWindow(elapsedNanos / 1e9, javaBytes, nativeBytes, pssBytes, inputSamples, pacingNanos)
    }

    @Synchronized
    fun json(
        mode: String,
        outcome: String,
        passed: Boolean,
        wallNanos: Long,
        pacingNanos: Long,
        initializationNanos: Long,
        queueCapacityFrames: Int,
    ): String {
        // This is wall-clock pipeline time minus deliberate realtime sleeping. It includes
        // decoder, ASR and synchronous Room-final commits, without double-counting overlaps.
        val processingNanos = (wallNanos - pacingNanos).coerceAtLeast(0)
        val audioDuration = inputSamples.toDouble() / sampleRate
        val windows = JSONArray()
        val throughputWindows = JSONArray()
        memoryWindows.forEach { sample ->
            windows.put(JSONObject().apply {
                put("elapsedSec", sample.elapsedSec)
                put("javaBytes", sample.javaBytes)
                put("nativeHeapBytes", sample.nativeBytes)
                put("pssBytes", sample.pssBytes)
            })
        }
        memoryWindows.zipWithNext().forEach { (previous, current) ->
            val audioSec = (current.inputSamples - previous.inputSamples).toDouble() / sampleRate
            val activeProcessingSec = ((current.elapsedSec - previous.elapsedSec) -
                (current.pacingNanos - previous.pacingNanos) / 1e9).coerceAtLeast(0.0)
            throughputWindows.put(JSONObject().apply {
                put("endElapsedSec", current.elapsedSec)
                put("audioDurationSec", audioSec)
                put("processingDurationSec", activeProcessingSec)
                put("RTF", if (audioSec == 0.0) JSONObject.NULL else activeProcessingSec / audioSec)
                put("audioSecPerProcessingSec", if (activeProcessingSec == 0.0) JSONObject.NULL else audioSec / activeProcessingSec)
            })
        }
        return JSONObject().apply {
            put("mode", mode)
            put("outcome", outcome)
            put("passed", passed)
            put("sampleRateHz", sampleRate)
            put("audioDurationSec", audioDuration)
            put("processingDurationSec", processingNanos / 1e9)
            put("initializationDurationSec", initializationNanos / 1e9)
            put("asrDurationSec", asrNanos / 1e9)
            val rtf = if (inputSamples == 0L) JSONObject.NULL else processingNanos / 1e9 / audioDuration
            put("RTF", rtf)
            put("rtf", rtf) // Compatibility alias for the first debug skeleton.
            put("wallDurationSec", wallNanos / 1e9)
            put("pacingWaitSec", pacingNanos / 1e9)
            put("transcriptCharacters", characters)
            put("transcriptSegments", segments)
            val averageSegmentDuration = if (segments == 0) JSONObject.NULL else segmentDurationSamples.toDouble() / segments / sampleRate
            put("averageSegmentDuration", averageSegmentDuration)
            put("averageSegmentDurationSec", averageSegmentDuration)
            put("maxProcessingBacklogSec", maxBacklogSamples.toDouble() / sampleRate)
            put("ASRErrorCount", errors)
            put("asrErrorCount", errors)
            put("queueCapacityFrames", queueCapacityFrames)
            put("maxQueuedFrames", maxQueuedFrames)
            put("droppedFrames", droppedFrames)
            put("peakMemory", JSONObject().apply {
                put("javaBytes", peakJavaBytes)
                put("nativeHeapBytes", peakNativeBytes)
                put("pssBytes", peakPssBytes)
                put("sampleIntervalSec", 5)
                put("note", "Sampled process memory; instantaneous peaks between samples are not claimed.")
            })
            put("memoryWindows", windows)
            put("throughputWindows", throughputWindows)
            put("backlogDefinition", "planned realtime audio sample clock minus samples accepted and committed through ASR")
            put("processingDefinition", "wall-clock pipeline time excluding deliberate realtime pacing; includes decoder, ASR and final Room commits")
        }.toString(2)
    }
}
