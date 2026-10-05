package cn.dsr213.hyperplus.module

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cn.dsr213.hyperplus.RotationEngine
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.BOOT_BREAKER_THRESHOLD
import cn.dsr213.hyperplus.BOOT_HEALTHY_WINDOW_MS
import cn.dsr213.hyperplus.EngineErrors
import cn.dsr213.hyperplus.PrefsBridge
import cn.dsr213.hyperplus.bootBreakerTripped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

internal object EngineHost {

    private const val TAG = HyperPlusModule.TAG

    private const val PUBLISH_MIN_INTERVAL_MS = 2_000L

    private const val HEARTBEAT_PERIOD_MS = 5_000L

    @Volatile private var engine: RotationEngine? = null
    @Volatile private var starting = false

    @Volatile private var dead = false

    @Volatile private var bootThread: HandlerThread? = null

    @Volatile private var resumeHostCtx: Context? = null
    @Volatile private var resumeAppCtx: Context? = null
    @Volatile private var resumeClassLoader: ClassLoader? = null

    @Volatile private var breakerWatchInstalled = false

    @Volatile private var startedAtMs = 0L

    val isDead: Boolean get() = dead

    fun start(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {
        if (starting || engine != null || dead) {
            Log.i(TAG, "引擎已启动 / 正在启动 / 已停用，跳过（starting=$starting engine=${engine != null} dead=$dead）")
            return
        }

        resumeHostCtx = hostCtx
        resumeAppCtx = appCtx
        resumeClassLoader = classLoader

        val attempts = PrefsBridge.readInt(hostCtx.contentResolver, PrefsBridge.BOOT_ATTEMPTS, 0)
        if (bootBreakerTripped(attempts)) {
            Log.w(
                TAG,
                "⛔ 启动熔断已生效（连续失败 $attempts 次 ≥ $BOOT_BREAKER_THRESHOLD）" +
                    "—— 本次不再启动引擎，以免系统界面继续反复重启",
            )
            publishHalted(hostCtx, attempts)
            installBreakerResumeWatch()
            return
        }

        starting = true

        val t = HandlerThread("hyperplus-engine-boot").apply { start() }
        bootThread = t
        Handler(t.looper).post {
            runCatching { bootOn(hostCtx, appCtx, classLoader) }
                .onFailure {
                    Log.e(TAG, "引擎启动过程异常（已吞掉，不影响 SystemUI）", it)
                    starting = false
                    panic(hostCtx, "启动异常", it)
                }
        }
    }

    private fun bootOn(hostCtx: Context, appCtx: Context?, classLoader: ClassLoader?) {

        val cr = hostCtx.contentResolver
        runCatching {
            val before = PrefsBridge.readInt(cr, PrefsBridge.BOOT_ATTEMPTS, 0)
            PrefsBridge.writeInt(cr, PrefsBridge.BOOT_ATTEMPTS, before + 1)
            Log.i(
                TAG,
                "启动计数 ${before + 1}（健康运行 ${BOOT_HEALTHY_WINDOW_MS / 1000} 秒后清零）",
            )
        }.onFailure { Log.w(TAG, "启动计数写失败（不影响本次启动）", it) }

        runCatching { ModulePrefs.attachTransport(hostCtx) }
            .onFailure { Log.w(TAG, "装配配置广播通道失败（配置下发会一直停在默认值）", it) }

        AppPrefs.initHost(hostCtx)

        Handler(Looper.getMainLooper()).post {
            runCatching {

                val eng = RotationEngine(hostCtx) { e -> panic(hostCtx, "引擎内部异常", e) }
                engine = eng
                eng.start()
                startedAtMs = SystemClock.elapsedRealtime()
                starting = false
                publishPhase(hostCtx, "ready")

                installStatePublisher(hostCtx, eng)
                installLivenessTick(hostCtx)
                Log.i(TAG, "✅ 引擎已在 SystemUI 内启动（常驻，不随前台/后台变化）")
            }.onFailure {
                starting = false
                Log.e(TAG, "引擎启动失败（已吞掉，不影响 SystemUI）", it)
                panic(hostCtx, "启动失败", it, publishFailure = true)
            }
        }
    }

    private fun panic(hostCtx: Context, why: String, e: Throwable?, publishFailure: Boolean = false) {
        if (dead) return
        dead = true
        Log.e(TAG, "⚠️ 引擎停用（$why）—— 已还原系统自动旋转，按钮旋转已停用", e)

        runCatching { engine?.stop() }
        engine = null

        runCatching {
            if (AppPrefs.isTakeoverActive()) {

                val target = AppPrefs.restoreTarget()
                val cur = Settings.System.getInt(
                    hostCtx.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    1,
                )
                if (cur == 0 && target != AppPrefs.AUTO_ROTATE_UNTOUCHED) {
                    Settings.System.putInt(
                        hostCtx.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        target,
                    )
                }
                AppPrefs.setTakeoverActive(false)
            }
        }

        if (publishFailure) {
            val msg = "v1|phase=failed|err=" + (e?.javaClass?.simpleName ?: why)
            runCatching { PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.STATE, msg) }
        } else {
            publishPhase(hostCtx, "stopped")
        }
    }

    private fun installStatePublisher(hostCtx: Context, eng: RotationEngine) {
        val cr = hostCtx.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            var last = ""
            var lastAt = 0L

            combine(eng.ui, AppPrefs.configOk, AppPrefs.configDiag) { ui, _, _ -> ui }
                .map { summary(it) }.distinctUntilChanged().collect { line ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < PUBLISH_MIN_INTERVAL_MS) {
                    delay(PUBLISH_MIN_INTERVAL_MS - (now - lastAt))
                }
                if (line == last) return@collect
                last = line
                lastAt = SystemClock.elapsedRealtime()
                runCatching {
                    PrefsBridge.writeString(cr, PrefsBridge.STATE, line)
                }.onFailure { Log.w(TAG, "状态上报失败", it) }
            }
        }
    }

    private fun installLivenessTick(hostCtx: Context) {
        val cr = hostCtx.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            while (!dead) {
                runCatching {
                    PrefsBridge.writeInt(
                        cr,
                        PrefsBridge.HEARTBEAT,
                        (SystemClock.elapsedRealtime() / 1000).toInt(),
                    )
                }.onFailure { Log.w(TAG, "心跳写失败", it) }
                delay(HEARTBEAT_PERIOD_MS)
            }
        }

        scope.launch {
            delay(BOOT_HEALTHY_WINDOW_MS)
            if (dead) {
                Log.i(TAG, "健康窗口到点，但引擎已被停用 ⇒ 保留启动计数（不下调）")
                return@launch
            }
            val ok = PrefsBridge.writeInt(cr, PrefsBridge.BOOT_ATTEMPTS, 0)
            Log.i(TAG, "引擎健康运行 ${BOOT_HEALTHY_WINDOW_MS / 1000} 秒 ⇒ 启动计数清零（$ok）")
        }
    }

    private fun publishPhase(hostCtx: Context, phase: String) {
        runCatching {
            PrefsBridge.writeString(hostCtx.contentResolver, PrefsBridge.STATE, "v1|phase=$phase")
        }
    }

    private fun publishHalted(hostCtx: Context, attempts: Int) {
        runCatching {
            PrefsBridge.writeString(
                hostCtx.contentResolver,
                PrefsBridge.STATE,
                "v1|phase=halted|attempts=$attempts",
            )
        }.onFailure { Log.w(TAG, "熔断态上报失败", it) }
    }

    private fun installBreakerResumeWatch() {
        if (breakerWatchInstalled) return
        breakerWatchInstalled = true

        resumeHostCtx?.let { h ->
            runCatching { ModulePrefs.attachTransport(h) }
                .onFailure { Log.w(TAG, "熔断态：装配配置广播通道失败", it) }
        }

        if (!ModulePrefs.transportReady) {
            Log.w(
                TAG,
                "熔断态：配置广播通道没装上（${ModulePrefs.diag}）" +
                    "⇒ 收不到 App 的「重新启用」，只能靠 App 借 root 清零 / adb 清键",
            )
            return
        }

        var last = runCatching { ModulePrefs.getString(PrefsBridge.BREAKER_RESET, null) }.getOrNull()
        ModulePrefs.subscribe {
            val raw = runCatching { ModulePrefs.getString(PrefsBridge.BREAKER_RESET, null) }
                .getOrNull() ?: return@subscribe
            if (raw == last) return@subscribe
            last = raw
            Log.i(TAG, "收到熔断复位请求（$raw）⇒ 清零启动计数并重新尝试启动")

            val h = resumeHostCtx ?: return@subscribe
            runCatching { PrefsBridge.writeInt(h.contentResolver, PrefsBridge.BOOT_ATTEMPTS, 0) }
                .onFailure { Log.w(TAG, "熔断计数清零失败", it) }

            runCatching { start(h, resumeAppCtx, resumeClassLoader) }
                .onFailure { Log.w(TAG, "熔断复位后重试启动失败", it) }
        }
        Log.w(
            TAG,
            "熔断已生效：不再自动启动。等用户在 App 里点「重新启用」（监听键 ${PrefsBridge.BREAKER_RESET}）",
        )
    }

    fun summary(s: RotationEngine.UiState): String = buildString {
        append("v1")
        append("|phase=ready")
        append("|mode=").append(s.mode.name)
        append("|takeover=").append(if (s.takeoverOn) 1 else 0)
        append("|rot=").append(s.lastSemiTarget)
        append("|disp=").append(s.displayRotation)

        append("|fg=").append(if (s.foregroundGated) 1 else 0)
        append("|fgpkg=").append(s.foregroundPkg)
        append("|fgori=").append(s.foregroundOrientation)

        append("|fgform=").append(s.foregroundForm)

        append("|swdp=").append(s.formProbeDp)

        append("|fgstop=").append(s.foregroundStop)
        append("|fgr=").append(if (s.foregroundReadable) 1 else 0)
        append("|gate=").append(s.skipGateCount)
        append("|handoff=").append(if (s.handoffRotate) 1 else 0)

        append("|gateon=").append(if (s.gateEnabled) 1 else 0)

        append("|uc=").append(s.uncontrollableCount)

        append("|semi=").append(s.semiShownCount)
        append("|semitgt=").append(s.lastSemiTarget)
        append("|semitap=").append(s.semiTappedCount)
        append("|ovl=").append(if (s.overlayUsable) 1 else 0)

        append("|hintAlive=").append(if (s.hintAlive) 1 else 0)

        append("|hint=").append(AppPrefs.hintMs.value)
        append("|ovlt=").append(s.overlayType)
        append("|sensor=").append(if (s.sensorAvailable) 1 else 0)
        append("|grant=").append(if (s.writeSettingsGranted) 1 else 0)
        append("|uptime=").append(engineUptimeSec())
        append("|startedAt=").append(if (startedAtMs <= 0L) 0L else startedAtMs / 1000)

        append("|cfgold=").append(if (AppPrefs.configOk.value) 1 else 0)
        append("|cfgmsg=").append(AppPrefs.configDiag.value.replace('|', '/'))

        append("|wlN=").append(s.whitelistSize)
        append("|pofs=").append(s.panelOffset)

        append("|errs=").append(EngineErrors.snapshot())
    }

    private fun engineUptimeSec(): Int {
        val t = startedAtMs
        if (t <= 0L) return 0
        return ((SystemClock.elapsedRealtime() - t) / 1000).toInt()
    }
}
