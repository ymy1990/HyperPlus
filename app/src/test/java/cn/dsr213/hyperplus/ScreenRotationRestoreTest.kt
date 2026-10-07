package cn.dsr213.hyperplus

import org.junit.Assert.*
import org.junit.Test

class ScreenRotationRestoreTest {
    @Test fun foldingNeverCopiesInnerUnlockToOuterLock() {
        var outerLock = 0
        val result = restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.OUTER, 1, outerLock) {
            outerLock = it; true
        }
        assertEquals(RotationRestoreResult.DEFERRED, result)
        assertEquals(0, outerLock)
    }

    @Test fun allIndependentLockCombinationsSurviveRepeatedFolds() {
        for (inner in 0..1) for (outer in 0..1) {
            val target = if (inner == 0) -1 else inner
            var outerSetting = outer
            repeat(3) {
                assertEquals(RotationRestoreResult.DEFERRED,
                    restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.OUTER, target, outerSetting) {
                        outerSetting = it; true
                    })
                assertEquals(outer, outerSetting)
                var innerSetting = 0 // Module temporarily locked the inner screen.
                val result = restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, target, innerSetting) {
                    innerSetting = it; true
                }
                assertEquals(inner, innerSetting)
                assertEquals(if (inner == 0) RotationRestoreResult.UNCHANGED else RotationRestoreResult.RESTORED, result)
                assertEquals(outer, outerSetting)
            }
        }
    }

    @Test fun pendingInnerDebtCanBeRestoredOnlyAfterReturningToInner() {
        var writes = 0
        assertEquals(RotationRestoreResult.DEFERRED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.OUTER, 1, 0) { writes++; true })
        assertEquals(0, writes)
        assertEquals(RotationRestoreResult.RESTORED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, 1, 0) { writes++; it == 1 })
        assertEquals(1, writes)
    }

    @Test fun recoveryNeverWritesToAnUnknownOrDifferentScreen() {
        for (current in listOf(null, ScreenForm.OUTER)) {
            assertEquals(RotationRestoreResult.DEFERRED,
                restoreTakeoverOnScreen(ScreenForm.INNER, current, 1, 0) {
                    fail("Must not restore another screen's setting"); true
                })
        }
    }

    @Test fun unreadableSystemSettingIsDeferredInsteadOfGuessed() {
        assertEquals(RotationRestoreResult.DEFERRED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, 1, null) {
                fail("Read failure is not a locked setting"); true
            })
    }

    @Test fun anExistingUserLockDoesNotCreateARestoreWrite() {
        assertEquals(RotationRestoreResult.UNCHANGED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, -1, 0) {
                fail("Module never changed this lock"); true
            })
    }

    @Test fun userChangesAreRespectedOnTheOriginalScreen() {
        assertEquals(RotationRestoreResult.UNCHANGED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, 0, 1) {
                fail("Must not overwrite the user's new choice"); true
            })
    }

    @Test fun failedWritesAreRetryableOnTheOriginalScreen() {
        assertEquals(RotationRestoreResult.FAILED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, 1, 0) { false })
        assertEquals(RotationRestoreResult.RESTORED,
            restoreTakeoverOnScreen(ScreenForm.INNER, ScreenForm.INNER, 1, 0) { true })
    }
}
