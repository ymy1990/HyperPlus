package cn.dsr213.hyperplus.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.dsr213.hyperplus.AppPrefs
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun FunctionPage(
    appVersion: String,

    probed: Boolean,

    hosted: Boolean,
    hostState: ModuleLink.State?,
    onOpen: (Route) -> Unit,
) {

    val modeInner by AppPrefs.modeInner.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.function_title),
                largeTitle = stringResource(R.string.function_title),

                subtitle = "HyperPlus $appVersion",
            )
        },
    ) { padding ->
        HomeColumn(padding) {

            DonateCard()

            SectionCard {
                ArrowPreference(
                    title = engineHeadline(ctx, probed, hosted, hostState),
                    summary = engineOneLiner(ctx, probed, hosted, hostState),
                    onClick = { onOpen(Route.Status) },
                )
            }

            SectionCard(title = stringResource(R.string.function_section_rotation)) {

                ArrowPreference(
                    title = stringResource(R.string.function_rotation_title),

                    summary = stringResource(
                        R.string.function_rotation_summary,
                        stringResource(modeInner.labelRes),
                    ),
                    onClick = { onOpen(Route.Rotation) },
                )
            }

        }
    }
}

private fun engineHeadline(
    ctx: Context,
    probed: Boolean,
    hosted: Boolean,
    hs: ModuleLink.State?,
): String = when {
    !probed -> ctx.getString(R.string.function_state_probing)
    hs == null -> ctx.getString(R.string.function_state_noservice)
    !hosted -> ctx.getString(R.string.function_state_offline)
    else -> ctx.getString(R.string.function_state_running)
}

private fun engineOneLiner(
    ctx: Context,
    probed: Boolean,
    hosted: Boolean,
    hs: ModuleLink.State?,
): String = when {

    !probed -> ctx.getString(R.string.function_state_probing_long)
    hs == null ->

        ctx.getString(R.string.function_no_report)
    !hosted ->

        ctx.getString(
            R.string.function_lost,
            hs.heartbeatAgoSec,
            ModuleLink.HOST_LOST_AFTER_SEC,
        )
    hs.mode == RotateMode.SYSTEM.name -> ctx.getString(R.string.function_mode_system)

    !hs.takeover -> takeoverNote(ctx, hs) ?: ctx.getString(R.string.function_not_active)
    hs.mode == RotateMode.SEMI.name ->
        semiStatusText(ctx, hs.semiShown, hs.semiTapped, hs.overlayOk, hs.overlayType)
    hs.rotation < 0 -> ctx.getString(R.string.function_await_first)

    else -> ctx.getString(R.string.ro_rotation, rotName(ctx, hs.rotation))
}
