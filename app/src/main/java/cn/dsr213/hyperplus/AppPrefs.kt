package cn.dsr213.hyperplus

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Xml
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/** Upgrade users of the removed adaptive mode to button rotation. */
internal fun storedMode(raw: String?): RotateMode? = when (raw) {
    "ADAPTIVE", "SEMI" -> RotateMode.SEMI
    "SYSTEM" -> RotateMode.SYSTEM
    else -> null
}

enum class RotateMode {

    SYSTEM,

    SEMI;

    val label: String
        get() = when (this) {
            SYSTEM -> "跟随系统"

            SEMI -> "半自动旋转（点击确认）"
        }

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.mode_label_system

            SEMI -> R.string.mode_label_semi
        }

    val shortLabel: String
        get() = when (this) {
            SYSTEM -> "跟随系统"

            SEMI -> "半自动"
        }

    @get:StringRes
    val shortLabelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.mode_short_system

            SEMI -> R.string.mode_short_semi
        }

    val engages: Boolean get() = this != SYSTEM

}

object AppPrefs {

    private const val TAG = "HyperPlusPrefs"

    private const val CONFIG_PUSH_DEBOUNCE_MS = 200L

    const val NAME = "facerotate_prefs"

    const val AUTO_ROTATE_UNTOUCHED = -1

    private val _modeInner = MutableStateFlow(RotateMode.SYSTEM)

    private val _form = MutableStateFlow(ScreenForm.INNER)

    private val _mode = MutableStateFlow(RotateMode.SYSTEM)

    private val _handoffRotate = MutableStateFlow(true)

    private val _gateEnabled = MutableStateFlow(true)

    const val HINT_MS_MIN = 1_000
    const val HINT_MS_MAX = 60_000
    const val HINT_MS_DEFAULT = 3_000

    fun snapHintMs(v: Int): Int =
        ((v.coerceIn(HINT_MS_MIN, HINT_MS_MAX) + 500) / 1000 * 1000)
            .coerceIn(HINT_MS_MIN, HINT_MS_MAX)

    private val _hintMs = MutableStateFlow(HINT_MS_DEFAULT)

    private val _uncontrollableClearReq = MutableStateFlow("")

    private val _hintTestReq = MutableStateFlow("")

    private val _wlAdd = MutableStateFlow<Set<String>>(emptySet())

    private val _wlDel = MutableStateFlow<Set<String>>(emptySet())

    private val _whitelist = MutableStateFlow<Set<String>>(emptySet())

    private val _configOk = MutableStateFlow(false)
    val configOk: StateFlow<Boolean> = _configOk.asStateFlow()

    private val _configDiag = MutableStateFlow("未初始化")
    val configDiag: StateFlow<String> = _configDiag.asStateFlow()

    val mode: StateFlow<RotateMode> = _mode.asStateFlow()

    val modeInner: StateFlow<RotateMode> = _modeInner.asStateFlow()

    val screenForm: StateFlow<ScreenForm> = _form.asStateFlow()

    val handoffRotate: StateFlow<Boolean> = _handoffRotate.asStateFlow()
    val gateEnabled: StateFlow<Boolean> = _gateEnabled.asStateFlow()

    val hintMs: StateFlow<Int> = _hintMs.asStateFlow()

    val uncontrollableClearReq: StateFlow<String> = _uncontrollableClearReq.asStateFlow()

    val hintTestReq: StateFlow<String> = _hintTestReq.asStateFlow()

    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var pushScheduled = false

    private val pushHandler by lazy { Handler(Looper.getMainLooper()) }

    private val pushRunnable = Runnable {
        pushScheduled = false
        pushConfigNow()
    }

    @Volatile
    private var hostMode = false

    fun init(context: Context) {
        if (prefs != null || hostMode) {

            syncScreenForm(context)
            return
        }
        synchronized(this) {
            if (prefs != null || hostMode) {
                syncScreenForm(context)
                return
            }
            val app = context.applicationContext ?: context
            ctx = app

            val p = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            prefs = p

            adoptConfigFromMirrorIfNeeded(p)

            runCatching {
                val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleConfigPush() }
                p.registerOnSharedPreferenceChangeListener(l)
                prefsChangeListener = l
            }.onFailure {
                Log.w(TAG, "注册配置变更监听失败（配置仍会写盘，只是不会主动推给引擎）", it)
            }

            migrateLegacyIfNeeded(app, p)
            reloadFromPrefs()

            syncScreenForm(app)
        }

        persistAll()

        pushConfigNow()
    }

    private fun scheduleConfigPush() {
        if (pushScheduled) return
        pushScheduled = true
        pushHandler.postDelayed(pushRunnable, CONFIG_PUSH_DEBOUNCE_MS)
    }

    internal fun pushConfigNow() {
        val c = ctx ?: return
        val p = prefs ?: return
        val snap = ConfigChannel.snapshotOf(p)

        if (snap.isEmpty()) return
        if (ConfigChannel.sendPush(c, snap)) {
            Log.i(TAG, "已推送配置快照（${snap.size} 个键）")
        }
    }

    private fun persistAll() {
        val p = prefs ?: return
        runCatching {
            val e = p.edit()
                .putString(PrefsBridge.MODE_INNER, _modeInner.value.name)
                .putBoolean(PrefsBridge.HANDOFF_ROTATE, _handoffRotate.value)
                .putBoolean(PrefsBridge.GATE, _gateEnabled.value)
                .putInt(PrefsBridge.HINT_MS, _hintMs.value)

            e.putString(PrefsBridge.WHITELIST_ADD, AppWhitelist.encode(_wlAdd.value))
            e.putString(PrefsBridge.WHITELIST_REMOVE, AppWhitelist.encode(_wlDel.value))
            // Retire the removed camera settings when upgrading an existing install.
            listOf("capture_strategy", "experimental_adaptive", "angle_preview", "calib_req",
                "calib_step1_done", "calib_step2_done").forEach { e.remove(it) }
            e.apply()
        }.onFailure { Log.w(TAG, "配置落盘失败", it) }
    }

    private fun reloadFromPrefs() {
        val p = prefs ?: return

        val legacy = storedMode(p.getString(PrefsBridge.MODE_LEGACY, null))
        _modeInner.value =
            storedMode(p.getString(PrefsBridge.MODE_INNER, null)) ?: legacy ?: RotateMode.SYSTEM
        refreshEffectiveMode()
        _handoffRotate.value = p.getBoolean(PrefsBridge.HANDOFF_ROTATE, true)
        _gateEnabled.value = p.getBoolean(PrefsBridge.GATE, true)

        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))

        _uncontrollableClearReq.value =
            p.getString(PrefsBridge.UNCONTROLLABLE_CLEAR, null).orEmpty()
        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()

        migrateLegacyKeys(p)
        loadWhitelist { k -> p.getString(k, null) }
    }

    private fun migrateLegacyKeys(p: SharedPreferences) {

        val legacyRemoveInner = p.getString("app_whitelist_remove_inner", null)
        if (!legacyRemoveInner.isNullOrBlank()) {
            runCatching {
                val merged = AppWhitelist.decode(p.getString(PrefsBridge.WHITELIST_REMOVE, null)) +
                    AppWhitelist.decode(legacyRemoveInner)
                p.edit()
                    .putString(PrefsBridge.WHITELIST_REMOVE, AppWhitelist.encode(merged))
                    .apply()
                Log.i(TAG, "已迁回旧内屏层的豁免名单：$legacyRemoveInner")
            }.onFailure { Log.w(TAG, "旧内屏名单迁移失败（已忽略）", it) }
        }

        val dead = listOf(

            "unfold_default_inner",
            "slot_set",

            "app_whitelist_add_inner",
            "app_whitelist_add_outer",
            "app_whitelist_remove_outer",
            "rotate_mode_outer",

            "restore_auto_rotate",
            "takeover_active",
            "calib_offset",
        )
        val present = dead.filter { p.contains(it) }
        if (present.isEmpty()) return
        runCatching {
            p.edit().apply { present.forEach { remove(it) } }.apply()
            Log.i(TAG, "已清理失效的历史键：$present")
        }.onFailure { Log.w(TAG, "清理历史键失败（已忽略，不影响功能）", it) }
    }

    private const val K_ADOPTED_MIRROR = "migrated_from_engine_mirror"

    private val REQUEST_ONLY_KEYS = setOf(
        "calib_req", // Do not replay a historical request from an older version.
        PrefsBridge.HINT_TEST,
        PrefsBridge.UNCONTROLLABLE_CLEAR,
        PrefsBridge.BREAKER_RESET,
    )

    private fun adoptConfigFromMirrorIfNeeded(p: SharedPreferences) {
        if (p.getBoolean(K_ADOPTED_MIRROR, false)) return
        val c = ctx?.contentResolver ?: return

        val raw = PrefsBridge.readString(c, PrefsBridge.MIRROR)
        if (raw.isNullOrEmpty()) {

            p.edit().putBoolean(K_ADOPTED_MIRROR, true).apply()
            Log.i(TAG, "升级迁移：没有引擎镜像可搬（全新安装？）—— 记上标记，以后不再检查")
            return
        }
        val snap = ConfigChannel.decode(raw)
        if (snap.isNullOrEmpty()) {
            Log.w(TAG, "升级迁移：引擎镜像解析失败（长度 ${raw.length}）→ 不搬，下次启动再试")
            return
        }

        val missing = snap.keys.filter { it !in REQUEST_ONLY_KEYS && !p.contains(it) }
        if (missing.isEmpty()) {

            p.edit().putBoolean(K_ADOPTED_MIRROR, true).apply()
            return
        }

        val moved = mutableListOf<String>()
        runCatching {
            val e = p.edit()
            snap.forEach { (k, v) ->
                if (k in REQUEST_ONLY_KEYS) return@forEach
                when (v) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    else -> return@forEach
                }
                moved += k
            }

            e.putBoolean(K_ADOPTED_MIRROR, true)
            e.apply()
        }.onFailure {
            Log.w(TAG, "升级迁移：把引擎镜像搬回 prefs 失败（已放弃，等 App 自己推一份新的）", it)
        }

        Log.i(
            TAG,
            "升级迁移：本地缺 ${missing.size} 个键（$missing）⇒ 判定为陈旧影子，" +
                "已整份采纳引擎镜像（共 ${moved.size} 个键）",
        )
    }

    fun initHost(context: Context) {
        if (hostMode) {

            syncScreenForm(context)
            return
        }
        synchronized(this) {
            if (hostMode) {
                syncScreenForm(context)
                return
            }
            val app = context.applicationContext ?: context
            ctx = app
            hostMode = true

            cn.dsr213.hyperplus.module.ModulePrefs.subscribe { applyFromModulePrefs() }
            val ok = runCatching { cn.dsr213.hyperplus.module.ModulePrefs.open() }.getOrDefault(false)

            _configOk.value = ok
            _configDiag.value = cn.dsr213.hyperplus.module.ModulePrefs.diag
            if (ok) {
                applyFromModulePrefs()
                Log.i(TAG, "宿主配置通道就绪：${cn.dsr213.hyperplus.module.ModulePrefs.diag}")
            } else {

                Log.w(TAG, "⚠️ 宿主读不到 App 配置，将使用默认值：${cn.dsr213.hyperplus.module.ModulePrefs.diag}")
            }

            syncScreenForm(app)
        }
    }

    private fun applyFromModulePrefs() {
        val p = cn.dsr213.hyperplus.module.ModulePrefs

        val legacy = storedMode(p.getString(PrefsBridge.MODE_LEGACY, null))
        storedMode(p.getString(PrefsBridge.MODE_INNER, null))?.let { _modeInner.value = it }
            ?: legacy?.let { _modeInner.value = it }
        refreshEffectiveMode()

        _handoffRotate.value = p.getBoolean(PrefsBridge.HANDOFF_ROTATE, true)
        _gateEnabled.value = p.getBoolean(PrefsBridge.GATE, true)

        _hintMs.value = snapHintMs(p.getInt(PrefsBridge.HINT_MS, HINT_MS_DEFAULT))

        _uncontrollableClearReq.value =
            p.getString(PrefsBridge.UNCONTROLLABLE_CLEAR, null).orEmpty()

        _hintTestReq.value = p.getString(PrefsBridge.HINT_TEST, null).orEmpty()

        loadWhitelist { k -> p.getString(k, null) }

        _configOk.value = p.available
        _configDiag.value = p.diag
    }

    private fun loadWhitelist(get: (String) -> String?) {
        _wlAdd.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_ADD))
        _wlDel.value = AppWhitelist.decode(get(PrefsBridge.WHITELIST_REMOVE))
        refreshWhitelist()
    }

    private fun refreshWhitelist() {
        val next = AppWhitelist.resolve(_wlAdd.value, _wlDel.value)
        if (next != _whitelist.value) _whitelist.value = next
    }

    internal fun setAppWhitelisted(pkg: String, on: Boolean): Boolean {
        if (pkg.isBlank()) return false
        val before = pkg in _whitelist.value
        val add = _wlAdd.value.toMutableSet().apply { remove(pkg); if (on) add(pkg) }
        val del = _wlDel.value.toMutableSet().apply { remove(pkg); if (!on) add(pkg) }
        if (before == (pkg in AppWhitelist.resolve(add, del))) return false

        _wlAdd.value = add
        _wlDel.value = del
        refreshWhitelist()
        persistAll()
        return true
    }

    fun isWhitelisted(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg in _whitelist.value
    }

    fun resetWhitelistRemovals(): Boolean {
        if (_wlDel.value.isEmpty()) return false
        _wlDel.value = emptySet()
        refreshWhitelist()
        persistAll()
        return true
    }

    fun syncScreenForm(context: Context) {
        val probe = runCatching { ScreenForm.probe(context) }
            .onFailure { ScreenFormLog.failed(it) }
            .getOrNull() ?: return
        val changed = probe.form != _form.value
        _form.value = probe.form
        refreshEffectiveMode()

        refreshWhitelist()
        if (changed) {

            PanelOrientation.invalidate()

            runCatching { formChangeListener?.invoke(probe.form) }
                .onFailure { Log.w(TAG, "形态变更回调异常（已忽略）", it) }
        }

        ScreenFormLog.note(probe, _mode.value, changed)
    }

    @Volatile
    private var formChangeListener: ((ScreenForm) -> Unit)? = null

    fun setScreenFormListener(l: ((ScreenForm) -> Unit)?) {
        formChangeListener = l
    }

    fun modeOf(form: ScreenForm): RotateMode =
        if (form == ScreenForm.INNER) _modeInner.value else RotateMode.SYSTEM

    private fun refreshEffectiveMode() {
        _mode.value = modeOf(_form.value)
    }

    fun setMode(value: RotateMode) {
        _modeInner.value = value
        prefs?.edit()?.putString(PrefsBridge.MODE_INNER, value.name)?.apply()
        refreshEffectiveMode()
    }

    fun setHandoffRotate(v: Boolean) {
        _handoffRotate.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.HANDOFF_ROTATE, v)?.apply()
    }

    fun setGateEnabled(v: Boolean) {
        _gateEnabled.value = v
        prefs?.edit()?.putBoolean(PrefsBridge.GATE, v)?.apply()
    }

    fun setHintMs(v: Int) {
        val clamped = snapHintMs(v)
        _hintMs.value = clamped
        prefs?.edit()?.putInt(PrefsBridge.HINT_MS, clamped)?.apply()
    }

    fun requestUncontrollableClear() {
        val v = System.currentTimeMillis().toString()
        _uncontrollableClearReq.value = v
        prefs?.edit()?.putString(PrefsBridge.UNCONTROLLABLE_CLEAR, v)?.apply()
    }

    fun requestHintTest(targetRotation: Int = 1) {
        val v = "${System.currentTimeMillis()}|$targetRotation"
        _hintTestReq.value = v
        prefs?.edit()?.putString(PrefsBridge.HINT_TEST, v)?.apply()
        Log.i(TAG, "按钮预览请求已落盘：$v")
    }

    fun toggleMode(): RotateMode {
        if (_form.value == ScreenForm.OUTER) return RotateMode.SYSTEM
        return nextMode(_modeInner.value).also { setMode(it) }
    }

    fun nextMode(m: RotateMode): RotateMode = when (m) {
        RotateMode.SYSTEM -> RotateMode.SEMI

        RotateMode.SEMI -> RotateMode.SYSTEM
    }

    suspend fun requestBreakerReset(): Boolean {
        val token = System.currentTimeMillis().toString()
        prefs?.edit()?.putString(PrefsBridge.BREAKER_RESET, token)?.apply()
        Log.i(TAG, "熔断复位：请求已落盘（$token）")
        val byRoot = RootShell.putSystemInt(PrefsBridge.BOOT_ATTEMPTS, 0)
        Log.i(TAG, "熔断复位：root 直写计数键 = $byRoot")
        return byRoot
    }

    /** Persist ownership before changing the system lock. */
    fun beginTakeover(form: ScreenForm, restore: Int): Boolean {
        val cr = ctx?.contentResolver ?: return false
        if (!PrefsBridge.writeString(cr, PrefsBridge.full(PrefsBridge.RESTORE), restore.toString())) return false
        if (!PrefsBridge.writeString(cr, PrefsBridge.full(PrefsBridge.TAKEOVER_FORM), form.name)) return false
        return setTakeoverActive(true)
    }

    fun takeoverForm(): ScreenForm {
        val raw = ctx?.contentResolver?.let {
            PrefsBridge.readString(it, PrefsBridge.full(PrefsBridge.TAKEOVER_FORM))
        }
        // Older versions only took over the inner screen; never restore their debt on outer.
        return runCatching { ScreenForm.valueOf(raw.orEmpty()) }.getOrDefault(ScreenForm.INNER)
    }

    fun restoreTarget(): Int {
        val cr = ctx?.contentResolver ?: return AUTO_ROTATE_UNTOUCHED
        return PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.RESTORE))
            ?.toIntOrNull() ?: AUTO_ROTATE_UNTOUCHED
    }

    fun setTakeoverActive(active: Boolean): Boolean {
        val c = ctx ?: return false
        return PrefsBridge.writeString(
            c.contentResolver,
            PrefsBridge.full(PrefsBridge.TAKEOVER),
            if (active) "1" else "0",
        )
    }

    fun isTakeoverActive(): Boolean {
        val cr = ctx?.contentResolver ?: return false
        return PrefsBridge.readString(cr, PrefsBridge.full(PrefsBridge.TAKEOVER)) == "1"
    }

    private fun migrateLegacyIfNeeded(app: Context, p: SharedPreferences) {

        if (p.contains(PrefsBridge.MODE_INNER) || p.contains(PrefsBridge.MODE_OUTER)) return

        val cr = app.contentResolver

        val legacyLocal = readLegacyLocalPrefs(app)

        fun fromSettings(key: String): String? = PrefsBridge.readString(cr, PrefsBridge.full(key))

        val mode = fromSettings(PrefsBridge.MODE_LEGACY) ?: legacyLocal[PrefsBridge.MODE_LEGACY] as? String
        val handoff = fromSettings(PrefsBridge.HANDOFF_ROTATE) ?: legacyLocal[PrefsBridge.HANDOFF_ROTATE]?.toString()
        val gate = fromSettings(PrefsBridge.GATE) ?: legacyLocal[PrefsBridge.GATE]?.toString()

        if (mode == null && handoff == null && gate == null) return

        runCatching {
            val e = p.edit()
            if (storedMode(mode) != null) {

                e.putString(PrefsBridge.MODE_INNER, mode)
            }
            if (handoff != null) e.putBoolean(PrefsBridge.HANDOFF_ROTATE, handoff == "true" || handoff == "1")
            if (gate != null) e.putBoolean(PrefsBridge.GATE, gate == "true" || gate == "1")
            e.apply()
            Log.i(
                TAG,
                "已从旧通道迁移配置：mode=$mode（灌给内屏那一份）handoff=$handoff gate=$gate",
            )
        }.onFailure { Log.w(TAG, "旧配置迁移失败（用户需手动重设）", it) }
    }

    private fun readLegacyLocalPrefs(app: Context): Map<String, Any> {
        val f = File(app.dataDir, "shared_prefs/$NAME.xml")
        if (!f.isFile || !f.canRead()) return emptyMap()
        val out = mutableMapOf<String, Any>()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            FileInputStream(f).use { parser.setInput(it, null) }

            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    if (!key.isNullOrEmpty()) {
                        when (parser.name) {
                            "string" -> out[key] = parser.nextText()
                            "boolean" -> out[key] = parser.getAttributeValue(null, "value") == "true"
                            "int" -> out[key] = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                            "float" -> out[key] = parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                        }
                    }
                }
                ev = parser.next()
            }
            out
        }.onFailure { Log.w(TAG, "解析旧 prefs 失败", it) }.getOrDefault(emptyMap())
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        if (name.isNullOrEmpty()) null
        else runCatching { enumValueOf<T>(name) }.getOrNull()
}
