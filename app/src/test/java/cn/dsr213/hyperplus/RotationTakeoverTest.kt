package cn.dsr213.hyperplus

import org.junit.Assert.*
import org.junit.Test

class RotationTakeoverTest {
    @Test fun unfoldingLandscapeDoesNotUseStalePortraitLock() {
        for (display in 0..3) {
            var stored = 0
            val writes = mutableListOf<String>()
            assertTrue(preserveRotationForTakeover(1, display, {
                stored = it; writes += "rotation:$it"; true
            }, {
                assertEquals(display, stored)
                writes += "lock"; true
            }))
            assertEquals(listOf("rotation:$display", "lock"), writes)
        }
    }
    @Test fun missingOrInvalidDisplayNeverLocksToPortrait() {
        for (display in listOf(null, -1, 4)) {
            assertFalse(preserveRotationForTakeover(1, display,
                { fail("must not write a fallback direction"); true },
                { fail("must not lock without a display reading"); true }))
        }
    }
    @Test fun failedDirectionWriteDoesNotLock() {
        assertFalse(preserveRotationForTakeover(1, 3, { false },
            { fail("lock must follow a successful direction write"); true }))
    }
    @Test fun existingUserLockIsUntouched() {
        assertTrue(preserveRotationForTakeover(0, 3,
            { fail("existing lock must be respected"); true },
            { fail("no extra lock write"); true }))
    }
    @Test fun rejectedLockIsReported() {
        assertFalse(preserveRotationForTakeover(1, 1, { true }, { false }))
    }
    @Test fun oldAdaptiveSettingsUpgradeToButtonMode() {
        assertEquals(RotateMode.SEMI, storedMode("ADAPTIVE"))
        assertEquals(RotateMode.SEMI, storedMode("SEMI"))
        assertEquals(RotateMode.SYSTEM, storedMode("SYSTEM"))
        assertNull(storedMode("unknown"))
        assertEquals(listOf(RotateMode.SYSTEM, RotateMode.SEMI), RotateMode.entries)
        assertEquals(RotateMode.SEMI, AppPrefs.nextMode(RotateMode.SYSTEM))
        assertEquals(RotateMode.SYSTEM, AppPrefs.nextMode(RotateMode.SEMI))
    }
}
