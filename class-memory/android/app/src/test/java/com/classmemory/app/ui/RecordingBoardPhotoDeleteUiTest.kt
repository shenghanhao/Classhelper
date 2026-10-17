package com.classmemory.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBoardPhotoDeleteUiTest {
    @Test
    fun deleteStartsOnlyAfterConfirmationAndOnlyOnceWhileBusy() {
        var requests = 0
        requestBoardPhotoDeletion(confirmed = false, busy = false) { requests++ }
        assertEquals(0, requests)

        requestBoardPhotoDeletion(confirmed = true, busy = true) { requests++ }
        assertEquals(0, requests)

        requestBoardPhotoDeletion(confirmed = true, busy = false) { requests++ }
        assertEquals(1, requests)
        requestBoardPhotoDeletion(confirmed = true, busy = true) { requests++ }
        assertEquals(1, requests)
    }
}
