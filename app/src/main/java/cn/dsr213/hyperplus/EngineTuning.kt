package cn.dsr213.hyperplus

const val FOREGROUND_POLL_MS = 2000L

const val FORM_SETTLE_MS = 300L

const val KEY_USER_ROTATION_PREFIX = PrefsBridge.KEY_USER_ROTATION_PREFIX

const val SEMI_COOLDOWN_MS = 500L

const val SEMI_SETTLE_MS = 280L

const val SEMI_READBACK_MS = 600L

const val UNCONTROLLABLE_TTL_MS = 3_000L

const val SEMI_TAP_COOLDOWN_MS = 700L

val SELF_PKG: String = BuildConfig.APPLICATION_ID

const val GRAVITY_MIN_PLANAR_G = 1.5f

const val BOOT_BREAKER_THRESHOLD = 3

const val BOOT_HEALTHY_WINDOW_MS = 60_000L

fun bootBreakerTripped(attempts: Int): Boolean = attempts >= BOOT_BREAKER_THRESHOLD
