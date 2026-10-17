/* Copyright (c) 2023 Xiaomi Corporation. SPDX-License-Identifier: Apache-2.0. */
package com.k2fsa.sherpa.onnx

class OfflineStream(var ptr: Long) {
    init { require(ptr != 0L) { "Failed to create native OfflineStream" } }
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) = acceptWaveform(ptr, samples, sampleRate)
    fun release() { if (ptr != 0L) { delete(ptr); ptr = 0L } }
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)
    companion object { init { System.loadLibrary("sherpa-onnx-jni") } }
}
