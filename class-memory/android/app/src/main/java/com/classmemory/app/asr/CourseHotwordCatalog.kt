package com.classmemory.app.asr

import com.classmemory.app.i18n.tr

/** Course names and asset paths for the multi-course X-ASR hotword build. */
data class CourseHotwordCourse(val id: String, val title: String)

object CourseHotwordCatalog {
    val courses = listOf(
        CourseHotwordCourse("data_structure", tr("示例课程", "Example course")),
    )

    val defaultCourse: CourseHotwordCourse get() = courses.first()

    fun courseOrDefault(id: String?): CourseHotwordCourse =
        courses.firstOrNull { it.id == id } ?: defaultCourse

    fun parseTerms(contents: String): List<String> {
        val terms = contents.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(terms.isNotEmpty()) { "Course hotword asset is empty" }
        require(terms.distinct().size == terms.size) { "Course hotword asset contains duplicates" }
        require(terms.all { '/' !in it && '\n' !in it && '\r' !in it }) {
            "Course hotwords cannot contain slash or newline"
        }
        return terms
    }
}

data class AsrHotwordSessionSnapshot(
    val enabled: Boolean,
    val courseId: String,
    val courseName: String,
    val settings: AsrHotwordSettings,
)
