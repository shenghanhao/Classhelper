package com.classmemory.app.asr

import android.content.Context
import com.classmemory.app.BuildConfig
import com.classmemory.app.i18n.tr

/** Pure merge helper for a synthetic sample library; public builds do not install bundled data. */
object CustomHotwordSeed {
    const val LIBRARY_ID = "custom_00000000-0000-4000-8000-000000000001"

    data class MergeResult(
        val libraries: List<CustomHotwordLibrary>,
        val status: String,
        val changed: Boolean,
        val selectedCourseId: String?,
        val enabled: Boolean,
    )

    /** A seed ID or name collision is never allowed to replace user data. */
    fun merge(
        existing: List<CustomHotwordLibrary>,
        seed: CustomHotwordLibrary,
        selectedCourseId: String? = null,
        enabled: Boolean = false,
    ): MergeResult {
        require(seed.id == LIBRARY_ID) { tr("种子词库标识不匹配", "Seed library ID does not match") }
        if (existing.any { it.id == seed.id }) {
            return MergeResult(existing.toList(), "existing_preserved", false, selectedCourseId, enabled)
        }
        if (existing.any { it.name.equals(seed.name, ignoreCase = true) }) {
            return MergeResult(existing.toList(), "name_conflict", false, selectedCourseId, enabled)
        }
        return MergeResult(CustomHotwordLibraryCollection.save(existing, seed), "added", true, selectedCourseId, enabled)
    }

    /** The publish source does not package or install a bundled seed library. */
    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context) {
        if (BuildConfig.PUBLIC_BUILD) return
    }

    @Suppress("UNUSED_PARAMETER")
    fun statusMessage(context: Context): String? = null
}
