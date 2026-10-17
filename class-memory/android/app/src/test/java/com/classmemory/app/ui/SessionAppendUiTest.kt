package com.classmemory.app.ui

import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAppendUiTest {
    @Test
    fun candidatesExcludeTheCurrentSessionAndRecordingSessions() {
        val source = session("a", title = "同名", type = "数学")
        val other = session("b", title = "其他", type = "物理")
        val recording = session("c", title = "正在记录", status = SessionStatus.recording)

        assertEquals(listOf("b"), appendCandidates(listOf(source, other, recording), "a").map { it.id })
    }

    @Test
    fun candidatesCanComeFromAnotherCourseTypeAndKeepSameNameDistinctById() {
        val a = session("a", title = "复习", type = "数学")
        val b1 = session("b1", title = "复习", type = "物理", startedAt = 10)
        val b2 = session("b2", title = "复习", type = "化学", startedAt = 20)

        val candidates = appendCandidates(listOf(a, b1, b2), "a")
        assertEquals(listOf("b1", "b2"), candidates.map { it.id })
        assertTrue(appendSessionDisplayLabel(b1).contains("物理"))
        assertTrue(appendSessionDisplayLabel(b2).contains("化学"))
    }

    @Test
    fun confirmationLabelMakesAppendDirectionExplicitAndReportsStaleItems() {
        val a = session("a", title = "A课", type = "数学")
        val b = session("b", title = "B课", type = "物理")

        val label = appendDirectionLabel(a, b)
        assertTrue(label.startsWith("A: A课"))
        assertTrue(label.contains("→ B: B课"))
        assertTrue(appendDirectionLabel(a, null).contains("no longer exists"))
        assertFalse(appendDirectionLabel(a, session("recording", status = SessionStatus.recording)).contains("→"))
        assertTrue(appendDirectionLabel(a, a).contains("itself"))
    }

    private fun session(
        id: String,
        title: String? = null,
        type: String? = null,
        startedAt: Long = 0,
        status: SessionStatus = SessionStatus.completed,
    ) = Session(id = id, startedAt = startedAt, title = title, courseType = type, status = status)
}
