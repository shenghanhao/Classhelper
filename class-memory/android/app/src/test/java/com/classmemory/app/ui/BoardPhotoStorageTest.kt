package com.classmemory.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardPhotoStorageTest {
    private val sessionId = "123e4567-e89b-12d3-a456-426614174000"
    private val photoId = "987e4567-e89b-12d3-a456-426614174000"

    @Test fun storedPathIsRelativeJpegWithinSessionDirectory() {
        assertEquals(
            "board-photos/$sessionId/$photoId.jpg",
            boardPhotoRelativePath(sessionId, photoId),
        )
    }

    @Test fun pathRejectsDirectoryTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            boardPhotoRelativePath("../other-session", photoId)
        }
    }

    @Test fun captureTimeIsRelativeAndNeverNegative() {
        assertEquals(31_000L, boardPhotoCapturedAtMs(100_000L, 131_000L))
        assertEquals(0L, boardPhotoCapturedAtMs(100_000L, 99_000L))
    }
}
