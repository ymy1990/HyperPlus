package cn.dsr213.hyperplus.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.dsr213.hyperplus.AppPrefs

import cn.dsr213.hyperplus.R
import cn.dsr213.hyperplus.RotateMode
import cn.dsr213.hyperplus.ScreenForm
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RotationPage(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {

    val modeInner by AppPrefs.modeInner.collectAsState()
    val form by AppPrefs.screenForm.collectAsState()

    val handoffRotate by AppPrefs.handoffRotate.collectAsState()
    val gateEnabled by AppPrefs.gateEnabled.collectAsState()

    val hintMs by AppPrefs.hintMs.collectAsState()
    val ctx = LocalContext.current

    SubPage(title = stringResource(R.string.rotation_title), onBack = onBack) {

        if (form == ScreenForm.OUTER) {
            OuterScreenBanner(reason = stringResource(R.string.rotation_outer_reason))
        }

        TextCard(title = stringResource(R.string.rotation_mode_section)) {
            Text(
                text = stringResource(R.string.rotation_mode_note),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
            )
        }
        SmallTitle(
            text = stringResource(
                if (form == ScreenForm.INNER) R.string.rotation_mode_title_active
                else R.string.rotation_mode_title,
            ),
        )
        ModePicker(value = modeInner)

        SectionCard(title = stringResource(R.string.rotation_section_switches)) {
            SwitchPreference(
                title = stringResource(R.string.rotation_gate_title),

                summary = stringResource(
                    if (gateEnabled) R.string.rotation_gate_on else R.string.rotation_gate_off,
                ),
                checked = gateEnabled,
                onCheckedChange = { on -> AppPrefs.setGateEnabled(on) },
            )

            if (gateEnabled) {
                SwitchPreference(
                    title = stringResource(R.string.rotation_handoff_title),
                    summary = stringResource(
                        if (handoffRotate) R.string.rotation_handoff_on
                        else R.string.rotation_handoff_off,
                    ),
                    checked = handoffRotate,
                    onCheckedChange = { on -> AppPrefs.setHandoffRotate(on) },
                )

            }

            SliderPreference(
                value = hintMs / 1000f,
                onValueChange = { AppPrefs.setHintMs((it * 1000f).roundToInt()) },
                title = stringResource(R.string.rotation_hint_title),
                summary = stringResource(R.string.rotation_hint_summary, hintMs / 1000),
                valueText = stringResource(R.string.rotation_hint_value, hintMs / 1000),
                valueRange = (AppPrefs.HINT_MS_MIN / 1000f)..(AppPrefs.HINT_MS_MAX / 1000f),
            )
        }

        TextCard(title = stringResource(R.string.rotation_preview_title)) {

            Text(
                text = stringResource(R.string.rotation_preview_body),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.paragraph,
            )
            TextButton(
                text = stringResource(R.string.rotation_preview_button),
                onClick = { AppPrefs.requestHintTest() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        AppWhitelistSection()
    }
}

@Composable
private fun ModePicker(value: RotateMode) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {

        listOf(RotateMode.SYSTEM, RotateMode.SEMI).forEach { m ->
                RadioButtonPreference(

                    title = stringResource(m.labelRes),
                    summary = modeSummary(ctx, m),
                    selected = value == m,
                    onClick = { AppPrefs.setMode(m) },
                )
            }
    }
}
