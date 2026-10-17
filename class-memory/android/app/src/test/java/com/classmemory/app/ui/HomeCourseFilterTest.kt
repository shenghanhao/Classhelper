package com.classmemory.app.ui

import com.classmemory.app.data.Session
import com.classmemory.app.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCourseFilterTest {
    private val sessions = listOf(
        session("complex", "复变函数与积分变换Ⅱ"),
        session("physics", "大学物理ⅠB"),
        session("other-physics", "大学物理ⅠB"),
        session("uncategorized", null),
    )

    @Test
    fun allFilterKeepsEverySession() {
        assertEquals(sessions, filterSessions(sessions, CourseFilter.All))
    }

    @Test
    fun taggedFilterKeepsOnlyMatchingCourseType() {
        assertEquals(
            listOf("physics", "other-physics"),
            filterSessions(sessions, CourseFilter.Tagged("大学物理ⅠB")).map { it.id },
        )
    }

    @Test
    fun unclassifiedFilterKeepsOnlySessionsWithoutType() {
        assertEquals(
            listOf("uncategorized"),
            filterSessions(sessions, CourseFilter.Unclassified).map { it.id },
        )
    }

    @Test
    fun organizedStatusLabel_reflectsOrganizedState() {
        assertEquals("Organized", organizedStatusLabel(true))
        assertEquals("Not organized", organizedStatusLabel(false))
    }

    private fun session(id: String, courseType: String?) = Session(
        id = id,
        startedAt = 0,
        courseType = courseType,
        status = SessionStatus.completed,
    )
}
