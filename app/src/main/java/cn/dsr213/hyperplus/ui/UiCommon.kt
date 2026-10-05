package cn.dsr213.hyperplus.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val BOTTOM_BAR_RESERVE = 100.dp

private val PAGE_H_PADDING = 12.dp

@Composable
internal fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) SmallTitle(text = title)
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        insideMargin = insideMargin,
        content = content,
    )
}

@Composable
internal fun OuterScreenBanner(reason: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.errorContainer),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.outer_banner_title),

            color = MiuixTheme.colorScheme.error,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = reason,
            color = MiuixTheme.colorScheme.onErrorContainer,
            style = MiuixTheme.textStyles.paragraph,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun TextCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = SectionCard(
    title = title,
    modifier = modifier,
    insideMargin = PaddingValues(16.dp),
    content = content,
)

@Composable
internal fun BackIcon(onBack: () -> Unit, contentDescription: String? = null) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onBack) {
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = contentDescription ?: stringResource(R.string.common_back),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
internal fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = { BackIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PAGE_H_PADDING),
        ) {
            content()

            Spacer(Modifier.height(BOTTOM_BAR_RESERVE))
        }
    }
}

@Composable
internal fun HomeColumn(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE_H_PADDING),
    ) {
        content()
        Spacer(Modifier.height(BOTTOM_BAR_RESERVE))
    }
}

@Composable
internal fun kv(k: String, v: String) {
    Text(
        text = stringResource(R.string.kv_format, k, v),
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 15.sp,
    )
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.paragraph,
        modifier = modifier,
    )
}

internal fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

internal fun fmt(v: Float) =
    if (v.isFinite()) String.format(java.util.Locale.US, "%.1f", v) else "—"

internal fun fmtDur(ctx: Context, sec: Int): String = when {
    sec < 0 -> "—"
    sec < 60 -> ctx.getString(R.string.dur_sec, sec)
    sec < 3600 -> ctx.getString(R.string.dur_min_sec, sec / 60, sec % 60)
    else -> ctx.getString(R.string.dur_hr_min, sec / 3600, (sec % 3600) / 60)
}

internal fun rotName(ctx: Context, rot: Int) = when (rot) {
    0 -> ctx.getString(R.string.rot_0)
    1 -> ctx.getString(R.string.rot_1)
    2 -> ctx.getString(R.string.rot_2)
    3 -> ctx.getString(R.string.rot_3)
    else -> ctx.getString(R.string.rot_none)
}

internal fun modeSummary(ctx: Context, m: RotateMode): String = when (m) {

    RotateMode.SYSTEM -> ctx.getString(R.string.mode_summary_system)
    RotateMode.SEMI -> ctx.getString(R.string.mode_summary_semi)
}

internal fun modeName(ctx: Context, raw: String): String =
    runCatching { ctx.getString(RotateMode.valueOf(raw).labelRes) }.getOrDefault(raw)

internal fun semiStatusText(
    ctx: Context,
    shown: Int,
    tapped: Int,
    overlayOk: Boolean,
    overlayType: Int,
): String = when {
    shown > 0 && !overlayOk ->

        ctx.getString(R.string.semi_no_overlay)
    shown > 0 && overlayType == APP_OVERLAY_TYPE ->
        ctx.getString(R.string.semi_low_layer, shown, tapped)
    shown == 0 -> ctx.getString(R.string.semi_idle)
    else -> ctx.getString(R.string.semi_counts, shown, tapped)
}

internal const val APP_OVERLAY_TYPE = 2038

internal fun errsText(ctx: Context, raw: String): String {
    if (raw.isBlank()) return ctx.getString(R.string.errs_none)
    return raw.split(',').filter { it.isNotBlank() }.joinToString(ctx.getString(R.string.errs_join)) { part ->
        val k = part.substringBefore(':')
        val n = part.substringAfter(':', "").trim()
        when (k) {
            "bind" -> ctx.getString(R.string.errs_bind, n)
            "rotw" -> ctx.getString(R.string.errs_rotw, n)
            "rotr" -> ctx.getString(R.string.errs_rotr, n)
            "fg" -> ctx.getString(R.string.errs_fg, n)
            else -> ctx.getString(R.string.errs_other, k, n)
        }
    }
}

internal fun takeoverNote(ctx: Context, hs: ModuleLink.State): String? = when {
    hs.takeover -> null
    hs.foregroundGated -> ctx.getString(
        R.string.takeover_gated,
        hs.foregroundPkg.ifBlank { ctx.getString(R.string.takeover_gated_current) },
        stopReasonText(ctx, hs.foregroundStop),
    )
    !hs.writeGranted -> ctx.getString(R.string.takeover_no_grant)

    else -> ctx.getString(R.string.takeover_unknown)
}

internal fun stopReasonText(ctx: Context, raw: String): String = when (raw) {
    "WHITELIST" -> ctx.getString(R.string.stop_whitelist)
    "UNCONTROLLABLE" -> ctx.getString(R.string.stop_uncontrollable)
    else -> ctx.getString(R.string.stop_other)
}

internal fun phaseText(ctx: Context, raw: String): String = when (raw) {
    "ready" -> ctx.getString(R.string.phase_ready)
    "starting" -> ctx.getString(R.string.phase_starting)
    "stopped" -> ctx.getString(R.string.phase_stopped)
    "failed" -> ctx.getString(R.string.phase_failed)

    "halted" -> ctx.getString(R.string.phase_halted)
    else -> raw
}

internal fun engineReadoutLines(ctx: Context, hs: ModuleLink.State?): List<String> {
    if (hs == null) return emptyList()
    val out = mutableListOf<String>()
    out += ctx.getString(R.string.ro_phase, phaseText(ctx, hs.phase))
    out += ctx.getString(R.string.ro_mode, modeName(ctx, hs.mode))

    out += ctx.getString(
        R.string.ro_takeover,
        takeoverNote(ctx, hs) ?: ctx.getString(R.string.ro_takeover_active),
    )
    out += ctx.getString(R.string.ro_rotation, rotName(ctx, hs.rotation))

    out += ctx.getString(R.string.ro_display, hs.display)
    out += ctx.getString(R.string.ro_uptime, fmtDur(ctx, hs.uptimeSec))
    if (hs.heartbeatAgoSec >= 0) {
        out += ctx.getString(
            R.string.ro_heartbeat,
            hs.heartbeatAgoSec,
            ctx.getString(if (hs.hostAlive) R.string.ro_normal else R.string.ro_unresponsive),
        )
    }
    return out
}
