package cn.dsr213.hyperplus

/** Seed the system lock from the visible screen, never from a stale saved rotation. */
internal fun preserveRotationForTakeover(
    autoRotation: Int,
    visibleRotation: Int?,
    writeRotation: (Int) -> Boolean,
    lockRotation: () -> Boolean,
): Boolean {
    if (autoRotation == 0) return true // Respect an existing user lock.
    val visible = visibleRotation?.takeIf { it in 0..3 } ?: return false
    if (!writeRotation(visible)) return false
    return lockRotation()
}
