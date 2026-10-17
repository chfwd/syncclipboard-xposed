package io.github.erenche.syncclipboard.xposed.hook

import android.content.Context
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于「剪贴板与常用语 5.7.0」实验包的验证 Hook。
 *
 * 剪贴板面板通过 MiuiClipboardManager.getClipboardData(Context) 取列表。
 * 这里直接返回 3 条类型为 10 的本地文本项，避免依赖实验包的 Provider/数据库。
 */
object PhraseExperimentHook {
    private const val TAG = "PhraseExperimentHook"
    private const val CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"
    private const val CLIPBOARD_MODEL = "com.miui.inputmethod.ClipboardContentModel"
    private const val TEXT_ITEM_TYPE = 10

    private val testTexts = listOf("测试文本 1", "测试文本 2", "测试文本 3")
    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        runCatching {
            val managerClass = Class.forName(CLIPBOARD_MANAGER, false, classLoader)
            val modelClass = Class.forName(CLIPBOARD_MODEL, false, classLoader)
            val fromJson = modelClass.getMethod("fromJSONObject", JSONObject::class.java)
            val getClipboardData = managerClass.getMethod("getClipboardData", Context::class.java)

            module.hook(getClipboardData).intercept {
                val now = System.currentTimeMillis()
                val injected = ArrayList<Any>(testTexts.size)
                testTexts.forEachIndexed { index, text ->
                    val content = JSONArray()
                        .put(JSONObject().put("text", text))
                        .toString()
                    val json = JSONObject()
                        .put("content", content)
                        .put("type", TEXT_ITEM_TYPE)
                        .put("time", now - index * 1_000L)
                        .put("isShow", true)
                        .put("isAcrossDevices", false)
                    injected += fromJson.invoke(null, json)
                }
                Logger.info(TAG, "Injected ${injected.size} clipboard test items")
                injected
            }
            Logger.info(TAG, "Hook installed on ${getClipboardData.toGenericString()}")
        }.onFailure {
            Logger.error(TAG, "Failed to install clipboard experiment hook", it)
        }
    }
}