package com.classmemory.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrHotwordProfileTest {
    @Test fun noneKeepsBaselineGreedyConfigurationWithoutHotwords() {
        val profile = AsrHotwordSettings.forProfile("none")
        assertFalse(profile.enabled)
        assertFalse(profile.requiresBpeVocab)
        assertTrue(profile.nativeHotwords.isEmpty())
        assertEquals("greedy_search", profile.decodingMethod)
        assertEquals(0.0f, profile.hotwordsScore)
    }

    @Test fun dataStructureToggleOffResolvesToOriginalNoneConfiguration() {
        val disabled = AsrHotwordConfiguration.resolve("data_structure")
        val explicitlyDisabled = AsrHotwordConfiguration.resolve("data_structure", dataStructureEnabled = false)
        val baseline = AsrHotwordSettings.forProfile("none")
        assertEquals(baseline, disabled)
        assertEquals(baseline, explicitlyDisabled)
        assertFalse(disabled.enabled)
        assertFalse(disabled.requiresBpeVocab)
        assertEquals("greedy_search", disabled.decodingMethod)
        assertEquals(0.0f, disabled.hotwordsScore)
        assertEquals("", disabled.modelingUnit)
    }

    @Test fun dataStructureToggleOnUsesCurrentHotwordConfiguration() {
        val enabled = AsrHotwordConfiguration.resolve("data_structure", dataStructureEnabled = true)
        assertEquals(AsrHotwordSettings.forProfile("data_structure"), enabled)
    }

    @Test fun capturedSettingsRemainUsableAfterPreferenceChanges() {
        val sessionSnapshot = AsrHotwordConfiguration.resolve("data_structure", dataStructureEnabled = true)
        AsrHotwordConfiguration.resolve("data_structure", dataStructureEnabled = false)
        assertEquals("data_structure", sessionSnapshot.profile)
        assertEquals("modified_beam_search", sessionSnapshot.decodingMethod)
        assertEquals(70, sessionSnapshot.nativeHotwords.size)
    }

    @Test fun courseCatalogUsesOnlyAGenericExampleAndDoesNotRequirePersonalAssets() {
        assertEquals(1, CourseHotwordCatalog.courses.size)
        assertEquals("data_structure", CourseHotwordCatalog.defaultCourse.id)
        assertEquals("data_structure", CourseHotwordCatalog.courseOrDefault("unknown").id)
        assertEquals("data_structure", AsrHotwordConfiguration.resolveCourse("unknown", false, emptyList()).courseId)
    }

    @Test fun courseHotwordResolutionDefaultsOffAndKeepsSelection() {
        val disabled = AsrHotwordConfiguration.resolveCourse("data_structure", false, emptyList())
        assertEquals("data_structure", disabled.courseId)
        assertEquals(CourseHotwordCatalog.defaultCourse.title, disabled.courseName)
        assertEquals(AsrHotwordSettings.forProfile("none"), disabled.settings)

        val enabled = AsrHotwordConfiguration.resolveCourse("data_structure", true, listOf("矢量", "加速度"))
        assertTrue(enabled.enabled)
        assertEquals("data_structure", enabled.settings.profile)
        assertEquals(listOf("矢量", "加速度"), enabled.settings.nativeHotwords)
        assertEquals(1.5f, enabled.settings.hotwordsScore)
        assertEquals("modified_beam_search", enabled.settings.decodingMethod)
        assertEquals(4, enabled.settings.maxActivePaths)
        assertEquals("bpe", enabled.settings.modelingUnit)
    }

    @Test fun invalidCourseFallsBackToDataStructureAndSnapshotIsStable() {
        assertEquals("data_structure", CourseHotwordCatalog.courseOrDefault("invalid").id)
        val firstTerms = listOf("单链表", "指针域")
        val snapshot = AsrHotwordConfiguration.resolveCourse("data_structure", true, firstTerms)
        AsrHotwordConfiguration.resolveCourse("unknown", true, listOf("JVM"))
        assertEquals("data_structure", snapshot.courseId)
        assertEquals(CourseHotwordCatalog.defaultCourse.title, snapshot.courseName)
        assertEquals(firstTerms, snapshot.settings.nativeHotwords)
    }

    @Test fun toggleDoesNotChangeOtherProfiles() {
        assertEquals(
            AsrHotwordSettings.forProfile("computer_org"),
            AsrHotwordConfiguration.resolve("computer_org", dataStructureEnabled = false),
        )
        assertEquals(
            AsrHotwordSettings.forProfile("computer_org"),
            AsrHotwordConfiguration.resolve("computer_org", dataStructureEnabled = true),
        )
        assertEquals(
            AsrHotwordSettings.forProfile("none"),
            AsrHotwordConfiguration.resolve("none", dataStructureEnabled = true),
        )
        assertEquals(
            AsrHotwordSettings.forProfile("none"),
            AsrHotwordConfiguration.resolve("none", dataStructureEnabled = false),
        )
    }

    @Test fun computerOrgProfileHasConservativeBpeBeamSettingsAndUniqueTerms() {
        val profile = AsrHotwordSettings.forProfile("computer_org")
        assertTrue(profile.enabled)
        assertEquals("modified_beam_search", profile.decodingMethod)
        assertEquals(4, profile.maxActivePaths)
        assertEquals(1.5f, profile.hotwordsScore)
        assertEquals("bpe", profile.modelingUnit)
        assertTrue(profile.requiresBpeVocab)
        assertEquals(profile.nativeHotwords.size, profile.nativeHotwords.distinct().size)
        assertTrue(profile.nativeHotwords.all { it.isNotBlank() && '/' !in it && '\n' !in it && '\r' !in it })
        val tokenizerTerms = profile.tokenizerInput.split('/')
        assertEquals(profile.nativeHotwords.size, tokenizerTerms.size)
        assertTrue(tokenizerTerms.all { term ->
            term.zipWithNext().none { (first, second) -> first.isHan() && second.isHan() }
        })
        assertTrue(profile.nativeHotwords.none { it.contains("不是") || it.contains("一定") || it == "不" })
    }

    @Test fun dataStructureProfileUsesComparableBpeSettingsAndFocusedTerms() {
        val profile = AsrHotwordSettings.forProfile("data_structure")
        assertTrue(profile.enabled)
        assertEquals("modified_beam_search", profile.decodingMethod)
        assertEquals(4, profile.maxActivePaths)
        assertEquals(1.5f, profile.hotwordsScore)
        assertEquals("bpe", profile.modelingUnit)
        assertTrue(profile.requiresBpeVocab)
        assertTrue(profile.nativeHotwords.size in 50..70)
        assertEquals(profile.nativeHotwords.size, profile.nativeHotwords.distinct().size)
        assertTrue(profile.nativeHotwords.containsAll(listOf("单链表", "数据域", "指针域", "头指针", "头结点", "首元结点", "前驱结点", "后继结点", "头插法", "尾插法", "malloc", "free", "struct", "next", "NULL")))
        val tokenizerTerms = profile.tokenizerInput.split('/')
        assertEquals(profile.nativeHotwords.size, tokenizerTerms.size)
        assertTrue(tokenizerTerms.all { term ->
            term.zipWithNext().none { (first, second) -> first.isHan() && second.isHan() }
        })
        assertTrue(profile.nativeHotwords.none { it.contains("不是") || it.contains("一定") || it == "不" })
    }

    @Test fun dataStructureProfileIncludesRemainingClassTermsWithoutDuplicates() {
        val profile = AsrHotwordSettings.forProfile("data_structure")
        assertEquals(70, profile.nativeHotwords.size)
        assertEquals(70, profile.nativeHotwords.distinct().size)
        assertTrue(profile.nativeHotwords.containsAll(listOf(
            "结构体", "数组下标", "首地址", "遍历", "计数器", "赋值", "前插", "后插", "逆序读入",
        )))
        assertEquals(70, profile.tokenizerInput.split('/').size)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownProfileFails() { AsrHotwordSettings.forProfile("unknown") }

    @Test fun bpeHotwordsSeparateHanCharactersAndKeepEnglishTermsTogether() {
        assertEquals("中 央 处 理 器", AsrHotwordSettings.toTokenizerInput("中央处理器"))
        assertEquals("CPU", AsrHotwordSettings.toTokenizerInput("CPU"))
        assertEquals("指 令 Cache", AsrHotwordSettings.toTokenizerInput("指令Cache"))
        assertEquals("计 算 机 组 成 原 理", AsrHotwordSettings.toTokenizerInput("计算机组成原理"))
    }

    private fun Char.isHan() = this in '\u3400'..'\u4dbf' || this in '\u4e00'..'\u9fff'

}
