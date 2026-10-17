package com.classmemory.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun languageSelectionReturnsTheMatchingUiCopy() {
        assertEquals("Class details", selectAppLanguageText("课堂详情", "Class details", englishEnabled = true))
        assertEquals("课堂详情", selectAppLanguageText("课堂详情", "Class details", englishEnabled = false))
        assertEquals("Lecture", selectAppLanguageText("讲座", "Lecture", englishEnabled = true))
        assertEquals("讲座", selectAppLanguageText("讲座", "Lecture", englishEnabled = false))
    }
}
