package cn.dsr213.hyperplus

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import cn.dsr213.hyperplus.ForegroundGate.StopReason
import cn.dsr213.hyperplus.trigger.DeviceOrientationTrigger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Button rotation. Sensors propose a direction; only a tap changes it. */
class RotationEngine(
    private val context: Context,
    private val onFatal: ((Throwable) -> Unit)? = null,
) {
    data class UiState(
        val mode: RotateMode = RotateMode.SYSTEM,
        val sensorAvailable: Boolean = false,
        val triggerCode: Int = -1,
        val triggerCount: Int = 0,
        val takeoverOn: Boolean = false,
        val writeSettingsGranted: Boolean = false,
        val foregroundGated: Boolean = false,
        val foregroundPkg: String = "",
        val foregroundOrientation: String = "",
        val foregroundForm: String = "",
        val foregroundStop: String = "",
        val foregroundReadable: Boolean = false,
        val skipGateCount: Int = 0,
        val handoffRotate: Boolean = true,
        val gateEnabled: Boolean = true,
        val whitelistSize: Int = 0,
        val semiShownCount: Int = 0,
        val lastSemiTarget: Int = -1,
        val semiTappedCount: Int = 0,
        val hintAlive: Boolean = false,
        val uncontrollableCount: Int = 0,
        val formProbeDp: Int = 0,
        val overlayUsable: Boolean = false,
        val overlayType: Int = -1,
        val displayRotation: Int = 0,
        val panelOffset: Int = 0,
        val events: List<String> = emptyList(),
    )
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val fatalGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "协程内未捕获异常（已拦截，不让它杀进程）", e)
        runCatching { onFatal?.invoke(e) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + fatalGuard)

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private var trigger: DeviceOrientationTrigger? = null
    private val fgProbe: ForegroundProbe by lazy { ForegroundProbe(context) }

    @Volatile private var fgGated = false

    @Volatile private var fgHandedOff = false

    @Volatile private var gateRejudging = false

    private var fgWatchJob: Job? = null

    @Volatile private var overlayRef: RotateHintOverlay? = null

    private fun ensureOverlay(): RotateHintOverlay =
        overlayRef ?: synchronized(this) {
            overlayRef ?: RotateHintOverlay(context) { target -> applySemiRotation(target) }
                .also { overlayRef = it }
        }

    @Volatile private var lastHintTestReq: String? = null

    private fun showHintForTest(req: String) {
        if (lastHintTestReq == null) {
            lastHintTestReq = req
            return
        }
        if (req == lastHintTestReq) return
        lastHintTestReq = req
        val reqTarget = req.substringAfter('|', "").toIntOrNull() ?: return

        val target = semiTargetRotation() ?: currentDeviceRotation()
        val ok = runCatching { ensureOverlay().show(target) }.getOrElse { false }
        event(
            if (ok) {
                "预览按钮：已弹出（目标 ${rotName(target)}；请求里写的是 ${rotName(reqTarget)}，" +
                    "定位按设备姿态走）—— 只为看外观，点下去仍走真实路径"
            } else {
                "预览按钮：⚠️ 加窗失败（看下面几行悬浮窗类型的日志）"
            },
        )
    }

    @Volatile private var semiCooldownUntil = 0L

    @Volatile private var semiLastTarget = -1

    @Volatile private var semiHintAtMs = 0L

    private var semiSettleReason = ""

    private val semiSettleRunnable = Runnable { confirmSemiHint(semiSettleReason) }

    @Volatile private var uncontrollableCache = ""

    @Volatile private var uncontrollableCacheAt = 0L

    @Volatile private var uncontrollableClearSeen = ""

    @Volatile private var uncontrollableClearPrimed = false

    private var semiReadbackGen = 0

    private var lastAppliedRot = -1
    private var lastWriteAt = 0L
    private var started = false
    private var formWatchInstalled = false
    private var formSettleJob: Job? = null

    fun start() = onMain {
        if (started) return@onMain
        started = true
        AppPrefs.init(context)
        recoverOrphanTakeover()
        installFormWatch()
        _ui.update { it.copy(mode = AppPrefs.mode.value, writeSettingsGranted = Settings.System.canWrite(context)) }
        scope.launch { AppPrefs.mode.collect { m ->
            _ui.update { it.copy(mode = m) }
            cancelSemiSettle()
            semiReadbackGen++
            overlayRef?.hide()
            if (m == RotateMode.SEMI) {
                refreshForegroundGate("模式切换")
                engageTakeover()
                startTrigger()
            } else {
                stopTrigger()
                releaseTakeover()
            }
        } }
        scope.launch { AppPrefs.gateEnabled.collect { v ->
            _ui.update { it.copy(gateEnabled = v) }
            refreshForegroundGate("名单开关")
        } }
        scope.launch { AppPrefs.whitelist.collect { wl ->
            _ui.update { it.copy(whitelistSize = wl.size) }
            refreshForegroundGate("名单变更")
        } }
        scope.launch { AppPrefs.uncontrollableClearReq.collect { handleUncontrollableClearRequest(it) } }
        scope.launch { AppPrefs.hintTestReq.collect { showHintForTest(it) } }
        scope.launch { AppPrefs.handoffRotate.collect { v ->
            _ui.update { it.copy(handoffRotate = v) }
            if (fgGated && v && _ui.value.takeoverOn) {
                releaseTakeover(quiet = true)
                fgHandedOff = true
            } else if (fgGated && !v) {
                fgHandedOff = false
                engageTakeover()
            }
        } }
        startForegroundWatch()
        refreshRotationUi()
        event("按钮旋转服务启动")
    }

    fun stop() = onMain {
        if (!started) return@onMain
        started = false
        stopTrigger()
        cancelSemiSettle()
        semiReadbackGen++
        overlayRef?.hide()
        stopForegroundWatch()
        formSettleJob?.cancel()
        if (formWatchInstalled) context.unregisterComponentCallbacks(formCallback)
        formWatchInstalled = false
        AppPrefs.setScreenFormListener(null)
        releaseTakeover(quiet = true)
        scope.cancel()
    }

    private fun onTriggered(reason: String) = onMain {
        if (!started || AppPrefs.mode.value != RotateMode.SEMI) return@onMain
        watchScreenForm()
        refreshForegroundGate("触发")
        if (fgGated) {
            _ui.update { it.copy(skipGateCount = it.skipGateCount + 1) }
            return@onMain
        }
        engageTakeover()
        onSemiTriggered(reason)
    }

    private fun startTrigger() {
        val existing = trigger
        if (existing != null) {

            val ok = existing.start()
            _ui.update { it.copy(sensorAvailable = ok) }
            if (ok) event("触发层已恢复")
            return
        }
        val t = DeviceOrientationTrigger(context) { reason, code ->
            _ui.update {
                it.copy(
                    triggerCode = code,
                    triggerCount = it.triggerCount + 1,
                )
            }
            event("触发：$reason")
            onTriggered(reason)
        }
        val ok = t.start()
        trigger = t
        _ui.update { it.copy(sensorAvailable = ok) }
        if (ok) {
            event(
                "触发层已启用（触发：主路 device_orientation on-change + 兜底 gyro 角速度；" +
                    "重力路：只当尺子，用于判断按钮目标方向）"
            )
        } else {
            event("⚠️ device_orientation 与 gyro 都不可用，触发层未启动")
        }
    }

    private fun stopTrigger() {
        trigger?.stop()

        _ui.update { it.copy(sensorAvailable = false) }
    }

    private fun refreshForegroundGate(why: String): Boolean {
        if (AppPrefs.mode.value != RotateMode.SEMI) return false

        if (!AppPrefs.gateEnabled.value) {
            leaveForegroundGate()
            return false
        }
        val info = runCatching { fgProbe.read() }.getOrNull()
        if (info == null) {

            EngineErrors.bump(EngineErrors.FOREGROUND)

            if (_ui.value.foregroundReadable) {
                _ui.update { it.copy(foregroundReadable = false) }
                event("前台门：读不到前台应用（$why）→ 门控不生效，按原行为运行")
            }
            return false
        }

        val form = AppPrefs.screenForm.value
        val whitelisted = AppPrefs.isWhitelisted(info.pkg)

        val uncontrollable = Uncontrollable.isMarked(uncontrollableRaw(), info.pkg, form)

        _ui.update {
            it.copy(
                foregroundPkg = info.pkg,
                foregroundOrientation = ForegroundGate.label(info.orientation),
                foregroundForm = if (form == ScreenForm.OUTER) "OUTER" else "INNER",

                hintAlive = overlayRef?.isShowing == true,

                uncontrollableCount = Uncontrollable.sizeOf(uncontrollableRaw()),
            )
        }
        val reason = when {
            whitelisted -> StopReason.WHITELIST
            uncontrollable -> StopReason.UNCONTROLLABLE
            else -> null
        }
        when (ForegroundGate.decide(info.pkg, reason != null)) {
            ForegroundGate.Decision.YIELD ->
                enterForegroundGate(info, why, reason ?: StopReason.WHITELIST)
            ForegroundGate.Decision.MANAGEABLE -> leaveForegroundGate()

            ForegroundGate.Decision.UNKNOWN -> Unit
        }
        if (!_ui.value.foregroundReadable) {
            _ui.update { it.copy(foregroundReadable = true) }
        }
        return true
    }

    private fun enterForegroundGate(
        info: ForegroundProbe.Info,
        why: String,
        reason: ForegroundGate.StopReason,
    ) {
        val label = ForegroundGate.label(info.orientation)

        val hit = when (reason) {
            ForegroundGate.StopReason.WHITELIST -> "在豁免名单里"
            ForegroundGate.StopReason.UNCONTROLLABLE ->
                "在${AppPrefs.screenForm.value.label}上实测旋转无效" +
                    "（写过方向、两次读回屏幕都没变）"
        }
        if (fgGated) {

            if (_ui.value.foregroundPkg != info.pkg ||
                _ui.value.foregroundOrientation != label ||
                _ui.value.foregroundStop != reason.name
            ) {
                _ui.update {
                    it.copy(
                        foregroundPkg = info.pkg,
                        foregroundOrientation = label,
                        foregroundStop = reason.name,
                    )
                }
            }
            return
        }

        fgGated = true

        cancelSemiSettle()
        runCatching { overlayRef?.hide() }

        val wantHandoff = AppPrefs.handoffRotate.value
        if (wantHandoff && _ui.value.takeoverOn) {
            releaseTakeover(restoreSystem = true, quiet = true)
            fgHandedOff = true
        } else {
            fgHandedOff = false
        }

        _ui.update {
            it.copy(
                foregroundGated = true,
                foregroundPkg = info.pkg,
                foregroundOrientation = label,
                foregroundStop = reason.name,
                foregroundReadable = true,
                handoffRotate = wantHandoff,

                whitelistSize = AppPrefs.whitelist.value.size,
            )
        }

        event(
            "⏸️ 前台门（$why）：${info.pkg} $hit → 已停手（不弹按钮 / 不写方向）" +
                when {
                    fgHandedOff -> "，并把自动旋转交还系统"
                    !wantHandoff -> "，按你的设置保留当前方向"
                    else -> "（当时尚未接管，方向盘本就在系统手上）"
                } +
                "（该应用声明的朝向为 $label，仅作诊断）",
        )
    }

    private fun leaveForegroundGate() {
        if (!fgGated) return
        fgGated = false

        fgHandedOff = false
        engageTakeover()
        _ui.update {
            it.copy(
                foregroundGated = false,
                foregroundPkg = "",
                foregroundOrientation = "",
                foregroundStop = "",
                handoffRotate = AppPrefs.handoffRotate.value,
            )
        }
        event("▶️ 前台门解除：前台应用不在白名单里（或已退出）→ 恢复按钮旋转")

        rejudgeAfterGate()
    }

    private fun rejudgeAfterGate() {
        if (gateRejudging) return
        gateRejudging = true
        try {
            onTriggered("前台门解除补判")
        } catch (t: Throwable) {
            Log.w(TAG, "外壳补判异常（已忽略）", t)
        } finally {
            gateRejudging = false
        }
    }

    private fun startForegroundWatch() {
        if (fgWatchJob?.isActive == true) return
        fgWatchJob = scope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_MS)

                runCatching { watchScreenForm() }
                    .onFailure { Log.w(TAG, "形态巡检异常（已忽略）", it) }

                refreshRotationUi()
                if (AppPrefs.mode.value == RotateMode.SYSTEM) continue
                if (!_ui.value.sensorAvailable) continue
                runCatching {
                    refreshForegroundGate("巡检")
                    if (!_ui.value.takeoverOn) engageTakeover()
                }
                    .onFailure { Log.w(TAG, "前台门巡检异常（已忽略）", it) }
            }
        }
    }

    private fun stopForegroundWatch() {
        fgWatchJob?.cancel()
        fgWatchJob = null

        fgGated = false
        fgHandedOff = false
        _ui.update {
            it.copy(foregroundGated = false, foregroundPkg = "", foregroundOrientation = "")
        }
    }

    private fun onSemiTriggered(reason: String) {

        onMain { scheduleSemiSettle(reason) }
    }

    private fun scheduleSemiSettle(reason: String) {
        semiSettleReason = reason
        mainHandler.removeCallbacks(semiSettleRunnable)
        mainHandler.postDelayed(semiSettleRunnable, SEMI_SETTLE_MS)
    }

    private fun cancelSemiSettle() {
        mainHandler.removeCallbacks(semiSettleRunnable)
    }

    private fun confirmSemiHint(reason: String) {

        if (AppPrefs.mode.value != RotateMode.SEMI) return
        if (ScreenRotationRestore.currentForm(context) != ScreenForm.INNER) return

        if (fgGated) return
        val now = SystemClock.elapsedRealtime()
        val target = semiTargetRotation()

        val cur = currentDeviceRotation()

        if (!shouldShowSemiHint(
                now = now,
                cooldownUntil = semiCooldownUntil,
                target = target,
                current = cur,
                lastTarget = semiLastTarget,
                lastHintAt = semiHintAtMs,
                repeatSuppressMs = semiRepeatSuppressMs(),

                flipSuppressMs = semiRepeatSuppressMs(),
            )
        ) {

            if (target != null && target == cur) semiLastTarget = -1

            return
        }
        val t = target ?: return

        semiLastTarget = t
        semiHintAtMs = now
        semiCooldownUntil = now + SEMI_COOLDOWN_MS

        val ok = runCatching { ensureOverlay().show(t) }.getOrDefault(false)
        _ui.update {
            it.copy(
                semiShownCount = it.semiShownCount + 1,
                lastSemiTarget = t,
                overlayUsable = ok,
                overlayType = overlayRef?.activeWindowType ?: -1,

                hintAlive = overlayRef?.isShowing == true,
            )
        }
        if (ok) {
            event(
                "半自动：设备姿态 → ${rotName(t)}（当前 ${rotName(cur)}）" +
                    " ⇒ 弹出旋转按钮，${AppPrefs.hintMs.value / 1000} 秒内点击生效（$reason）"
            )
        } else {

            event("⚠️ 半自动：旋转按钮弹出失败 —— 两档窗口类型都被 WindowManager 拒了（$reason）")
        }
    }

    private fun semiTargetRotation(): Int? {
        val t = trigger ?: return null
        val fresh = t.gravityFresh(SystemClock.elapsedRealtime())
        return semiTarget(fresh, t.gravityAx, t.gravityAy, t.lastCode, GRAVITY_MIN_PLANAR_G)
    }

    private fun applySemiRotation(target: Int) {

        val now = SystemClock.elapsedRealtime()
        semiCooldownUntil = now + SEMI_TAP_COOLDOWN_MS
        semiLastTarget = target

        semiHintAtMs = 0L

        if (AppPrefs.mode.value != RotateMode.SEMI) return
        if (ScreenRotationRestore.currentForm(context) != ScreenForm.INNER) return

        if (fgGated) {
            runCatching { overlayRef?.hide() }
            event("半自动：当前应用在白名单里（不受本应用控制）⇒ 点击不生效，已收起按钮")
            return
        }
        if (!_ui.value.takeoverOn) {
            event("⚠️ 半自动：尚未接管（缺「修改系统设置」授权）⇒ 点击不生效")
            return
        }

        if (!_ui.value.takeoverOn || readAutoRotate() != 0) engageTakeover()
        if (readAutoRotate() != 0) {
            runCatching { overlayRef?.hide() }
            event("⚠️ 半自动：自动旋转不在本模块手里（ACCELEROMETER_ROTATION=1）⇒ 点击不生效，已收起按钮")
            return
        }

        val cur = currentDeviceRotation()
        if (cur == target) {
            event("半自动：点击时屏幕已是 ${rotName(target)}，无需旋转")
            return
        }
        if (!writeUserRotation(target)) return
        _ui.update { it.copy(semiTappedCount = it.semiTappedCount + 1, lastSemiTarget = target) }
        event("半自动：用户点击 ⇒ 旋转到 ${rotName(target)}（原 ${rotName(cur)}）")

        scheduleUncontrollableReadback(target)
    }

    private fun scheduleUncontrollableReadback(target: Int) {
        val gen = ++semiReadbackGen
        val pkg = _ui.value.foregroundPkg.takeIf { it.isNotBlank() } ?: return
        val form = AppPrefs.screenForm.value
        if (Uncontrollable.isMarked(uncontrollableRaw(), pkg, form)) return
        scope.launch {
            repeat(2) {
                delay(SEMI_READBACK_MS)
                if (gen != semiReadbackGen || AppPrefs.mode.value != RotateMode.SEMI ||
                    AppPrefs.screenForm.value != form || fgGated || readAutoRotate() != 0 ||
                    readUserRotation() != deviceToPanel(target)) return@launch
                // A change of foreground app or an unreadable display cannot prove failure.
                val front = runCatching { fgProbe.read() }.getOrNull() ?: return@launch
                if (front.pkg != pkg) return@launch
                val actual = ActiveDisplay.rotationOf(context, form) ?: return@launch
                if (panelToDevice(actual) == target) return@launch
            }
            markUncontrollable(pkg, form, target)
        }
    }

    private fun markUncontrollable(pkg: String, form: ScreenForm, target: Int) {
        val next = Uncontrollable.withMarked(uncontrollableRaw(), pkg, form)
        val ok = PrefsBridge.writeString(context.contentResolver, Uncontrollable.KEY, next)
        if (ok) {

            uncontrollableCache = next
            uncontrollableCacheAt = SystemClock.elapsedRealtime()
        }
        event(
            "半自动：$pkg 在${form.label}上写了 ${rotName(target)} 但屏幕没有变化（两次读回都没变）" +
                " ⇒ 记入实测不可控名单，此后在${form.label}上不再弹按钮" +
                if (ok) "" else "（⚠️ 写入 Settings 失败，本条只在内存里有效）"
        )

        refreshForegroundGate("实测不可控")
    }

    private fun uncontrollableRaw(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - uncontrollableCacheAt < UNCONTROLLABLE_TTL_MS) return uncontrollableCache
        PrefsBridge.readString(context.contentResolver, Uncontrollable.KEY)
            ?.let { uncontrollableCache = it }
        uncontrollableCacheAt = now
        return uncontrollableCache
    }

    private fun handleUncontrollableClearRequest(req: String) {
        val run = shouldRunClear(uncontrollableClearPrimed, uncontrollableClearSeen, req)

        uncontrollableClearPrimed = true
        if (!run) return
        uncontrollableClearSeen = req
        val had = Uncontrollable.sizeOf(uncontrollableRaw())
        PrefsBridge.delete(context.contentResolver, Uncontrollable.KEY)
        uncontrollableCache = ""
        uncontrollableCacheAt = SystemClock.elapsedRealtime()
        event("半自动：按你的请求清除了实测不可控名单（原有 $had 条）⇒ 相关应用恢复弹按钮")
    }

    private fun writeUserRotation(rot: Int): Boolean {
        val offset = installOffsetNow()
        val panel = deviceToPanel(rot)
        if (!putPanelRotation(panel)) {
            event("⚠️ 写入 user_rotation 失败（目标方向 ${rotName(rot)} → 落盘 $panel）")
            return false
        }
        lastAppliedRot = panel
        lastWriteAt = SystemClock.elapsedRealtime()
        event(
            "接管写入 user_rotation = $panel" +
                "（目标方向 ${rotName(rot)}；本屏安装朝向偏移 $offset）"
        )
        return true
    }

    private fun putPanelRotation(panel: Int): Boolean {
        if (AppPrefs.mode.value != RotateMode.SEMI ||
            ScreenRotationRestore.currentForm(context) != ScreenForm.INNER) return false
        val ok = runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.USER_ROTATION, panel)
        }.getOrDefault(false)

        if (!ok) EngineErrors.bump(EngineErrors.ROTATION_WRITE)
        return ok
    }

    private fun readUserRotation(): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION)
    }.getOrNull().also { if (it == null) EngineErrors.bump(EngineErrors.ROTATION_READ) }

    private fun readAutoRotate(): Int? = runCatching {
        Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
        )
    }.getOrNull()

    private fun releaseTakeover(restoreSystem: Boolean = true, quiet: Boolean = false) {
        if (!_ui.value.takeoverOn && !AppPrefs.isTakeoverActive()) return
        val result = if (restoreSystem) ScreenRotationRestore.restore(context) else {
            AppPrefs.setTakeoverActive(false)
            RotationRestoreResult.UNCHANGED
        }
        if (!quiet || result == RotationRestoreResult.DEFERRED || result == RotationRestoreResult.FAILED) {
            event(when (result) {
                RotationRestoreResult.DEFERRED -> "接管恢复暂缓：当前不是接管的屏幕，不改另一块屏的旋转锁定"
                RotationRestoreResult.UNCHANGED -> "交还系统旋转：保持用户当前设置"
                RotationRestoreResult.RESTORED -> "交还系统旋转：仅恢复接管屏幕的原设置"
                RotationRestoreResult.FAILED -> "接管恢复失败：保留记录，下次在原屏重试"
            })
        }
        lastAppliedRot = -1
        _ui.update { it.copy(takeoverOn = false) }
    }

    private fun recoverOrphanTakeover() {
        if (!AppPrefs.isTakeoverActive()) return
        when (ScreenRotationRestore.restore(context)) {
            RotationRestoreResult.RESTORED -> {
                Recovery.fixed = true
                event("上次接管未正常退出：已在原屏恢复旋转锁定")
            }
            RotationRestoreResult.DEFERRED -> event("上次接管记录属于另一块屏：等待回到原屏再处理")
            RotationRestoreResult.FAILED -> event("上次接管恢复失败：保留记录以便重试")
            RotationRestoreResult.UNCHANGED -> Unit
        }
    }

    private fun displayRotation(): Int {
        ActiveDisplay.rotationOf(context, AppPrefs.screenForm.value)?.let { return it }
        readUserRotation()?.let { return it }
        return runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: run {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.rotation
                }
        }.getOrDefault(0)
    }

    private fun installOffsetNow(): Int = PanelOrientation.offsetFor(AppPrefs.screenForm.value)

    private fun panelToDevice(panel: Int): Int = PanelOrientation.toDevice(panel, installOffsetNow())

    private fun deviceToPanel(device: Int): Int = PanelOrientation.toPanel(device, installOffsetNow())

    private fun currentDeviceRotation(): Int = panelToDevice(displayRotation())

    private fun event(msg: String) {
        Log.i(TAG, msg)
        _ui.update { s ->
            s.copy(events = (listOf(ts() + " " + msg) + s.events).take(16))
        }
    }

    private fun ts(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun refreshRotationUi() {
        _ui.update {
            it.copy(
                displayRotation = displayRotation(),
                panelOffset = PanelOrientation.installOffset(context),
            )
        }
    }

    private fun syncForm() {
        AppPrefs.syncScreenForm(context)
    }

    private fun engageTakeover() {
        if (!started || AppPrefs.mode.value != RotateMode.SEMI || (fgGated && AppPrefs.handoffRotate.value)) return
        val granted = Settings.System.canWrite(context)
        _ui.update { it.copy(writeSettingsGranted = granted) }
        if (!granted) return
        val form = ScreenRotationRestore.currentForm(context) ?: return
        if (form != ScreenForm.INNER || form != AppPrefs.screenForm.value) return
        if (AppPrefs.isTakeoverActive() && AppPrefs.takeoverForm() != form) return
        val auto = readAutoRotate() ?: return
        if (_ui.value.takeoverOn && auto == 0) return
        if (auto != 0) {
            // USER_ROTATION may still contain portrait while the system displays landscape.
            // Seed the lock from the active inner display before disabling auto-rotation.
            // Never substitute gravity, an outer-screen value, or a zero fallback here.
            val visible = runCatching { ActiveDisplay.rotationOf(context, AppPrefs.screenForm.value) }
                .getOrNull()?.takeIf { it in 0..3 } ?: run {
                    event("接管暂缓：当前屏幕方向暂不可读")
                    return
                }
            val locked = preserveRotationForTakeover(auto, visible, ::putPanelRotation) {
                if (!AppPrefs.beginTakeover(form, auto)) return@preserveRotationForTakeover false
                runCatching {
                    Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                }.getOrDefault(false)
            }
            if (!locked || readAutoRotate() != 0) {
                releaseTakeover(quiet = true)
                event("接管失败：系统旋转锁定未生效")
                return
            }
            event("接管旋转：保留系统当前方向 ${rotName(visible)}")
        } else if (!AppPrefs.isTakeoverActive()) {
            if (!AppPrefs.beginTakeover(form, AppPrefs.AUTO_ROTATE_UNTOUCHED)) return
        }
        _ui.update { it.copy(takeoverOn = true) }
    }

    object Recovery { @Volatile var fixed = false }

    private val formCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onMain {
            syncForm()
            formSettleJob?.cancel()
            formSettleJob = scope.launch { delay(FORM_SETTLE_MS); syncForm() }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    private fun installFormWatch() {
        AppPrefs.setScreenFormListener { form -> onMain { onScreenFormChanged(form) } }
        syncForm()
        if (!formWatchInstalled) {
            context.registerComponentCallbacks(formCallback)
            formWatchInstalled = true
        }
    }

    private fun onScreenFormChanged(form: ScreenForm) {
        cancelSemiSettle()
        semiReadbackGen++
        semiLastTarget = -1
        semiHintAtMs = 0
        overlayRef?.hide()
        if (AppPrefs.mode.value == RotateMode.SYSTEM) releaseTakeover(quiet = true)
        // The mode collector takes over the new screen using its actual display rotation.
        refreshRotationUi()
        event("屏幕切换 → ${form.label}：使用系统展开方向")
    }

    private fun watchScreenForm() {
        val probe = runCatching { ScreenForm.probe(context) }.getOrNull() ?: return
        _ui.update { it.copy(formProbeDp = probe.smallestWidthDp) }
        if (probe.form == AppPrefs.screenForm.value || formSettleJob?.isActive == true) return
        formSettleJob = scope.launch { delay(FORM_SETTLE_MS); syncForm() }
    }

    companion object { private const val TAG = "HyperPlusRotation" }
}
