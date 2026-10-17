package io.github.erenche.syncclipboard.xposed.hook

import android.content.Context
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于「剪贴板与常用语 5.7.0」的验证 Hook。
 *
 * getClipboardData 很短，ART 可能把它内联到弹窗刷新 Runnable 中，导致单纯 Hook
 * 数据方法不触发。因此这里同时 Hook 弹窗的后台刷新 Runnable，在它发布 UI 刷新
 * 消息之前替换列表字段。
 */
object PhraseExperimentHook {
    private const val TAG = "PhraseExperimentHook"
    private const val CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"
    private const val CLIPBOARD_MODEL = "com.miui.inputmethod.ClipboardContentModel"
    private const val POPUP = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val POPUP_LOADER = "$POPUP\$2"
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

            fun createModels(): ArrayList<Any> {
                val now = System.currentTimeMillis()
                return ArrayList<Any>(testTexts.size).apply {
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
                        add(fromJson.invoke(null, json))
                    }
                }
            }

            // 兜底：未被内联时直接替换返回值。
            runCatching { module.deoptimize(getClipboardData) }
                .onFailure { Logger.warn(TAG, "deoptimize getClipboardData failed: ${it.message}") }
            module.hook(getClipboardData).intercept {
                val injected = createModels()
                Logger.info(TAG, "Injected ${injected.size} clipboard items via getClipboardData")
                injected
            }

            // 主路径：getClipboardData 被内联时，在弹窗刷新任务结束、UI 更新消息执行前替换字段。
            val popupClass = Class.forName(POPUP, false, classLoader)
            val loaderClass = Class.forName(POPUP_LOADER, false, classLoader)
            val outerField = loaderClass.getDeclaredField("this\$0").apply { isAccessible = true }
            val allListField = popupClass.getDeclaredField("mAllClipboardList").apply { isAccessible = true }
            val currentListField = popupClass.getDeclaredField("mCurrentImeClipboardList").apply { isAccessible = true }
            val runMethod = loaderClass.getMethod("run")

            module.hook(runMethod).intercept { chain ->
                val result = chain.proceed()
                val popup = outerField.get(chain.thisObject)
                val injected = createModels()
                allListField.set(popup, injected)
                currentListField.set(popup, injected)
                Logger.info(TAG, "Injected ${injected.size} clipboard items before popup refresh")
                result
            }

            Logger.info(TAG, "Hooks installed on getClipboardData and $POPUP_LOADER.run()")
        }.onFailure {
            Logger.error(TAG, "Failed to install clipboard experiment hook", it)
        }
    }
}