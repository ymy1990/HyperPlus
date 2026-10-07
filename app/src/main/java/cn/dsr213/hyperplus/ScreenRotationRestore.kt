package cn.dsr213.hyperplus

import android.content.Context
import android.provider.Settings

internal enum class RotationRestoreResult { DEFERRED, UNCHANGED, RESTORED, FAILED }

/** A global rotation setting always belongs to the screen currently projected by the ROM. */
internal fun restoreTakeoverOnScreen(
    owner: ScreenForm,
    current: ScreenForm?,
    target: Int,
    autoRotation: Int?,
    writeAutoRotation: (Int) -> Boolean,
): RotationRestoreResult {
    // Folding must never copy an inner-screen preference into the outer-screen setting.
    if (current != owner) return RotationRestoreResult.DEFERRED
    if (target !in 0..1) return RotationRestoreResult.UNCHANGED
    if (autoRotation == null) return RotationRestoreResult.DEFERRED
    if (autoRotation != 0) return RotationRestoreResult.UNCHANGED // The user changed it.
    return if (writeAutoRotation(target)) RotationRestoreResult.RESTORED else RotationRestoreResult.FAILED
}

/** Shared by normal release, process recovery and the host's crash fallback. */
internal object ScreenRotationRestore {
    fun currentForm(context: Context): ScreenForm? = runCatching {
        val form = ScreenForm.probe(context).form
        // Do not restore while the display is off or a screen switch cannot be observed.
        if (ActiveDisplay.forForm(context, form) == null) null else form
    }.getOrNull()

    fun restore(context: Context): RotationRestoreResult {
        if (!AppPrefs.isTakeoverActive()) return RotationRestoreResult.UNCHANGED
        val cr = context.contentResolver
        val result = restoreTakeoverOnScreen(
            owner = AppPrefs.takeoverForm(),
            current = currentForm(context),
            target = AppPrefs.restoreTarget(),
            autoRotation = runCatching {
                Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION)
            }.getOrNull(),
            writeAutoRotation = { value -> runCatching {
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, value)
            }.getOrDefault(false) },
        )
        // A cross-screen release keeps its debt so it can be restored on the original screen.
        if (result == RotationRestoreResult.UNCHANGED || result == RotationRestoreResult.RESTORED) {
            AppPrefs.setTakeoverActive(false)
        }
        return result
    }
}
