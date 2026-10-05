package cn.dsr213.hyperplus.ui

import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.VersionChecker
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HyperPlusApp(
    appVersion: String,

    probed: Boolean,

    hosted: Boolean,
    hostState: ModuleLink.State?,

    onOpenSettings: () -> Unit,

    onRecreate: () -> Unit,

    onExit: () -> Unit,
) {

    val backStack = remember { navBackStackOf(Route.Function) }

    val blurSupported = Build.VERSION.SDK_INT >= BLUR_MIN_SDK
    val backdrop = if (blurSupported) rememberLayerBackdrop() else null

    val ctx = LocalContext.current

    val current = backStack.lastOrNull()
    val currentTab = tabOf(current)

    fun go(route: Route) {
        if (route !in backStack) backStack.add(route)
    }

    fun switchTab(tab: HomeTab) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (backStack.isEmpty()) backStack.add(tab.root) else backStack[0] = tab.root
    }

    DisposableEffect(ctx) {
        AppPrefs.syncScreenForm(ctx)
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                AppPrefs.syncScreenForm(ctx)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        }
        ctx.registerComponentCallbacks(cb)
        onDispose { ctx.unregisterComponentCallbacks(cb) }
    }

    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<VersionChecker.Release?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { VersionChecker.check(appVersion) }
            checking = false
            when (r) {

                is VersionChecker.Result.Newer -> updateInfo = r.remote

                is VersionChecker.Result.UpToDate -> if (manual) {
                    toast(ctx, ctx.getString(R.string.update_up_to_date, appVersion))
                }
                is VersionChecker.Result.Failed -> if (manual) {

                    toast(
                        ctx,
                        ctx.getString(
                            R.string.update_check_failed,
                            ctx.getString(r.reasonRes, r.detail),
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdate(manual = false) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) backStack.removeLastOrNull() else onExit()
            },
            modifier = Modifier
                .fillMaxSize()

                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            entry<Route.Function> {
                FunctionPage(
                    appVersion = appVersion,
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                    onOpen = ::go,
                )
            }
            entry<Route.Settings> {
                SettingsPage(hostState = hostState, onOpen = ::go)
            }
            entry<Route.Rotation> {
                RotationPage(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenSettings = onOpenSettings,
                )
            }
            entry<Route.Status> {
                StatusPage(
                    onBack = { backStack.removeLastOrNull() },
                    probed = probed,
                    hosted = hosted,
                    hostState = hostState,
                )
            }
            entry<Route.Diagnostics> {
                DiagnosticsPage(
                    onBack = { backStack.removeLastOrNull() },
                    hostState = hostState,
                )
            }
            entry<Route.About> {
                AboutPage(
                    onBack = { backStack.removeLastOrNull() },
                    appVersion = appVersion,
                    checking = checking,
                    onCheckUpdate = { checkUpdate(manual = true) },
                )
            }
            entry<Route.Language> {
                LanguagePage(
                    onBack = { backStack.removeLastOrNull() },
                    onRecreate = onRecreate,
                )
            }

        }

        LiquidGlassBar(
            backdrop = backdrop,
            selectedIndex = HomeTab.entries.indexOf(currentTab),
            onSelect = { switchTab(HomeTab.entries[it]) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    updateInfo?.let { rel ->
        UpdateDialog(
            remote = rel,
            current = appVersion,
            onOpen = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)))
                }
                updateInfo = null
            },
            onDismiss = { updateInfo = null },
        )
    }
}
