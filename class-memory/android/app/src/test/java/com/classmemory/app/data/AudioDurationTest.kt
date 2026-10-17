package com.classmemory.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDurationTest {
    @Test
    fun durationUsesAcceptedAudioSamplesInsteadOfProcessingClock() {
        assertEquals(8L, audioDurationSec(8 * 16_000L + 15_999L, 16_000))
        assertEquals(0L, audioDurationSec(15_999L, 16_000))
    }
}
