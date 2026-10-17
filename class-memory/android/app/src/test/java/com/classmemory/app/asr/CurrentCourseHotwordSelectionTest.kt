package com.classmemory.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentCourseHotwordSelectionTest {
    @Test fun retiredPresetIdsAndUnselectedInstallNormalizeToNoneAndOff() {
        listOf("data_structure", "college_physics", "computer_org").forEach { retiredId ->
            val normalized = CurrentCourseHotwordSelection.normalize(retiredId, enabled = true)
            assertEquals(null, normalized.selectedCourseId)
            assertFalse(normalized.enabled)
            assertTrue(normalized.changed)
        }
        val fresh = CurrentCourseHotwordSelection.normalize(null, enabled = false)
        assertEquals(null, fresh.selectedCourseId)
        assertFalse(fresh.enabled)
        assertFalse(fresh.changed)
    }

    @Test fun selectedCustomLibraryAndItsEnabledStateArePreserved() {
        val id = "custom_123e4567-e89b-12d3-a456-426614174000"
        val normalized = CurrentCourseHotwordSelection.normalize(id, enabled = true)
        assertEquals(id, normalized.selectedCourseId)
        assertTrue(normalized.enabled)
        assertFalse(normalized.changed)
        val disabled = CurrentCourseHotwordSelection.normalize(id, enabled = false)
        assertEquals(id, disabled.selectedCourseId)
        assertFalse(disabled.enabled)
        assertFalse(disabled.changed)
        assertTrue(CurrentCourseHotwordSelection.canEnable(id, customLibraryExists = true, termCount = 2))
        assertFalse(CurrentCourseHotwordSelection.canEnable(id, customLibraryExists = false, termCount = 2))
        assertFalse(CurrentCourseHotwordSelection.canEnable(id, customLibraryExists = true, termCount = 0))
        assertFalse(CurrentCourseHotwordSelection.canEnable(null, customLibraryExists = true, termCount = 2))
    }

    @Test fun emptyDisabledSelectionResolvesToFullNoneConfiguration() {
        val snapshot = AsrHotwordConfiguration.resolveCurrentSelection(SelectedHotwordCourse.NONE, false, emptyList())
        assertFalse(snapshot.enabled)
        assertEquals(AsrHotwordSettings.NONE, snapshot.settings.profile)
        assertEquals(emptyList<String>(), snapshot.settings.nativeHotwords)
        assertEquals("greedy_search", snapshot.settings.decodingMethod)
        assertEquals(0.0f, snapshot.settings.hotwordsScore)
        assertEquals("未选择词库", snapshot.courseName)
    }

    @Test fun enablingWithoutASelectedCustomLibraryIsRejected() {
        try {
            AsrHotwordConfiguration.resolveCurrentSelection(SelectedHotwordCourse.NONE, true, emptyList())
            throw AssertionError("Expected enabling without a custom library to fail")
        } catch (_: IllegalArgumentException) {
            // Expected: never silently replace the missing selection with a preset.
        }
    }

    @Test fun selectedCustomSnapshotKeepsItsIdentityAndCopiesItsWords() {
        val id = "custom_123e4567-e89b-12d3-a456-426614174000"
        val mutableTerms = mutableListOf("单链表", "malloc")
        val snapshot = AsrHotwordConfiguration.resolveCurrentSelection(
            SelectedHotwordCourse(id, "自定义数据结构", isCustom = true), true, mutableTerms,
        )
        mutableTerms[0] = "外部变更"
        assertEquals(id, snapshot.courseId)
        assertEquals("自定义数据结构", snapshot.courseName)
        assertEquals(id, snapshot.settings.profile)
        assertEquals(listOf("单链表", "malloc"), snapshot.settings.nativeHotwords)
        assertEquals(1.5f, snapshot.settings.hotwordsScore)
        assertEquals("modified_beam_search", snapshot.settings.decodingMethod)
        assertEquals(4, snapshot.settings.maxActivePaths)
        assertEquals("bpe", snapshot.settings.modelingUnit)
    }
}
