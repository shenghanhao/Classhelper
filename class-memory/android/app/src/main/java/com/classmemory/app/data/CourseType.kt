package com.classmemory.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.classmemory.app.i18n.tr

@Entity(tableName = "course_types")
data class CourseType(
    @PrimaryKey val name: String,
    val createdAt: Long,
)

val DEFAULT_COURSE_TYPES = listOf(
    "Lecture",
    "Lab",
    "Seminar",
)

fun displayCourseType(name: String): String = when (name) {
    "Lecture" -> tr("讲座", "Lecture")
    "Lab" -> tr("实验", "Lab")
    "Seminar" -> tr("研讨课", "Seminar")
    else -> name
}
