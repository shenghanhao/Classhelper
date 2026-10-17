package com.classmemory.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomHotwordSeedTest {
    private val seed = CustomHotwordLibrary(
        id = CustomHotwordSeed.LIBRARY_ID,
        name = "Example library",
        terms = listOf("数据结构", "链表"),
    )

    @Test fun seedMergeIsIdempotentAndNeverSelectsOrEnablesItself() {
        val first = CustomHotwordSeed.merge(emptyList(), seed)
        val second = CustomHotwordSeed.merge(first.libraries, seed)
        assertEquals("added", first.status)
        assertEquals("existing_preserved", second.status)
        assertEquals(first.libraries, second.libraries)
        assertNull(first.selectedCourseId)
        assertFalse(first.enabled)
        assertNull(second.selectedCourseId)
        assertFalse(second.enabled)
    }

    @Test fun seedMergePreservesSameIdLibraryAfterUserRenameAndEdit() {
        val edited = seed.copy(name = "My example library", terms = listOf("自定义词", "课堂术语"))
        val result = CustomHotwordSeed.merge(listOf(edited), seed)
        assertEquals("existing_preserved", result.status)
        assertEquals(listOf(edited), result.libraries)
    }

    @Test fun seedNameConflictPreservesUserLibraryAndSelectionState() {
        val user = CustomHotwordLibraryCollection.create(
            seed.name,
            listOf("用户词条"),
            "custom_123e4567-e89b-12d3-a456-426614174000",
        )
        val result = CustomHotwordSeed.merge(listOf(user), seed, user.id, enabled = true)
        assertEquals("name_conflict", result.status)
        assertEquals(listOf(user), result.libraries)
        assertEquals(user.id, result.selectedCourseId)
        assertTrue(result.enabled)
    }
}
