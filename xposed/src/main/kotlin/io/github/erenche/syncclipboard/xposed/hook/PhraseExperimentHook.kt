package io.github.erenche.syncclipboard.xposed.hook

import android.content.Context
import io.github.erenche.syncclipboard.common.util.Logger
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于「剪贴板与常用语 5.7.0」的验证 Hook。
 *
 * 剪贴板面板实际运行在输入法进程，并由 InputMethodModuleManager.loadDex 动态加载
 * 常用语 APK 的类。因此需要同时处理动态 ClassLoader 和面板刷新流程。
 */
object PhraseExperimentHook {
    private const val TAG = "PhraseExperimentHook"
    private const val CLIPBOARD_MANAGER = "com.miui.inputmethod.MiuiClipboardManager"
    private const val CLIPBOARD_MODEL = "com.miui.inputmethod.ClipboardContentModel"
    private const val POPUP = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val POPUP_LOADER = "$POPUP\$2"
    private const val IME_MODULE_MANAGER = "android.inputmethodservice.InputMethodModuleManager"
    private const val TEXT_ITEM_TYPE = 10

    private val testTexts = listOf("测试文本 1", "测试文本 2", "测试文本 3")
    private val installed = AtomicBoolean(false)
    private val loadDexHooked = AtomicBoolean(false)

    /** com.miui.phrase 自身进程使用默认 ClassLoader。 */
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (installed.get()) return

        val managerClass = runCatching {
            Class.forName(CLIPBOARD_MANAGER, false, classLoader)
        }.getOrNull() ?: return
        val modelClass = runCatching {
            Class.forName(CLIPBOARD_MODEL, false, classLoader)
        }.getOrNull() ?: return

        if (!installed.compareAndSet(false, true)) return

        runCatching {
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

            runCatching { module.deoptimize(getClipboardData) }
                .onFailure { Logger.warn(TAG, "deoptimize getClipboardData failed: ${it.message}") }
            module.hook(getClipboardData).intercept {
                val injected = createModels()
                Logger.info(TAG, "Injected ${injected.size} clipboard items via getClipboardData")
                injected
            }

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
            installed.set(false)
            Logger.error(TAG, "Failed to install clipboard experiment hook", it)
        }
    }

    /**
     * 输入法进程：先 Hook loadDex，再尝试用当前 ClassLoader 直接安装。
     * loadDex 完成后会用新的动态 ClassLoader 再次调用 install。
     */
    fun installForInputMethod(module: XposedModule, classLoader: ClassLoader) {
        if (loadDexHooked.compareAndSet(false, true)) {
            runCatching {
                val imeModuleManager = Class.forName(IME_MODULE_MANAGER, false, classLoader)
                val loadDex = imeModuleManager.declaredMethods.firstOrNull {
                    it.name == "loadDex" && it.parameterTypes.size == 2
                } ?: error("loadDex(ClassLoader, String) not found")
                loadDex.isAccessible = true

                module.hook(loadDex).intercept { chain ->
                    val result = chain.proceed()
                    val targetLoader = chain.args.firstOrNull() as? ClassLoader
                    if (targetLoader != null) {
                        Logger.info(TAG, "loadDex completed; checking dynamic classloader")
                        install(module, targetLoader)
                    }
                    result
                }
                Logger.info(TAG, "Hook installed on InputMethodModuleManager.loadDex")
            }.onFailure {
                loadDexHooked.set(false)
                Logger.error(TAG, "Failed to hook InputMethodModuleManager.loadDex", it)
            }
        }
        install(module, classLoader)
    }
}