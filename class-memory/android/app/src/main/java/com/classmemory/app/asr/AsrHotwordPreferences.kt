package com.classmemory.app.asr

import android.content.Context
import com.classmemory.app.BuildConfig
import com.classmemory.app.i18n.tr

/** User choice and per-session configuration for the data-structure X-ASR build. */
object AsrHotwordPreferences {
    private const val PREFERENCES = "asr_hotword_settings"
    private const val DATA_STRUCTURE_ENABLED = "data_structure_enabled"
    private const val SELECTED_COURSE_ID = "selected_course_id"

    fun supportsCourseHotwords(): Boolean =
        BuildConfig.ASR_MODEL_ID == "xasr" && BuildConfig.ASR_HOTWORD_PROFILE == AsrHotwordSettings.DATA_STRUCTURE

    fun supportsDataStructureToggle(): Boolean = supportsCourseHotwords()

    fun normalizeCurrentCoursePreferences(context: Context) {
        if (!supportsCourseHotwords()) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        synchronized(CustomHotwordLibraryPreferences.lock) {
            val normalized = CurrentCourseHotwordSelection.normalize(
                preferences.getString(SELECTED_COURSE_ID, null),
                preferences.getBoolean(DATA_STRUCTURE_ENABLED, false),
            )
            if (normalized.changed) {
                val editor = preferences.edit().putBoolean(DATA_STRUCTURE_ENABLED, normalized.enabled)
                if (normalized.selectedCourseId == null) editor.remove(SELECTED_COURSE_ID)
                else editor.putString(SELECTED_COURSE_ID, normalized.selectedCourseId)
                editor.commit()
            }
        }
    }

    fun isDataStructureEnabled(context: Context): Boolean {
        normalizeCurrentCoursePreferences(context)
        return synchronized(CustomHotwordLibraryPreferences.lock) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(DATA_STRUCTURE_ENABLED, false)
        }
    }

    /** Returns false when enabling was requested without a valid, non-empty custom library. */
    fun setDataStructureEnabled(context: Context, enabled: Boolean): Boolean {
        normalizeCurrentCoursePreferences(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        synchronized(CustomHotwordLibraryPreferences.lock) {
            if (enabled && !canEnableDataStructureHotwords(context)) return false
            return preferences.edit().putBoolean(DATA_STRUCTURE_ENABLED, enabled).commit()
        }
    }

    fun canEnableDataStructureHotwords(context: Context): Boolean {
        if (!supportsCourseHotwords()) return false
        return synchronized(CustomHotwordLibraryPreferences.lock) {
            normalizeCurrentCoursePreferences(context)
            val id = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(SELECTED_COURSE_ID, null)
            val library = if (id?.startsWith("custom_") == true) {
                runCatching { CustomHotwordLibraryPreferences.get(context, id) }.getOrNull()
            } else null
            CurrentCourseHotwordSelection.canEnable(id, library != null, library?.terms?.size ?: 0)
        }
    }

    fun selectedHotwordCourse(context: Context, tolerateUnreadableCustom: Boolean = false): SelectedHotwordCourse {
        return synchronized(CustomHotwordLibraryPreferences.lock) {
            normalizeCurrentCoursePreferences(context)
            val id = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(SELECTED_COURSE_ID, null)
            if (id?.startsWith("custom_") == true) {
                val custom = try {
                    CustomHotwordLibraryPreferences.get(context, id)
                } catch (error: Exception) {
                    if (!tolerateUnreadableCustom) throw error
                    null
                }
                if (custom != null) return@synchronized SelectedHotwordCourse(custom.id, custom.name, isCustom = true)
                if (tolerateUnreadableCustom) return@synchronized SelectedHotwordCourse(id, tr("自定义课程（配置不可读）", "Custom course (configuration unreadable)"), isCustom = true)
                throw IllegalArgumentException(tr("所选自定义课程词库不存在", "Selected custom course library does not exist"))
            }
            SelectedHotwordCourse.NONE
        }
    }

    fun selectHotwordCourse(context: Context, courseId: String) = CustomHotwordLibraryPreferences.select(context, courseId)

    fun snapshot(context: Context): AsrHotwordSessionSnapshot {
        if (supportsCourseHotwords()) {
            return synchronized(CustomHotwordLibraryPreferences.lock) {
                normalizeCurrentCoursePreferences(context)
                val enabled = isDataStructureEnabled(context)
                val course = selectedHotwordCourse(context, tolerateUnreadableCustom = !enabled)
                if (!enabled) return@synchronized AsrHotwordConfiguration.resolveCurrentSelection(course, false, emptyList())
                require(course.isCustom && course.id.startsWith("custom_")) {
                    tr("请先新建或导入并选择一个自定义词库", "Create or import a custom library, then select it")
                }
                val terms = CustomHotwordLibraryPreferences.get(context, course.id)?.terms
                    ?: throw IllegalArgumentException(tr("所选自定义课程词库不存在", "Selected custom course library does not exist"))
                require(terms.isNotEmpty()) { tr("所选自定义课程词库为空，请重新编辑", "Selected custom course library is empty. Edit it and try again.") }
                CustomHotwordRepresentability.requireRepresentable(terms, CustomHotwordLibraryPreferences.readSupportedTokens(context))
                AsrHotwordConfiguration.resolveCurrentSelection(course, true, terms)
            }
        }
        val settings = AsrHotwordSettings.forProfile(BuildConfig.ASR_HOTWORD_PROFILE)
        val course = CourseHotwordCatalog.courses.firstOrNull { it.id == settings.profile }
        return AsrHotwordSessionSnapshot(
            settings.enabled,
            course?.id ?: settings.profile,
            course?.title.orEmpty(),
            settings,
        )
    }
}

data class NormalizedCourseHotwordSelection(
    val selectedCourseId: String?,
    val enabled: Boolean,
    val changed: Boolean,
)

/** Pure selection rules for this build after removing the bundled preset courses. */
object CurrentCourseHotwordSelection {
    fun normalize(selectedCourseId: String?, enabled: Boolean): NormalizedCourseHotwordSelection {
        if (selectedCourseId?.startsWith("custom_") == true) {
            return NormalizedCourseHotwordSelection(selectedCourseId, enabled, changed = false)
        }
        val changed = selectedCourseId != null || enabled
        return NormalizedCourseHotwordSelection(null, false, changed)
    }

    fun canEnable(selectedCourseId: String?, customLibraryExists: Boolean, termCount: Int): Boolean =
        selectedCourseId?.startsWith("custom_") == true && customLibraryExists && termCount > 0
}

/** Pure resolver so each service can capture an immutable configuration at session start. */
object AsrHotwordConfiguration {
    fun resolve(profile: String, dataStructureEnabled: Boolean? = null): AsrHotwordSettings = when {
        profile == AsrHotwordSettings.DATA_STRUCTURE && dataStructureEnabled != true ->
            AsrHotwordSettings.forProfile(AsrHotwordSettings.NONE)
        else -> AsrHotwordSettings.forProfile(profile)
    }

    fun resolveCourse(courseId: String?, enabled: Boolean, terms: List<String>): AsrHotwordSessionSnapshot {
        val course = CourseHotwordCatalog.courseOrDefault(courseId)
        return resolveSelection(SelectedHotwordCourse(course.id, course.title, false), enabled, terms)
    }

    fun resolveCurrentSelection(course: SelectedHotwordCourse, enabled: Boolean, terms: List<String>): AsrHotwordSessionSnapshot {
        if (!enabled) {
            val noneCourse = if (course.id.startsWith("custom_")) course else SelectedHotwordCourse.NONE
            return AsrHotwordSessionSnapshot(false, noneCourse.id, noneCourse.name, AsrHotwordSettings.forProfile(AsrHotwordSettings.NONE))
        }
        require(course.isCustom && course.id.startsWith("custom_")) {
            tr("必须选择自定义热词库", "A custom hotword library must be selected")
        }
        require(terms.isNotEmpty()) { tr("启用的自定义热词不能为空", "Enabled custom hotwords cannot be empty") }
        return resolveSelection(course, true, terms)
    }

    fun resolveSelection(course: SelectedHotwordCourse, enabled: Boolean, terms: List<String>): AsrHotwordSessionSnapshot {
        val settings = if (enabled) {
            require(terms.isNotEmpty()) { tr("启用的课程热词不能为空", "Enabled course hotwords cannot be empty") }
            require(terms.distinct().size == terms.size) { tr("课程热词不能重复", "Course hotwords must be unique") }
            require(terms.all { it.isNotBlank() && '/' !in it && '\n' !in it && '\r' !in it }) {
                tr("课程热词不能为空或包含斜线、换行符", "Course hotwords must be non-empty and cannot contain slash or newline")
            }
            AsrHotwordSettings(course.id, terms.toList(), 1.5f, "modified_beam_search", 4, "bpe")
        } else {
            AsrHotwordSettings.forProfile(AsrHotwordSettings.NONE)
        }
        return AsrHotwordSessionSnapshot(enabled, course.id, course.name, settings)
    }
}
