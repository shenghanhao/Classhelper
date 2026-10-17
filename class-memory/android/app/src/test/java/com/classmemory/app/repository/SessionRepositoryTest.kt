package com.classmemory.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun normalizeSessionTitle_trimsAndCollapsesWhitespace() {
        assertEquals("理论力学 第一讲", normalizeSessionTitle("  理论力学  \n 第一讲  "))
    }

    @Test
    fun normalizeSessionTitle_rejectsBlankTitle() {
        assertThrows(IllegalArgumentException::class.java) { normalizeSessionTitle(" \n ") }
    }

    @Test
    fun normalizeSessionTitle_rejectsMoreThanSixtyCharacters() {
        assertThrows(IllegalArgumentException::class.java) { normalizeSessionTitle("课".repeat(61)) }
    }

    @Test
    fun normalizeCourseType_trimsAndCollapsesWhitespace() {
        assertEquals("大学物理ⅠB", normalizeCourseType("  大学物理ⅠB  \n"))
    }

    @Test
    fun normalizeCourseType_rejectsBlankValue() {
        assertThrows(IllegalArgumentException::class.java) { normalizeCourseType(" \n ") }
    }

    @Test fun normalizeClassroomNote_preservesMultilineAndNormalizesLineEndings() {
        assertEquals("疑点一\n疑点二", normalizeClassroomNote("  疑点一\r\n疑点二  "))
    }

    @Test fun normalizeClassroomNote_rejectsBlankAndOverLimitText() {
        assertThrows(IllegalArgumentException::class.java) { normalizeClassroomNote(" \n ") }
        assertThrows(IllegalArgumentException::class.java) { normalizeClassroomNote("疑".repeat(2_001)) }
    }

    @Test fun boardPhotoPath_requiresSessionScopedSafeRelativePath() {
        org.junit.Assert.assertTrue(isSafeBoardPhotoPath("lesson-1", "board-photos/lesson-1/photo.jpg"))
        org.junit.Assert.assertFalse(isSafeBoardPhotoPath("lesson-1", "board-photos/lesson-1/../other.jpg"))
        org.junit.Assert.assertFalse(isSafeBoardPhotoPath("lesson-1", "board-photos/other/photo.jpg"))
        org.junit.Assert.assertFalse(isSafeBoardPhotoPath("../other", "board-photos/../other/photo.jpg"))
    }
}
