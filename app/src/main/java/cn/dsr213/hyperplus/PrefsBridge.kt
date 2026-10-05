package cn.dsr213.hyperplus

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log

internal object PrefsBridge {

    private const val TAG = "HyperPlusPrefs"

    private const val PREFIX = "hyperplus_"

    fun full(localKey: String): String = PREFIX + localKey

    const val MODE_LEGACY = "rotate_mode"

    const val MODE_INNER = "rotate_mode_inner"

    const val MODE_OUTER = "rotate_mode_outer"

    const val HANDOFF_ROTATE = "handoff_auto_rotate"

    const val GATE = "gate_enabled"

    const val HINT_MS = "semi_hint_ms"

    const val HINT_TEST = "hint_test"

    const val WHITELIST_ADD = "app_whitelist_add"

    const val WHITELIST_REMOVE = "app_whitelist_remove"

    const val UNCONTROLLABLE_CLEAR = "uncontrollable_clear"

    const val BREAKER_RESET = "breaker_reset"

    const val RESTORE = "restore_auto_rotate"

    const val TAKEOVER = "takeover_active"

    val BOOT_ATTEMPTS = PREFIX + "boot_attempts"

    val MIRROR = PREFIX + "config_mirror"

    const val KEY_USER_ROTATION_PREFIX = "user_rotation_"

    val STATE = PREFIX + "bus_engine_state"

    val HEARTBEAT = PREFIX + "bus_engine_heartbeat"

    fun readString(cr: ContentResolver, key: String): String? =
        runCatching { Settings.System.getString(cr, key) }.getOrNull()

    fun readInt(cr: ContentResolver, key: String, def: Int): Int =
        runCatching { Settings.System.getInt(cr, key, def) }.getOrDefault(def)

    fun writeString(cr: ContentResolver, key: String, v: String): Boolean =
        runCatching { Settings.System.putString(cr, key, v) }
            .onFailure { Log.w(TAG, "写 $key 失败", it) }
            .getOrDefault(false)

    fun writeInt(cr: ContentResolver, key: String, v: Int): Boolean =
        runCatching { Settings.System.putInt(cr, key, v) }
            .onFailure { Log.w(TAG, "写 $key 失败", it) }
            .getOrDefault(false)

    fun delete(cr: ContentResolver, key: String): Boolean =
        runCatching { Settings.System.putString(cr, key, null) }
            .onFailure { Log.w(TAG, "删 $key 失败", it) }
            .getOrDefault(false)
}
