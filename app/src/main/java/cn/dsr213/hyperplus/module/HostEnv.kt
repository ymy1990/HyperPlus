package cn.dsr213.hyperplus.module

import android.content.Context

internal object HostEnv {
    fun appContext(host: Context, pkg: String): Context? = runCatching {
        host.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()
}
