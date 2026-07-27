package com.ggufserver.app.plugin

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ggufserver.app.ServerBus
import com.ggufserver.app.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 一个 WebView 服务哪一种形态：service.js 的隐藏后台 / ui.html 的可见面板 / settings.html 的设置卡片 /
 *  消息卡片（§6.12：聊天列表里插件自绘的一条消息）。 */
enum class WebViewKind { SERVICE, PANEL, SETTINGS, CARD }

/** __ggufInit 注入内容：权限清单 + 插件摘要 + 宿主快照（模型/上下文/服务状态是创建那一刻的快照，不做实时推送）。 */
private fun buildPluginInitJson(context: Context, m: PluginManifest): String {
    val ctxSize = runCatching { SettingsStore(context).contextSize }.getOrDefault(4096)
    return JSONObject()
        .put("permissions", JSONArray(m.permissions.toList()))
        .put("plugin", JSONObject().put("id", m.id).put("name", m.name).put("version", m.version))
        .put(
            "host",
            JSONObject()
                .put("appVersion", PluginManifest.HOST_API_VERSION)
                .put("model", ServerBus.activeAlias.value)
                .put("contextSize", ctxSize)
                .put("serverRunning", ServerBus.state.value.name == "RUNNING")
        )
        .toString()
}

/**
 * 插件执行时的运行时：管理隐藏 service WebView 的生命周期、把网关的钩子调用派发进 JS、
 * 处理熔断计数。面板 WebView（ui.html，第二阶段的可见容器）通过 [preparePanelWebView] /
 * [detachPanelWebView] 接入，事件转发（[broadcast]）与本对象共用同一套机制。
 *
 * 线程模型：WebView 只能在主线程创建/调用，但 [dispatch] 会被网关的 socket 工作线程
 * （经协程 runBlocking 桥接）调用；用 [mainHandler] 把实际的 WebView 操作切回主线程，
 * 调用方用 CompletableDeferred + withTimeoutOrNull 等结果，超时即视为该次钩子无返回。
 */
object PluginRuntime {
    private const val VIRTUAL_HOST = "plugin.gguf"

    /** service 引导页的合成路径（拦截器现场生成，不对应磁盘文件）。 */
    internal const val SERVICE_ENTRY = "__service__"

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class Held(val webView: WebView, val bridge: PluginBridge)
    private val serviceViews = ConcurrentHashMap<String, Held>()

    /**
     * service WebView 已经**加载完 service.js**、能接住 `__ggufDispatch` 的插件集合。
     *
     * 创建 WebView 与「页面加载完」之间有几百毫秒到几秒的窗口（App 冷启时尤其明显）。这段时间里
     * `evaluateJavascript` 打进去等于石沉大海：`__ggufDispatch` 还没定义，没人 respond，只能等超时。
     * 后果有三个，都不该发生：①白白吃掉整个钩子预算（route/curate 各 150ms）；②按「超时」记进
     * 熔断计数，**而熔断状态是落盘的**——冷启后连发三条请求就能把一个好插件永久旁路；
     * ③curate 面按「登记了却没表态」把它的工具藏起来。见 [isServiceReady] 的两个使用点。
     */
    private val serviceReady = ConcurrentHashMap.newKeySet<String>()

    /** 该插件此刻能不能接住一次 dispatch（service WebView 存在**且**已加载完）。 */
    fun isServiceReady(pluginId: String): Boolean =
        serviceViews.containsKey(pluginId) && serviceReady.contains(pluginId)
    private val panelViews = ConcurrentHashMap<String, Held>()
    private val settingsViews = ConcurrentHashMap<String, Held>()
    /** 消息卡片 WebView，键 "<pluginId>#<cardKey>"（cardKey = 消息 id）。 */
    private val cardViews = ConcurrentHashMap<String, Held>()

    // 设置卡片自适应高度：settings.html 内容高度（CSS px ≈ dp）经桥回报，PluginSettingsCard 据此撑高卡片，
    // 避免固定高度裁掉滑块等控件（面板/后台 WebView 的回报不进这里，见 PluginBridge.reportHeight）。
    private val _settingsHeights = MutableStateFlow<Map<String, Int>>(emptyMap())
    val settingsHeights: StateFlow<Map<String, Int>> = _settingsHeights.asStateFlow()
    fun updateSettingsHeight(pluginId: String, px: Int) { _settingsHeights.update { it + (pluginId to px) } }
    fun clearSettingsHeight(pluginId: String) { _settingsHeights.update { it - pluginId } }

    // 消息卡片自适应高度（同一机制）：键 "<pluginId>#<cardKey>"，卡片页 scrollHeight 经桥回报撑高消息行。
    private val _cardHeights = MutableStateFlow<Map<String, Int>>(emptyMap())
    val cardHeights: StateFlow<Map<String, Int>> = _cardHeights.asStateFlow()
    fun updateCardHeight(pluginId: String, cardKey: String, px: Int) {
        _cardHeights.update { it + ("$pluginId#$cardKey" to px) }
    }

    private data class PendingCall(val pluginId: String, val deferred: CompletableDeferred<JSONObject>)
    private val pending = ConcurrentHashMap<Int, PendingCall>()
    private val callIdSeq = AtomicInteger(0)

    /** 插件被禁用/其 WebView 被销毁时用来快速唤醒还在等待的 dispatch()，不算作失败。 */
    private class PluginUnavailable : Exception()

    /**
     * 近似的“当前会话”：最近一次本机来源（source=local）请求的 sessionId。
     * chat.current()/chat.messages() 靠它 + ChatStore 工作——真正精确的“用户当前打开的会话”
     * 要等 ChatViewModel 在后续阶段开始发送 X-GGUF-Session 头才有意义，本阶段先把机制打通。
     */
    @Volatile var lastLocalSessionId: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun noteLocalTurn(sessionId: String?) {
        if (sessionId != null) lastLocalSessionId = sessionId
    }

    /** PluginStore.plugins 变化后调用（App.kt 里订阅）：按 enabled && 声明了 hooks 或 provides
     *  （§6.13 能力提供者的钩子同样在 service.js 里响应）增删 service WebView。 */
    fun reconcile(records: List<PluginRecord>) {
        val wanted = records
            .filter { it.enabled && (it.manifest.hooks.isNotEmpty() || it.manifest.provides.isNotEmpty()) }
            .associateBy { it.manifest.id }
        for (id in serviceViews.keys.toList()) {
            if (!wanted.containsKey(id)) destroyService(id)
        }
        for ((id, rec) in wanted) {
            if (!serviceViews.containsKey(id)) createService(rec)
        }
    }

    private fun createService(rec: PluginRecord) {
        mainHandler.post {
            if (serviceViews.containsKey(rec.manifest.id)) return@post
            val wv = WebView(appContext)
            wv.setWillNotDraw(true)
            // service 页面不能用 loadDataWithBaseURL：真机上（MIUI WebView）data 文档会落到
            // chrome-error://chromewebdata，SDK 全部失效。改走与面板完全相同的
            // loadUrl + shouldInterceptRequest 路径，由拦截器合成 service 引导页（已验证可靠）。
            configureCommon(
                wv, rec.manifest, rec.dir,
                onFirstReady = {
                    // service.js 已加载完，从这一刻起 __ggufDispatch 才接得住调用（见 serviceReady）。
                    serviceReady.add(rec.manifest.id)
                    emit(rec.manifest.id, "enable", null)
                    warmUp(rec.manifest.id)
                },
            )
            val bridge = PluginBridge(rec.manifest.id, WebViewKind.SERVICE, appContext, wv)
            wv.addJavascriptInterface(bridge, "__ggufHost")
            serviceViews[rec.manifest.id] = Held(wv, bridge)
            wv.loadUrl("https://$VIRTUAL_HOST/${rec.manifest.id}/$SERVICE_ENTRY")
            wv.resumeTimers()
        }
    }

    /**
     * 预热一次分发路径（切主线程 → `evaluateJavascript` → JS 执行 → `respond` 回程），
     * 把**首次分发的冷启成本从用户的第一句话上挪走**。
     *
     * 为什么需要：串行面共享预算（route 150ms），排在后面的插件本来就只拿得到零头；若第一个
     * 处理器还是冷的，后面的直接被预算地板挡掉——**能力就在用户的第一句话上丢了**（真机 2026-07-27：
     * 「给我生成悲伤的音乐」那轮整条管线花了 480ms，排在最后的乐句插件根本没被问到）。
     *
     * 用一个 SDK 不认识的钩子名：SDK 对未知钩子**立即 respond({})**（gguf-sdk.js），所以又快又无副作用；
     * 不登记 pending、不记熔断、不记成功——这不是插件的行为，只是把路走热。
     */
    private fun warmUp(pluginId: String) {
        val held = serviceViews[pluginId] ?: return
        val callId = callIdSeq.incrementAndGet()
        val script = "__ggufDispatch($callId,\"__warmup__\",\"{}\")"
        mainHandler.post { runCatching { held.webView.evaluateJavascript(script, null) } }
    }

    private fun destroyService(id: String) {
        // **摘表要同步做，销毁才 post 到主线程**（2026-07-27 修）：[reconcile] 跑在
        // Dispatchers.Default，若 `serviceViews.remove` 也排进主线程队列，就会出现
        // 「禁用→立刻重新启用」时 reconcile#2 看见旧条目还在、以为无需重建，随后主线程才把它销毁
        // ——终态是「插件已启用、却没有 service WebView」，此后每次 dispatch 都静默返回 null，
        // 工具"点了没反应"且不记任何错。摘表同步化后这个窗口不存在。
        serviceReady.remove(id)
        val held = serviceViews.remove(id)
        for (call in pending.values) {
            if (call.pluginId == id) call.deferred.completeExceptionally(PluginUnavailable())
        }
        if (held == null) return
        mainHandler.post {
            runCatching { held.webView.evaluateJavascript("__ggufEmit(" + JSONObject.quote("disable") + ",\"null\")", null) }
            held.webView.destroy()
        }
    }

    private fun configureCommon(
        wv: WebView,
        manifest: PluginManifest,
        dir: File,
        onFirstReady: (() -> Unit)? = null,
        extraInitJs: String? = null,
    ) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.setGeolocationEnabled(false)
        // 变声/发声类插件要在（隐藏的）WebView 里用 Web Audio 播放处理后的音频，
        // 没有用户手势——必须关掉 Chromium 的"媒体播放需手势"限制
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = DenyingChromeClient()
        wv.webViewClient = PluginWebViewClient(appContext, manifest, dir, onFirstReady, extraInitJs)
    }

    private fun buildInitJson(m: PluginManifest): String = buildPluginInitJson(appContext, m)

    // ============================== 钩子分发 ==============================

    /**
     * 派发一次钩子调用，[budgetMs] 内没有 respond() 回来就返回 null（超时/无插件/异常都是 null，
     * 调用方——网关——一律当作“这个插件本轮无意见”处理，绝不因插件而阻塞转发）。
     */
    suspend fun dispatch(pluginId: String, hook: String, payload: JSONObject, budgetMs: Long): JSONObject? {
        val held = serviceViews[pluginId] ?: run {
            // 启用中的插件却没有 service WebView：不该发生（[reconcile] 会补建）。真发生时它是
            // **静默的能力丢失**——工具"点了没反应"、钩子恒不响应，却不记错也不进熔断。留一行日志
            // 让它至少可被发现（不记熔断：这不是插件的错）。
            android.util.Log.w("GGUF-gw", "dispatch $pluginId/$hook 无 service WebView（已禁用或待重建）")
            return null
        }
        // service.js 还没加载完：这次调用注定没人接。立刻当「本轮无返回」返回，**且不记熔断**——
        // 「没准备好」不是「坏了」（熔断状态落盘，冷启期连发几条就能把好插件永久旁路）。
        if (!serviceReady.contains(pluginId)) {
            // 这是**能力静默丢失**的一种（插件本轮完全没参与），所以要进用户可见的运行日志，
            // 不能只写 logcat——logcat 会滚掉，事后无从追查（2026-07-27 就为此查了很久）。
            com.ggufserver.app.ServerBus.appendLog("[插件:$pluginId] $hook 跳过：后台脚本尚未就绪（不计熔断）")
            return null
        }
        val callId = callIdSeq.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[callId] = PendingCall(pluginId, deferred)
        val script = "__ggufDispatch(" + callId + "," + JSONObject.quote(hook) + "," +
            JSONObject.quote(payload.toString()) + ")"
        mainHandler.post { runCatching { held.webView.evaluateJavascript(script, null) } }
        return try {
            val result = withTimeoutOrNull(budgetMs) { deferred.await() }
            if (result != null) {
                PluginStore.recordDispatchSuccess(pluginId)
                result
            } else {
                PluginStore.recordDispatchFailure(pluginId, "$hook 钩子超时（预算 ${budgetMs}ms 未响应）")
                null
            }
        } catch (e: PluginUnavailable) {
            null // 插件在等待期间被禁用/销毁：不算失败，静默放弃
        } catch (e: Exception) {
            PluginStore.recordDispatchFailure(pluginId, "$hook 钩子异常：${e.message}")
            null
        } finally {
            pending.remove(callId)
        }
    }

    /** JS 经 __ggufHost.respond(callId, resultJson) 回调，完成一次 dispatch。 */
    fun respond(callId: Int, resultJson: String) {
        val call = pending[callId] ?: return
        val obj = runCatching { JSONObject(resultJson) }.getOrNull()
        if (obj == null || obj.has("__error")) {
            call.deferred.completeExceptionally(RuntimeException(obj?.optString("__error") ?: "插件返回值不是合法 JSON"))
        } else {
            call.deferred.complete(obj)
        }
    }

    /** handle 钩子的 ctl.status(text) 进度文案：本阶段没有专门的 UI 承接，先落到运行日志里。 */
    fun status(callId: Int, text: String) {
        val call = pending[callId] ?: return
        ServerBus.appendLog("[插件:${call.pluginId}] $text")
    }

    // ============================== 事件 / 广播 ==============================

    fun emit(pluginId: String, name: String, payload: JSONObject?) {
        serviceViews[pluginId]?.let { emitTo(it, name, payload) }
        panelViews[pluginId]?.let { emitTo(it, name, payload) }
        settingsViews[pluginId]?.let { emitTo(it, name, payload) }
        for ((key, held) in cardViews) if (key.startsWith("$pluginId#")) emitTo(held, name, payload)
    }

    private fun emitTo(held: Held, name: String, payload: JSONObject?) {
        val payloadJson = payload?.toString() ?: "null"
        val script = "__ggufEmit(" + JSONObject.quote(name) + "," + JSONObject.quote(payloadJson) + ")"
        mainHandler.post { runCatching { held.webView.evaluateJavascript(script, null) } }
    }

    /** 同插件 UI ↔ service 互通：一侧广播，转发给另一侧（如果它当前存在）。
     *  设置卡片(SETTINGS)、面板(PANEL)、消息卡片(CARD)同属「可见前端」，都与后台 service 互通；
     *  service 广播则回给全部前端（含当前挂在聊天列表里的所有卡片）。 */
    fun broadcast(pluginId: String, fromKind: WebViewKind, name: String, payload: JSONObject?) {
        when (fromKind) {
            WebViewKind.SERVICE -> {
                panelViews[pluginId]?.let { emitTo(it, name, payload) }
                settingsViews[pluginId]?.let { emitTo(it, name, payload) }
                for ((key, held) in cardViews) if (key.startsWith("$pluginId#")) emitTo(held, name, payload)
            }
            WebViewKind.PANEL, WebViewKind.SETTINGS, WebViewKind.CARD ->
                serviceViews[pluginId]?.let { emitTo(it, name, payload) }
        }
    }

    // ============================== 面板 WebView（第二阶段接入点） ==============================

    /**
     * 第二阶段的面板容器（顶部下拉面板等）创建好一个空 WebView 后调用本方法完成收尾：
     * 配置 WebView 设置、注入桥、加载 ui.html。必须在主线程调用（Compose AndroidView 的
     * factory lambda 本就在主线程，无需再切线程）。返回 false = 插件不存在，调用方应放弃展示。
     */
    fun preparePanelWebView(webView: WebView, pluginId: String): Boolean {
        val rec = PluginStore.plugins.value.firstOrNull { it.manifest.id == pluginId } ?: return false
        configureCommon(webView, rec.manifest, rec.dir)
        val bridge = PluginBridge(pluginId, WebViewKind.PANEL, appContext, webView)
        webView.addJavascriptInterface(bridge, "__ggufHost")
        panelViews[pluginId] = Held(webView, bridge)
        webView.loadUrl("https://$VIRTUAL_HOST/$pluginId/${rec.manifest.entryUi}")
        return true
    }

    /** 收起面板时（禁用插件 / 内存回收）销毁；懒创建下次唤出时重新走 [preparePanelWebView]。 */
    fun detachPanelWebView(pluginId: String) {
        panelViews.remove(pluginId)?.webView?.destroy()
    }

    /**
     * 设置卡片 WebView（插件管理页展开时渲染 settingsCard 声明的 html）。与面板同源、同桥、同权限，
     * 只是入口文件不同、独立追踪（面板与设置卡片可各自存在）。返回 false = 插件不存在或未声明设置卡片。
     */
    fun prepareSettingsWebView(webView: WebView, pluginId: String): Boolean {
        val rec = PluginStore.plugins.value.firstOrNull { it.manifest.id == pluginId } ?: return false
        val card = rec.manifest.settingsCard ?: return false
        configureCommon(webView, rec.manifest, rec.dir)
        val bridge = PluginBridge(pluginId, WebViewKind.SETTINGS, appContext, webView)
        webView.addJavascriptInterface(bridge, "__ggufHost")
        settingsViews[pluginId] = Held(webView, bridge)
        webView.loadUrl("https://$VIRTUAL_HOST/$pluginId/$card")
        return true
    }

    /** 折叠插件行 / 离开插件管理页时销毁设置卡片 WebView。 */
    fun detachSettingsWebView(pluginId: String) {
        settingsViews.remove(pluginId)?.webView?.destroy()
    }

    // ============================== 消息卡片 WebView（§6.12） ==============================

    /**
     * 消息卡片 WebView：聊天列表里插件自绘的一条消息。与面板同源、同桥、同权限；每条卡片一个
     * WebView，随 LazyColumn 滚动懒创建/回收（状态要靠 __ggufCard.data 与 gguf.storage 重建）。
     * 宿主在 SDK 之前注入 `window.__ggufCard = { key, data }`；高度由页面 scrollHeight 自动回报。
     * 必须主线程调用（AndroidView factory 天然满足）。返回 false = 插件不存在或未启用。
     */
    fun prepareCardWebView(webView: WebView, pluginId: String, cardKey: String, file: String, dataJson: String): Boolean {
        val rec = PluginStore.plugins.value.firstOrNull { it.manifest.id == pluginId && it.enabled } ?: return false
        val dataObj = runCatching { JSONObject(dataJson) }.getOrDefault(JSONObject())
        val extra = "window.__ggufCard=" +
            JSONObject().put("key", cardKey).put("data", dataObj).toString() + ";"
        configureCommon(webView, rec.manifest, rec.dir, extraInitJs = extra)
        val bridge = PluginBridge(pluginId, WebViewKind.CARD, appContext, webView, cardKey)
        webView.addJavascriptInterface(bridge, "__ggufHost")
        cardViews["$pluginId#$cardKey"] = Held(webView, bridge)
        webView.loadUrl("https://$VIRTUAL_HOST/$pluginId/$file")
        return true
    }

    /** 卡片滚出列表被回收 / 插件禁用时销毁（高度保留，重建前占位不跳动）。 */
    fun detachCardWebView(pluginId: String, cardKey: String) {
        cardViews.remove("$pluginId#$cardKey")?.webView?.destroy()
    }

    fun notifyPanelShow(pluginId: String) = emit(pluginId, "show", null)
    fun notifyPanelHide(pluginId: String) = emit(pluginId, "hide", null)
}

/** 插件 WebView 一律拒绝地理位置/摄像头/麦克风权限申请（§5：面板运行环境的硬性限制）。 */
private class DenyingChromeClient : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }
}

/**
 * 插件目录经虚拟域名 https://plugin.gguf/<id>/... 提供服务（§5）：
 * - `/_sdk/gguf-sdk.js` 固定映射到 APK assets 里的 SDK 垫片；
 * - `/<id>/...` 映射到该插件的安装目录，越权访问其它插件目录一律拒绝；
 * - 其余域名按 net:lan / net:internet 权限放行或拦截（默认全部拦截）。
 *
 * ui.html 一类的 HTML 文件在返回前做一次轻量文本注入：紧跟 <head>（或 <body>）之后插入
 * gguf-sdk.js 的 <script> 标签，保证它在插件自己的脚本之前执行——不需要 androidx.webkit
 * 的 WebViewAssetLoader（那是新依赖，本项目零新增 Gradle 依赖），platform 自带的
 * shouldInterceptRequest + WebResourceResponse 已经够用。
 */
private class PluginWebViewClient(
    private val context: Context,
    private val manifest: PluginManifest,
    private val pluginDir: File,
    private val onFirstReady: (() -> Unit)? = null,
    /** 额外注入的初始化 JS（消息卡片的 window.__ggufCard），在 SDK 之前执行。 */
    private val extraInitJs: String? = null,
) : WebViewClient() {

    private val assets: AssetManager get() = context.assets
    private var readyFired = false

    override fun onPageFinished(view: WebView, url: String?) {
        // service 引导页加载完成后才通知 enable 等事件——早于此的 evaluateJavascript
        // 会打在尚未就绪的文档上（__ggufDispatch is not defined）。
        if (!readyFired && onFirstReady != null && url?.contains(PluginRuntime.SERVICE_ENTRY) == true) {
            readyFired = true
            onFirstReady.invoke()
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != VIRTUAL_HOST) {
            return if (networkAllowed(url.host)) null else deny()
        }
        val path = url.path?.removePrefix("/").orEmpty()
        if (path == "_sdk/gguf-sdk.js") {
            return try {
                WebResourceResponse("application/javascript", "utf-8", assets.open("gguf-sdk.js"))
            } catch (e: Exception) {
                notFound()
            }
        }
        val prefix = "${manifest.id}/"
        if (!path.startsWith(prefix)) return notFound()
        val rel = path.removePrefix(prefix)
        if (rel.startsWith("__tts__/")) {
            // 语音合成产物（HostTts.synthesize，见 tts.synthesize 桥）：只从本插件自己的
            // 合成缓存目录取，文件名不允许路径穿越
            val name = rel.removePrefix("__tts__/")
            if (name.contains('/') || name.contains("..")) return deny()
            val f = File(context.cacheDir, "tts-synth/${manifest.id}/$name")
            return if (f.exists() && f.isFile) WebResourceResponse("audio/wav", null, f.inputStream()) else notFound()
        }
        if (rel.startsWith("__net__/")) {
            // 宿主代取的网络音频（net.fetchAudio 桥）：同上，只取本插件自己的网络缓存
            val name = rel.removePrefix("__net__/")
            if (name.contains('/') || name.contains("..")) return deny()
            val f = File(context.cacheDir, "plugin-net/${manifest.id}/$name")
            return if (f.exists() && f.isFile) WebResourceResponse("audio/wav", null, f.inputStream()) else notFound()
        }
        if (rel == PluginRuntime.SERVICE_ENTRY) {
            // 合成的 service 引导页：init JSON + SDK + 插件的 service.js（不落盘、不走 injectSdk）
            val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<script>window.__ggufInit=" + buildPluginInitJson(context, manifest) + ";</script>" +
                "<script src=\"https://$VIRTUAL_HOST/_sdk/gguf-sdk.js\"></script>" +
                "</head><body>" +
                "<script src=\"./${manifest.entryService}\"></script>" +
                "</body></html>"
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        val file = resolveSafe(pluginDir, rel) ?: return deny()
        if (!file.exists() || !file.isFile) return notFound()
        val mime = mimeTypeFor(file.name)
        return if (mime == "text/html") {
            val injected = injectSdk(file.readText(Charsets.UTF_8))
            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)))
        } else {
            WebResourceResponse(mime, null, file.inputStream())
        }
    }

    private fun injectSdk(html: String): String {
        val snippet = THEME_CSS +
            "<script>window.__ggufInit=" + buildPluginInitJson(context, manifest) + ";" +
            extraInitJs.orEmpty() + "</script>" +
            "<script src=\"https://$VIRTUAL_HOST/_sdk/gguf-sdk.js\"></script>"
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx >= 0) {
            val insertAt = html.indexOf('>', headIdx).let { if (it < 0) headIdx else it + 1 }
            return html.substring(0, insertAt) + snippet + html.substring(insertAt)
        }
        val bodyIdx = html.indexOf("<body", ignoreCase = true)
        if (bodyIdx >= 0) {
            val insertAt = html.indexOf('>', bodyIdx).let { if (it < 0) bodyIdx else it + 1 }
            return html.substring(0, insertAt) + snippet + html.substring(insertAt)
        }
        return snippet + html
    }

    private fun resolveSafe(base: File, rel: String): File? {
        return try {
            val target = File(base, rel).canonicalFile
            val baseCanon = base.canonicalFile
            if (target.path == baseCanon.path || target.path.startsWith(baseCanon.path + File.separator)) target else null
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "js") return "application/javascript"
        if (ext == "html" || ext == "htm") return "text/html"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun networkAllowed(host: String?): Boolean {
        host ?: return false
        val perms = manifest.permissions
        if ("net:internet" in perms) return true
        if ("net:lan" in perms) return isLoopbackOrPrivate(host)
        return false
    }

    private fun isLoopbackOrPrivate(host: String): Boolean {
        if (host == "localhost") return true
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        return false
    }

    private fun deny(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        private const val VIRTUAL_HOST = "plugin.gguf"

        /**
         * 注入插件页面的主题 CSS 变量（规范 §5：--gg-bg/--gg-fg/--gg-card/--gg-accent/--gg-muted，
         * 外加分隔线用的 --gg-line）。取值与宿主 Theme.kt 的 WarmTones 一一对应，
         * 用 prefers-color-scheme 跟随系统深浅色。
         */
        private const val THEME_CSS =
            "<style>:root{--gg-bg:#FAF9F5;--gg-fg:#2D2A26;--gg-card:#FFFFFF;--gg-accent:#C96442;" +
                "--gg-muted:#6B6557;--gg-line:#E7E3D8;}" +
                "@media (prefers-color-scheme: dark){:root{--gg-bg:#211E1B;--gg-fg:#ECE8DE;--gg-card:#292521;" +
                "--gg-accent:#D97757;--gg-muted:#A39D8D;--gg-line:#3A362F;}}</style>"
    }
}
