package com.classmemory.app.data

/** Duration represented by accepted PCM samples, independent of ASR/DB processing time. */
fun audioDurationSec(samples: Long, sampleRate: Int): Long {
    require(samples >= 0) { "samples must be non-negative" }
    require(sampleRate > 0) { "sampleRate must be positive" }
    return samples / sampleRate
}
