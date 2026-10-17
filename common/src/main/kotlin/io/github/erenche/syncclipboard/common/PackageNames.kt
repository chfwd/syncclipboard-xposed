package io.github.erenche.syncclipboard.common

import io.github.erenche.syncclipboard.common.BuildConfig

/**
 * 包名常量
 */
object PackageNames {
    /** 本 App 的包名 */
    val APPLICATION: String get() = BuildConfig.APP_PACKAGE_NAME

    /** SystemUI 的包名 */
    const val SYSTEM_UI = "com.android.systemui"

    /** 实验包：由「剪贴板与常用语 5.7.0」修改包名后安装 */
    const val PHRASE_EXPERIMENT = "com.miui.phrase.experiment"

    /** Android 系统（system_server） */
    const val ANDROID = "android"
}
