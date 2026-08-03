package com.ggufserver.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ggufserver.app.plugin.PluginStore
import com.ggufserver.app.plugin.ToolCall
import com.ggufserver.app.plugin.ToolRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调试专用（debug 源集，release 不会编译进去，正式包里完全不存在这个类/这个 receiver）：
 * 绕开小模型，由上位机经 adb 直接把一段工具标记体喂给插件的 `tool` 钩子——复用真实的
 * `ToolRuntime.execute` -> `PluginStore.postCard` 链路，JS 侧的确定性合成逻辑（自动配低音、
 * register 折叠、混音…）照常跑，只是标记体不再由模型生成。用于把"模型听不听话"和"合成引擎
 * 本身好不好"这两件事分开测（2026-08-03，手机上真机验证乐句多音色时，模型一被指定具体音色
 * 就整段失败，需要能单独验证 JS 合成侧）。
 *
 * 用法（手机需已装 debug 包、服务已启动，插件已启用）：
 *   adb shell am broadcast -a com.ggufserver.app.debug.SYNTH \
 *     -n com.ggufserver.app/com.ggufserver.app.debug.DebugSynthReceiver \
 *     --es plugin com.gguf.midi --es tool midi.play \
 *     --es input "tempo:96 key:Dm\npat A: D4 F4 A4 -\nplay: A A A A\ninst: melody=bell"
 *
 * `input` 里用字面 \n（反斜杠+n 两个字符，不是真换行——adb 的 extras 不好传真换行）表示换行，
 * 本类会转回真换行再喂给插件。产出的卡片经 `PluginStore.postCard` 落进"当前正在看的会话"
 * （sessionId=null），走的是插件桥路径 `gguf.card.post()` 同一条订阅，UI 侧无需改动。
 * 结果只能看 logcat（tag: DebugSynth）——没有 card 说明插件判定这份 input 不合法/拒绝了。
 *
 * 可选 `--es messages`：一段 JSON 数组字符串（[{"role":"user","content":"..."}]），冒充
 * turn.messages 喂给插件——用来测"情绪起伏弧线"是不是真的在读对话内容（不给这个参数时
 * turn 没有 messages 字段，插件会退化到默认拱形曲线，两条路径都能单独测）。
 */
class DebugSynthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra("plugin") ?: "com.gguf.midi"
        val toolName = intent.getStringExtra("tool") ?: "midi.play"
        val rawInput = intent.getStringExtra("input")
        if (rawInput.isNullOrEmpty()) {
            android.util.Log.w("DebugSynth", "missing --es input")
            return
        }
        val input = rawInput.replace("\\n", "\n")

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val rec = PluginStore.plugins.value.firstOrNull { it.manifest.id == pluginId && it.enabled }
                if (rec == null) {
                    android.util.Log.w("DebugSynth", "plugin not found or not enabled: $pluginId")
                    return@launch
                }
                val turn = JSONObject().put("source", "debug").put("sessionId", JSONObject.NULL)
                intent.getStringExtra("messages")?.let { raw ->
                    runCatching { JSONArray(raw) }.getOrNull()?.let { turn.put("messages", it) }
                }
                val outcome = ToolRuntime.execute(ToolCall(rec.manifest, toolName, input), turn)
                if (outcome.cards.length() == 0) {
                    android.util.Log.w("DebugSynth", "no card produced — plugin declined or input malformed")
                    return@launch
                }
                val card = outcome.cards.getJSONObject(0)
                PluginStore.postCard(
                    card.optString("plugin", pluginId),
                    card.optString("file", "card.html"),
                    card.optJSONObject("data")?.toString() ?: "{}",
                    null,
                )
                android.util.Log.i("DebugSynth", "posted card ok")
            } catch (e: Exception) {
                android.util.Log.w("DebugSynth", "failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
