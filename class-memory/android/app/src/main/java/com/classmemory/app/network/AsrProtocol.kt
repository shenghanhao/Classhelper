package com.classmemory.app.network

import com.classmemory.app.i18n.tr

data class AsrSegment(val startSec: Double, val endSec: Double, val text: String, val completed: Boolean)
sealed interface ServerMessage { data object Ready : ServerMessage; data class Wait(val message: String) : ServerMessage; data class Error(val message: String) : ServerMessage; data class Segments(val items: List<AsrSegment>) : ServerMessage }

object AsrProtocol {
    const val READY = "SERVER_READY"
    val END_OF_AUDIO = "END_OF_AUDIO".encodeToByteArray()
    fun hello(uid: String) = "{\"uid\":\"${uid.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"language\":\"zh\",\"task\":\"transcribe\",\"model\":\"small\",\"use_vad\":true,\"audio_format\":\"int16\",\"same_output_threshold\":0}"
    fun parseServerMessage(text: String, expectedUid: String): ServerMessage {
        val root = org.json.JSONObject(text)
        require(root.optString("uid") == expectedUid) { tr("响应会话不匹配", "Response session does not match") }
        val status = root.optString("status").uppercase()
        val message = if (root.has("message")) root.get("message").toString() else ""
        when (status) { "WAIT" -> return ServerMessage.Wait(message); "ERROR" -> return ServerMessage.Error(message.ifBlank { tr("服务器错误", "Server error") }) }
        if (message == READY) return ServerMessage.Ready
        val segments = root.optJSONArray("segments") ?: throw IllegalArgumentException(tr("服务器响应缺少 segments", "Server response is missing segments"))
        val parsed = buildList(segments.length()) { for (index in 0 until segments.length()) { val segment = segments.getJSONObject(index); val start = segment.getString("start").toDoubleOrNull(); val end = segment.getString("end").toDoubleOrNull(); require(start != null && end != null && start.isFinite() && end.isFinite()) { tr("服务器时间无效", "Invalid server timestamp") }; require(start >= 0.0 && end >= start) { tr("服务器时间范围无效", "Invalid server time range") }; add(AsrSegment(start, end, segment.optString("text"), segment.optBoolean("completed"))) } }
        return ServerMessage.Segments(parsed)
    }
    fun backoffMillis(attempt: Int): Long = ((1L shl attempt.coerceIn(0, 4)) * 1_000L).coerceAtMost(10_000L)
    fun sessionMillis(connectionOffsetMs: Long, seconds: Double): Long = connectionOffsetMs + (seconds * 1000).toLong()
}
