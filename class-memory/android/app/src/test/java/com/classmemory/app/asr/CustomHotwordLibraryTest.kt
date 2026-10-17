package com.classmemory.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomHotwordLibraryTest {
    @Test fun jsonExportRoundTripsContractWithoutInternalId() {
        val draft = CustomHotwordDraft("自定义课程", listOf("电场强度", "电势差"))
        val encoded = CustomHotwordLibraryCodec.encode(draft)
        val decoded = CustomHotwordLibraryCodec.decode(encoded)
        assertEquals(draft, decoded)
        val text = encoded.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertFalse(text.contains("\"id\""))
    }

    @Test fun txtImportRequiresNameAndParsesOneTermPerLine() {
        val bytes = "电场强度\r\n电势差\r\n".toByteArray(Charsets.UTF_8)
        assertEquals(
            CustomHotwordDraft("电磁学", listOf("电场强度", "电势差")),
            CustomHotwordLibraryCodec.decode(bytes, "电磁学"),
        )
    }

    @Test fun slashInDisplayNameIsAllowedButUnsafeTermMarkersAreRejected() {
        assertEquals("Java/C++", CustomHotwordLibraryCodec.validate(
            CustomHotwordDraft("Java/C++", listOf("JVM")),
        ).name)
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("自定义", listOf("a/b"))) }
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("自定义", listOf("<unk>"))) }
    }

    @Test fun rejectsMalformedJsonUnknownVersionAndNonStringTerms() {
        assertInvalid { CustomHotwordLibraryCodec.decode("{".toByteArray()) }
        assertInvalid { CustomHotwordLibraryCodec.decode(
            """{"schemaVersion":2,"name":"课程","terms":["词"]}""".toByteArray(),
        ) }
        assertInvalid { CustomHotwordLibraryCodec.decode(
            """{"schemaVersion":1,"name":"课程","terms":[{"bad":"object"}]}""".toByteArray(),
        ) }
        assertInvalid { CustomHotwordLibraryCodec.decode(
            """{"schemaVersion":1,"name":"课程","terms":["词"],"extra":1}""".toByteArray(),
        ) }
        assertInvalid { CustomHotwordLibraryCodec.decode(
            """{"schemaVersion":1,"name":"课程","terms":["词"]} trailing""".toByteArray(),
        ) }
    }

    @Test fun rejectsBadUtf8EmptyInputDuplicateWordsAndLimits() {
        assertInvalid { CustomHotwordLibraryCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertInvalid { CustomHotwordLibraryCodec.decode(byteArrayOf()) }
        assertInvalid { CustomHotwordLibraryCodec.decode("词\n词".toByteArray(), "课程") }
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("课程", emptyList())) }
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("课程", listOf("词", "词"))) }
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("课程", listOf("词".repeat(81)))) }
        assertInvalid { CustomHotwordLibraryCodec.validate(CustomHotwordDraft("", listOf("词"))) }
        assertInvalid { CustomHotwordLibraryCodec.decode(ByteArray(CustomHotwordLibraryCodec.MAX_INPUT_BYTES + 1)) }
    }

    @Test fun collectionRenameKeepsStableIdAndConflictsNeverOverwrite() {
        val id = "custom_123e4567-e89b-12d3-a456-426614174000"
        val original = CustomHotwordLibraryCollection.create("个人词库", listOf("电场"), id)
        val renamed = CustomHotwordLibraryCollection.save(listOf(original), original.copy(name = "物理自定义"))
        assertEquals(1, renamed.size)
        assertEquals(id, renamed.single().id)
        assertEquals("物理自定义", renamed.single().name)
        assertEquals(renamed, CustomHotwordLibraryCollection.decode(CustomHotwordLibraryCollection.encode(renamed)))
        assertInvalid {
            CustomHotwordLibraryCollection.save(
                renamed + CustomHotwordLibraryCollection.create("其他词库", listOf("磁通"), "custom_123e4567-e89b-12d3-a456-426614174001"),
                original.copy(name = "其他词库"),
            )
        }
        assertEquals("物理自定义", renamed.single().name)
    }

    @Test fun customProfileIsStableAndSnapshotCopiesTheTerms() {
        val library = CustomHotwordLibraryCollection.create(
            "个性课程", listOf("电场强度"), "custom_123e4567-e89b-12d3-a456-426614174000",
        )
        val terms = mutableListOf("电场强度")
        val snapshot = AsrHotwordConfiguration.resolveSelection(
            SelectedHotwordCourse(library.id, library.name, isCustom = true),
            enabled = true,
            terms = terms,
        )
        terms[0] = "外部修改"
        assertEquals(library.id, snapshot.settings.profile)
        assertEquals(library.id, snapshot.courseId)
        assertEquals("个性课程", snapshot.courseName)
        assertEquals(listOf("电场强度"), snapshot.settings.nativeHotwords)
        assertEquals(1.5f, snapshot.settings.hotwordsScore)
        assertEquals("modified_beam_search", snapshot.settings.decodingMethod)
        assertEquals(4, snapshot.settings.maxActivePaths)
        assertEquals("bpe", snapshot.settings.modelingUnit)
    }

    @Test fun customTermsUseBpePieceSegmentationInsteadOfCharacterOnlyChecks() {
        val pieces = CustomHotwordRepresentability.parseTokenLines("▁hello 1\nworld 2\n▁known 3\n")
        CustomHotwordRepresentability.requireRepresentable(listOf("helloworld"), pieces)
        assertEquals(listOf("unreachable"), CustomHotwordRepresentability.unsupportedFragments(listOf("unreachable"), pieces))
        assertInvalid { CustomHotwordRepresentability.requireRepresentable(listOf("unreachable"), pieces) }
    }

    @Test fun tokenLineParserAcceptsSpaceAndTabSeparators() {
        assertEquals(
            setOf("▁电场", "强度"),
            CustomHotwordRepresentability.parseTokenLines("▁电场 1\n强度\t2\n"),
        )
    }

    @Test fun customHotwordRepresentabilityUsesOnlyTokenAndBpeIntersection() {
        val tokens = CustomHotwordRepresentability.parseTokenLines(
            "▁hello 1\nworld 2\n▁data 3\nbase 4\n▁token_only 5\n",
        )
        val bpePieces = CustomHotwordRepresentability.parseTokenLines(
            "▁hello 10\nworld 20\n▁data 30\nbase 40\nbpe_only 50\n",
        )
        val supportedPieces = tokens intersect bpePieces

        CustomHotwordRepresentability.requireRepresentable(listOf("helloworld", "database"), supportedPieces)
        assertEquals(
            listOf("tokenonly"),
            CustomHotwordRepresentability.unsupportedFragments(listOf("tokenonly"), supportedPieces),
        )
        assertEquals(
            listOf("bpeonly"),
            CustomHotwordRepresentability.unsupportedFragments(listOf("bpeonly"), supportedPieces),
        )
        assertInvalid { CustomHotwordRepresentability.requireRepresentable(listOf("tokenonly"), supportedPieces) }
        assertInvalid { CustomHotwordRepresentability.requireRepresentable(listOf("bpeonly"), supportedPieces) }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid custom hotword input")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

}
