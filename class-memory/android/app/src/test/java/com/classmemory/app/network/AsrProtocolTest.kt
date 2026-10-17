package com.classmemory.app.network

import com.classmemory.app.repository.Dedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AsrProtocolTest {
    @Test
    fun helloContainsRequiredWhisperLiveOptions() {
        val hello = AsrProtocol.hello("test-user")

        assertTrue(hello.contains("\"uid\":\"test-user\""))
        assertTrue(hello.contains("\"language\":\"zh\""))
        assertTrue(hello.contains("\"audio_format\":\"int16\""))
        assertTrue(hello.contains("\"use_vad\":true"))
        assertTrue(hello.contains("\"same_output_threshold\":0"))
    }

    @Test
    fun reconnectBackoffIsBoundedBetweenOneAndTenSeconds() {
        assertEquals(1_000L, AsrProtocol.backoffMillis(0))
        assertEquals(2_000L, AsrProtocol.backoffMillis(1))
        assertEquals(10_000L, AsrProtocol.backoffMillis(4))
        assertEquals(10_000L, AsrProtocol.backoffMillis(99))
    }

    @Test
    fun serverSegmentTimeUsesConnectionOffset() {
        assertEquals(12_345L, AsrProtocol.sessionMillis(10_000L, 2.345))
    }

    @Test
    fun dedupeKeyNormalizesTranscriptWhitespaceButKeepsGenerationAndTimes() {
        val first = Dedupe.segmentKey(2L, 100L, 300L, "  hello   world ")
        val same = Dedupe.segmentKey(2L, 100L, 300L, "hello world")
        val otherGeneration = Dedupe.segmentKey(3L, 100L, 300L, "hello world")

        assertEquals(first, same)
        assertTrue(first != otherGeneration)
    }

    @Test
    fun parsesReadyWaitErrorAndPreservesPartialFinalFlag() {
        assertEquals(ServerMessage.Ready, AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"message\":\"SERVER_READY\"}", "x"))
        assertEquals(ServerMessage.Wait("0.5"), AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"status\":\"WAIT\",\"message\":0.5}", "x"))
        assertEquals(ServerMessage.Error("reason"), AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"status\":\"ERROR\",\"message\":\"reason\"}", "x"))
        val segments = (AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"segments\":[{\"start\":\"1.0\",\"end\":\"2.0\",\"text\":\"partial\",\"completed\":false},{\"start\":\"2.0\",\"end\":\"3.0\",\"text\":\"final\",\"completed\":true}]}", "x") as ServerMessage.Segments).items
        assertEquals(listOf(false, true), segments.map { it.completed })
    }

    @Test
    fun rejectsMismatchedUidAndInvalidTimes() {
        expectInvalid { AsrProtocol.parseServerMessage("{\"uid\":\"other\",\"message\":\"SERVER_READY\"}", "x") }
        expectInvalid { AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"segments\":[{\"start\":\"NaN\",\"end\":\"2\",\"text\":\"bad\",\"completed\":true}]}", "x") }
        expectInvalid { AsrProtocol.parseServerMessage("{\"uid\":\"x\",\"segments\":[{\"start\":\"3\",\"end\":\"2\",\"text\":\"bad\",\"completed\":true}]}", "x") }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("expected invalid server response") } catch (_: IllegalArgumentException) { }
    }
}
