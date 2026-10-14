package io.github.erenche.syncclipboard.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.erenche.syncclipboard.common.PackageNames
import io.github.erenche.syncclipboard.common.util.Logger

/** 向 system_server 同步局域网开关，接收端通过模块签名权限校验来源。 */
object LocalNetworkAccessBridge {
    const val ACTION = "io.github.erenche.syncclipboard.SET_SYSTEMUI_LOCAL_NETWORK_ACCESS"
    const val EXTRA_ENABLED = "enabled"

    fun send(context: Context, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 37) return
        runCatching {
            context.sendBroadcast(Intent(ACTION).apply {
                setPackage(PackageNames.ANDROID)
                putExtra(EXTRA_ENABLED, enabled)
            })
        }.onFailure { Logger.warn("LocalNetBridge", "Failed to send local network setting: ${it.message}") }
    }
}
