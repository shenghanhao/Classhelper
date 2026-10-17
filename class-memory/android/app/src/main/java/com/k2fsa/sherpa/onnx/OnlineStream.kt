/* Copyright (c) 2026 k2-fsa. SPDX-License-Identifier: Apache-2.0. */
package com.k2fsa.sherpa.onnx

class OnlineStream(var ptr: Long = 0L) {
    init { require(ptr != 0L) { "Failed to create native OnlineStream" } }
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) = acceptWaveform(ptr, samples, sampleRate)
    fun inputFinished() = inputFinished(ptr)
    fun release() { if (ptr != 0L) { delete(ptr); ptr = 0L } }
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)
    companion object { init { System.loadLibrary("sherpa-onnx-jni") } }
}
