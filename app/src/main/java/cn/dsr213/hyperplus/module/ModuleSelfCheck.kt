package cn.dsr213.hyperplus.module

import android.content.Context
import android.provider.Settings
import android.util.Log

/** Read-only diagnostics; never opens a camera or changes rotation. */
internal object ModuleSelfCheck {
    fun run(hostCtx: Context, appCtx: Context?) {
        Log.i("HyperPlusCheck", "WRITE_SETTINGS=${Settings.System.canWrite(hostCtx)}; resources=${appCtx != null}")
    }
}
