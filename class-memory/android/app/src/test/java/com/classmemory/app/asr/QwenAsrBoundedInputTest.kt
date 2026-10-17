package com.classmemory.app.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class QwenAsrBoundedInputTest {
    @Test
    fun vadInputWindowsNeverExceedSileroWindow() {
        assertEquals(512, qwenVadChunkSize(20_000))
        assertEquals(8, qwenVadChunkSize(8))
        assertEquals(0, qwenVadChunkSize(-1))
    }

    @Test
    fun receivedSampleClockAdvancesByEveryVadWindow() {
        assertEquals(100_512L, qwenVadReceivedThroughSample(100_000L, 512))
        assertEquals(100_000L, qwenVadReceivedThroughSample(100_000L, -1))
    }

    @Test
    fun segmentTimestampsUseAbsoluteSourceSampleClock() {
        assertEquals(
            QwenSegmentSampleRange(startSample = 100_300, endSample = 100_900),
            qwenSegmentSampleRange(100_000, 300, 600, 101_000),
        )
    }

    @Test
    fun segmentEndDoesNotExtendPastReceivedAudio() {
        assertEquals(
            QwenSegmentSampleRange(startSample = 100_300, endSample = 100_500),
            qwenSegmentSampleRange(100_000, 300, 600, 100_500),
        )
    }
}
