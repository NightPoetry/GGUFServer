package com.ggufserver.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.ggufserver.app.plugin.PluginRuntime
import com.ggufserver.app.plugin.PluginStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class App : Application() {
    /** 进程生命周期的后台作用域：只用来把 PluginStore 的状态流接到 PluginRuntime 的 WebView 生命周期。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "推理服务器",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "llama-server 前台运行状态" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // 插件核心运行时初始化：PluginStore 管状态（安装目录、启用集合、KV），PluginRuntime 管
        // WebView 执行；两者故意不直接互相持有引用（解耦），靠这里订阅状态流把它们接起来——
        // 启用/禁用一个声明了 hooks 的插件时，reconcile() 据此创建/销毁对应的隐藏 service WebView。
        // 日志记录模式：给全局日志总线注入落盘目录 + 当前开关（关时 appendLog 只走内存，不碰磁盘）
        ServerBus.initFileLog(File(filesDir, "logs"), com.ggufserver.app.data.SettingsStore(this).logRecording)
        PluginStore.init(this)
        PluginRuntime.init(this)
        migrateNotesToPlugin(this)
        appScope.launch {
            PluginStore.plugins.collect { records -> PluginRuntime.reconcile(records) }
        }
    }

    /**
     * 一次性迁移（打标记防重复，见《工具插件规范》§11 迁移终态）：老版本会话 JSON 里的
     * note/noteHistory 字段搬进便签插件的私有 KV（com.gguf.notes 的 `note:<会话id>` 键），
     * 然后把这两个字段从会话文件里清掉。直接改 JSON 文件而不走 ChatStore.save——
     * 后者会把 updatedAt 刷成现在，历史抽屉的排序就乱了；迁移要对其余字段零扰动。
     * 标签（tags）是会话元数据，留在宿主，不迁移。
     */
    private fun migrateNotesToPlugin(context: Context) {
        val flag = File(context.filesDir, ".notes_migrated_v1")
        if (flag.exists()) return
        runCatching {
            val chatsDir = File(context.filesDir, "chats")
            (chatsDir.listFiles { f -> f.extension == "json" } ?: emptyArray()).forEach { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    val note = o.optString("note")
                    val history = o.optJSONArray("noteHistory") ?: JSONArray()
                    if (note.isNotBlank() || history.length() > 0) {
                        val trimmed = JSONArray()
                        val from = (history.length() - 100).coerceAtLeast(0)
                        for (i in from until history.length()) {
                            val r = history.optJSONObject(i) ?: continue
                            trimmed.put(
                                JSONObject()
                                    .put("ts", r.optLong("ts"))
                                    .put("note", r.optString("note"))
                                    .put("label", r.optString("label"))
                            )
                        }
                        PluginStore.kvSet(
                            "com.gguf.notes",
                            "note:${o.optLong("id")}",
                            JSONObject().put("note", note).put("history", trimmed)
                        )
                    }
                    if (o.has("note") || o.has("noteHistory")) {
                        o.remove("note")
                        o.remove("noteHistory")
                        f.writeText(o.toString())
                    }
                }
            }
        }
        runCatching { flag.writeText("1") }
    }

    companion object {
        const val CHANNEL_ID = "llama_server"
    }
}
