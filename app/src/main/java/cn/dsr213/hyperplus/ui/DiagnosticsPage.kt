package cn.dsr213.hyperplus.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import cn.dsr213.hyperplus.ModuleLink
import cn.dsr213.hyperplus.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DiagnosticsPage(
    onBack: () -> Unit,
    hostState: ModuleLink.State?,
) {
    val hs = hostState
    val ctx = LocalContext.current

    SubPage(title = stringResource(R.string.diag_title), onBack = onBack) {

        TextCard(title = stringResource(R.string.diag_section_trigger)) {
            if (hs == null) {
                Note(stringResource(R.string.diag_no_data))
            } else {
                kv(
                    stringResource(R.string.diag_kv_sensor),
                    stringResource(
                        if (hs.sensorAvailable) R.string.diag_sensor_on else R.string.diag_sensor_off,
                    ),
                )
                kv(
                    stringResource(R.string.diag_kv_write),
                    stringResource(if (hs.writeGranted) R.string.diag_granted else R.string.diag_missing),
                )

                kv(
                    stringResource(R.string.diag_kv_cfg),
                    when {
                        hs.cfgOk == false -> {
                            val reason = if (hs.cfgMsg.isNotEmpty()) {
                                hs.cfgMsg
                            } else {
                                stringResource(R.string.diag_cfg_no_reason)
                            }
                            stringResource(R.string.diag_cfg_not_ready_engine, reason)
                        }
                        hs.cfgOk == null -> stringResource(R.string.diag_cfg_unknown)
                        else -> stringResource(R.string.diag_cfg_ok)
                    },
                )

                kv(
                    stringResource(R.string.diag_kv_gate),
                    when {
                        !hs.foregroundReadable -> stringResource(R.string.diag_gate_unreadable)
                        hs.foregroundGated -> {
                            val reason = stopReasonText(ctx, hs.foregroundStop)
                            if (hs.handoffRotate) {
                                stringResource(R.string.diag_gate_gated_rotate, hs.foregroundPkg, reason)
                            } else {
                                stringResource(R.string.diag_gate_gated_hold, hs.foregroundPkg, reason)
                            }
                        }
                        else -> {
                            val pkg = if (hs.foregroundPkg.isNotBlank()) {
                                hs.foregroundPkg
                            } else {
                                stringResource(R.string.diag_gate_current_app)
                            }
                            stringResource(R.string.diag_gate_normal, pkg, hs.skipGateCount)
                        }
                    },
                )

                if (hs.foregroundReadable) {
                    kv(
                        stringResource(R.string.diag_kv_fg_orientation),
                        stringResource(
                            R.string.diag_fg_orientation,
                            hs.foregroundOrientation.ifEmpty { "—" },
                        ),
                    )
                }

                kv(
                    stringResource(R.string.diag_kv_whitelist),
                    stringResource(R.string.diag_whitelist_value, hs.whitelistSize),
                )

                kv(stringResource(R.string.diag_kv_uptime), fmtDur(ctx, hs.uptimeSec))

                kv(
                    stringResource(R.string.diag_kv_heartbeat),
                    when {
                        hs.heartbeatAgoSec < 0 -> "—"
                        hs.hostAlive -> stringResource(
                            R.string.diag_heartbeat_ok,
                            hs.heartbeatAgoSec,
                        )
                        else -> stringResource(
                            R.string.diag_heartbeat_bad,
                            hs.heartbeatAgoSec,
                            ModuleLink.HOST_LOST_AFTER_SEC,
                        )
                    },
                )

                kv(stringResource(R.string.diag_kv_errs), errsText(ctx, hs.errs))
            }
        }

        TextCard(title = stringResource(R.string.diag_section_events)) {
            val lines = engineReadoutLines(ctx, hs)
            if (lines.isEmpty()) {
                Note(stringResource(R.string.diag_events_none))
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
