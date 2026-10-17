package com.classmemory.app.benchmark

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DebugAudioDecoderResamplerTest {
    @Test
    fun `48k mono emits exactly 16k samples per second`() = runTest {
        val input = ShortArray(48_000) { ((it * 31) % 30_000).toShort() }
        val output = resample(48_000, 1, input, intArrayOf(input.size))

        assertEquals(16_000, output.size)
    }

    @Test
    fun `input chunk boundaries do not change resampling result`() = runTest {
        val input = ShortArray(9_001) { ((it * 73) % 50_000 - 25_000).toShort() }

        val whole = resample(44_100, 1, input, intArrayOf(input.size))
        val chunked = resample(44_100, 1, input, intArrayOf(1, 37, 509, 3, 211))

        assertArrayEquals(whole, chunked)
    }

    private suspend fun resample(
        sampleRate: Int,
        channels: Int,
        interleaved: ShortArray,
        chunkFrames: IntArray,
    ): ShortArray {
        val result = ArrayList<Short>()
        val decoder = DebugAudioDecoder.StreamingResampler(sampleRate, channels) { result += it }
        var offset = 0
        var chunkIndex = 0
        while (offset < interleaved.size) {
            val shorts = minOf(chunkFrames[chunkIndex++ % chunkFrames.size] * channels, interleaved.size - offset)
            val bytes = ByteBuffer.allocate(shorts * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(shorts) { bytes.putShort(interleaved[offset + it]) }
            bytes.flip()
            decoder.accept(bytes, shorts)
            offset += shorts
        }
        decoder.finish()
        return result.toShortArray()
    }
}
