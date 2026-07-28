package com.ggufserver.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ggufserver.app.ChatMessage
import com.ggufserver.app.ChatViewModel
import com.ggufserver.app.ServerBus
import com.ggufserver.app.ServerState
import com.ggufserver.app.data.ChatSummary
import com.ggufserver.app.plugin.PluginMessageAction
import com.ggufserver.app.plugin.PluginRecord
import com.ggufserver.app.plugin.PluginRuntime
import com.ggufserver.app.plugin.PluginStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 预览聊天界面（暖色系）。
 * 顶栏右侧：历史会话、新对话、服务器图标（呼吸灯：绿=运行中，橙=重启中；点进设置）。
 * 从左边缘滑入历史会话抽屉；从右边缘滑入模型配置抽屉（System Prompt / 温度 / Top-P，应用后重启生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel(), onOpenSettings: () -> Unit) {
    val tones = LocalWarmTones.current
    val alias by ServerBus.activeAlias.collectAsStateWithLifecycle()
    val serverState by ServerBus.state.collectAsStateWithLifecycle()
    val restarting by ServerBus.restarting.collectAsStateWithLifecycle()
    val activeRequests by ServerBus.activeRequests.collectAsStateWithLifecycle()
    val model = alias.ifBlank { "local" }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)        // 左：历史会话
    val configDrawerState = rememberDrawerState(DrawerValue.Closed)  // 右：模型配置
    val scope = rememberCoroutineScope()
    // 列表用 reverseLayout：贴底时由布局系统天然锚定底边——流式增长不需要任何
    // scrollToItem（此前每个 token 瞬跳一次，看起来就是闪烁）；上滑后锚在旧消息上，
    // 内容再怎么长也纹丝不动。「贴底」= 反转后的第 0 项可见且偏移量小于阈值。
    val density = LocalDensity.current
    val atBottom by remember(density) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= with(density) { 96.dp.toPx() }
        }
    }

    // --- 交互门户（顶部下拉，选项卡：便签 / 各启用插件一个 / 浏览器） ---
    // 插件选项卡是动态的：按 PluginStore 显示顺序排在便签和浏览器之间；bypassed（熔断旁路）
    // 只影响钩子调度，不影响插件自己的 UI 面板，所以这里只按 enabled 过滤，不排除 bypassed。
    val plugins by PluginStore.plugins.collectAsStateWithLifecycle()
    val enabledPlugins = remember(plugins) { plugins.filter { it.enabled }.sortedBy { it.order } }
    // 消息角标（§6.11）：已启用且未熔断的插件声明的 messageActions，渲染进每条消息下方的动作行。
    // 与门户选项卡不同，这里要排除 bypassed——点击会派发进 service.js，熔断中的插件收不到。
    val messageActions = remember(plugins) {
        plugins.filter { it.enabled && !it.bypassed && it.manifest.messageActions.isNotEmpty() }
            .sortedBy { it.order }
            .flatMap { rec -> rec.manifest.messageActions.map { rec to it } }
    }
    // 便签选项卡不再内置——由 com.gguf.notes 插件启用后自己提供 ui.html 面板；
    // 门户内置的选项卡只剩「浏览器」，恒排最后。
    val portalTabs = remember(enabledPlugins) {
        enabledPlugins.map { PortalTabInfo.Plugin(it) } + listOf(PortalTabInfo.Browser)
    }
    var portalOpen by remember { mutableStateOf(false) }
    // 启动台（顶栏「启动台」图标下拉的半屏毛玻璃方格面板）开合 + 背后内容模糊进度
    var launchpadOpen by remember { mutableStateOf(false) }
    val launchpadBlur by animateDpAsState(
        targetValue = if (launchpadOpen) 16.dp else 0.dp,
        animationSpec = tween(240), label = "launchpad-blur"
    )
    BackHandler(enabled = launchpadOpen) { launchpadOpen = false }
    var portalTabId by rememberSaveable { mutableStateOf(PortalTabInfo.BROWSER_ID) }
    // 插件被禁用/卸载后，若门户还停在它的选项卡上，退回浏览器选项卡（内置，恒定存在）
    LaunchedEffect(portalTabs) {
        if (portalTabs.none { it.id == portalTabId }) portalTabId = PortalTabInfo.BROWSER_ID
    }
    // 插件面板的 WebView 缓存：门户收起、选项卡切走时 Compose 会把对应节点移出组合，
    // 但缓存住的 WebView 对象本身不销毁（不调用 destroy()），下次显示时把它从旧父容器摘下
    // 重新挂载即可——JS/DOM 状态原样保留，满足「收起面板保留实例」（见 preparePanelWebView）。
    // 插件真正被禁用（不在 enabledIds 里）时才清缓存 + 调 detachPanelWebView 销毁。
    val pluginWebViewCache = remember { mutableMapOf<String, WebView>() }
    LaunchedEffect(plugins) {
        val enabledIds = plugins.filter { it.enabled }.map { it.manifest.id }.toSet()
        val stale = pluginWebViewCache.keys.filter { it !in enabledIds }
        stale.forEach { id ->
            pluginWebViewCache.remove(id)
            PluginRuntime.detachPanelWebView(id)
        }
    }
    var browserHtml by remember { mutableStateOf<String?>(null) }
    var browserSeq by remember { mutableStateOf(0) }
    // 回复里出现完整 HTML 时：自动拉下门户、切到浏览器渲染（自动化演示闭环）
    LaunchedEffect(vm.htmlPreview) {
        vm.htmlPreview?.let { html ->
            browserHtml = html
            browserSeq++
            portalTabId = PortalTabInfo.BROWSER_ID
            portalOpen = true
            vm.consumeHtmlPreview()
        }
    }
    // 插件 gguf.ui.close()：如果它正是当前显示的选项卡，收起门户；toast：走 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        PluginStore.closeRequests.collect { id -> if (portalTabId == id) portalOpen = false }
    }
    LaunchedEffect(Unit) {
        PluginStore.toasts.collect { text -> snackbarHostState.showSnackbar(text) }
    }

    BackHandler(portalOpen || drawerState.isOpen || configDrawerState.isOpen) {
        when {
            drawerState.isOpen || configDrawerState.isOpen -> scope.launch {
                if (configDrawerState.isOpen) configDrawerState.close() else drawerState.close()
            }
            else -> portalOpen = false
        }
    }

    // 删除会话需确认；批量导出走系统「另存为」；标签编辑弹窗（迁自便签选项卡，见历史抽屉）
    var pendingDeleteSession by remember { mutableStateOf<ChatSummary?>(null) }
    var pendingTagEdit by remember { mutableStateOf<ChatSummary?>(null) }
    var exportIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> if (uri != null && exportIds.isNotEmpty()) vm.exportSessions(exportIds, uri) }

    // 外层用 RTL 把第二个抽屉挂到右边缘，内容再切回 LTR
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = configDrawerState,
            // 关闭时禁用内置手势（否则在门户/插件面板上左右滑也会拉出抽屉）——开抽屉只走消息区的
            // 自定义 detectHorizontalDragGestures；打开后再启用，保留滑动关闭抽屉的手感。
            gesturesEnabled = configDrawerState.isOpen,
            drawerContent = {
                ConfigDrawer(vm, restarting, onClose = { scope.launch { configDrawerState.close() } })
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // 同上：关闭时禁用内置手势，开抽屉只走消息区自定义手势，避免在门户/插件面板上误触。
                    gesturesEnabled = drawerState.isOpen,
                    drawerContent = {
                        HistoryDrawer(
                            vm,
                            onClose = { scope.launch { drawerState.close() } },
                            onRequestDelete = { pendingDeleteSession = it },
                            onRequestEditTags = { pendingTagEdit = it },
                            onExport = { ids ->
                                exportIds = ids
                                exporter.launch(
                                    "GGUF对话导出-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())}.md"
                                )
                            }
                        )
                    }
                ) {
                    Scaffold(
                        containerColor = tones.bg,
                        snackbarHost = {
                            SnackbarHost(snackbarHostState) { data ->
                                Snackbar(data, containerColor = tones.card, contentColor = tones.ink)
                            }
                        },
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = tones.bg),
                                title = { },
                                // 从顶栏区域向下滑 = 拉出交互门户（收起走面板底部的卷帘门拉手）。
                                // 过阈值就立即展开，动画跟着手指的意图走，不等松手。落点 x/屏宽 的比例
                                // 命中某启用插件的手势区间时，门户直接定位到该插件的选项卡。
                                modifier = Modifier.pointerInput(Unit) {
                                    var acc = 0f
                                    var startXFrac = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { offset ->
                                            acc = 0f
                                            startXFrac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        },
                                        onDragEnd = { }
                                    ) { change, dy ->
                                        acc += dy
                                        if (acc > 48f && !portalOpen) {
                                            matchPluginTabByX(startXFrac)?.let { portalTabId = it }
                                            portalOpen = true
                                        }
                                        change.consume()
                                    }
                                },
                                actions = {
                                    // 顶栏图标网格（用户定的规矩）：每行放几个、每个放哪里都提前算好，
                                    // 图标只是现场填进槽位——整行两端对齐、间距均匀，满员时正好铺满，
                                    // 不满时也均匀铺开、绝不在一侧留大空块。
                                    // 视觉线：容器两端 8dp + 44dp 槽内 22~26dp 图形居中（自带约 10dp 留白），
                                    // 最左图形的边缘正好落在消息区头像的 18dp 竖直线上，右端同理对称。
                                    val topbarPlugins = enabledPlugins.filter { it.manifest.panelTopbar }
                                    val screenWidthDp = LocalConfiguration.current.screenWidthDp
                                    // 槽位 44dp、分隔符约 13dp：固定功能区占 5 槽，剩余宽度全归插件直显槽
                                    val slots = ((screenWidthDp - 16 - 13) / 44).coerceAtLeast(5)
                                    val directPlugins = topbarPlugins.take((slots - 5).coerceAtLeast(0))
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        // 左区 = 直显插件快捷图标；点按 = 开门户并定位到该插件选项卡，再点同一个收起。
                                        // panel.topbar=false 的插件（纯角标/纯后台）不占直显位，启动台仍列全量兜底。
                                        directPlugins.forEach { rec ->
                                            PluginTopBarButton(
                                                rec = rec,
                                                selected = portalOpen && portalTabId == rec.manifest.id,
                                                onClick = {
                                                    if (portalOpen && portalTabId == rec.manifest.id) {
                                                        portalOpen = false
                                                    } else {
                                                        portalTabId = rec.manifest.id
                                                        portalOpen = true
                                                    }
                                                }
                                            )
                                        }
                                        // 分隔符：切开「插件快捷区」与右侧「固定功能区」（无直显插件时不画）
                                        if (directPlugins.isNotEmpty()) TopBarDivider()
                                        // 固定功能区：启动台（方格列出全部已启用插件）+ 历史 + 新对话 + 配置 + 服务灯
                                        TopBarSlot(onClick = { launchpadOpen = !launchpadOpen }) {
                                            Icon(
                                                Icons.Outlined.GridView,
                                                contentDescription = "启动台",
                                                tint = if (launchpadOpen) tones.accent else tones.muted
                                            )
                                        }
                                        TopBarSlot(onClick = { scope.launch { drawerState.open() } }) {
                                            // 方形列表图标：与右侧方形气泡、服务器机架图标形状呼应。
                                            // 该图形在 24dp 画布内边距较大、视觉上显小，放大到 26dp 补齐。
                                            Icon(
                                                Icons.AutoMirrored.Outlined.ListAlt,
                                                contentDescription = "历史会话",
                                                modifier = Modifier.size(26.dp),
                                                tint = tones.muted
                                            )
                                        }
                                        TopBarSlot(onClick = { vm.newChat() }) {
                                            Icon(Icons.Outlined.AddComment, contentDescription = "新对话", tint = tones.muted)
                                        }
                                        TopBarSlot(onClick = { scope.launch { configDrawerState.open() } }) {
                                            Icon(Icons.Outlined.Tune, contentDescription = "模型配置", tint = tones.muted)
                                        }
                                        TopBarSlot(onClick = onOpenSettings) {
                                            // 绿=运行中（常亮待命；本机聊天或远程请求处理中都呼吸闪烁），橙=重启/未就绪（闪烁）
                                            ServerIconWithLight(
                                                iconTint = tones.muted,
                                                lightColor = if (serverState == ServerState.RUNNING) tones.ok else tones.warn,
                                                ringColor = tones.bg,
                                                blinking = vm.busy || activeRequests > 0 || serverState != ServerState.RUNNING
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    ) { inner ->
                      Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(inner)
                                .consumeWindowInsets(inner)
                                .imePadding()
                                .blur(launchpadBlur)
                        ) {
                            Portal(
                                open = portalOpen,
                                tabs = portalTabs,
                                selectedId = portalTabId,
                                onOpenChange = { portalOpen = it },
                                onSelect = { portalTabId = it },
                                browserHtml = browserHtml,
                                browserSeq = browserSeq,
                                pluginWebViewCache = pluginWebViewCache,
                            )
                            // 消息区自己路由水平滑动：向左滑开模型配置，向右滑开历史。
                            // （嵌套抽屉的自带手势会被内层吃掉，右边缘滑又撞系统返回手势，所以在这里自己检测。）
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .pointerInput(Unit) {
                                        var acc = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { acc = 0f },
                                            onDragEnd = {
                                                when {
                                                    acc < -100f -> scope.launch { configDrawerState.open() }
                                                    acc > 100f -> scope.launch { drawerState.open() }
                                                }
                                            }
                                        ) { change, dragAmount ->
                                            acc += dragAmount
                                            change.consume()
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        // 消息区顶部（稍长于面板高度）= 门户下拉热区：只抢「下滑开门户」。
                                        // 在 Initial 通道抢先判定，仅在门户未开且下滑时消费（上滑留给列表滚动、
                                        // 收起门户走面板底部的卷帘门拉手）；点按 / 横滑不消费，复制、抽屉照常。
                                        awaitPointerEventScope {
                                            var startY = -1f
                                            var startXFrac = 0f
                                            var accY = 0f
                                            var accX = 0f
                                            var claimed = false
                                            var fired = false
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val c = event.changes.firstOrNull() ?: continue
                                                if (c.changedToDownIgnoreConsumed()) {
                                                    startY = c.position.y
                                                    startXFrac = (c.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                    accY = 0f; accX = 0f
                                                    claimed = false; fired = false
                                                } else if (c.pressed) {
                                                    accY += c.position.y - c.previousPosition.y
                                                    accX += c.position.x - c.previousPosition.x
                                                    // 下拉开门户的热区 = 消息区上 1/4（1.0.4 曾扩到 0.66，实用中太容易误触
                                                    // ——翻聊天记录时总不小心拉出插件面板，2026-07-18 用户要求改回）
                                                    val inZone = startY >= 0f && startY < size.height * 0.25f
                                                    if (!claimed && inZone && !portalOpen && accY > 0f &&
                                                        kotlin.math.abs(accY) > viewConfiguration.touchSlop &&
                                                        kotlin.math.abs(accY) > kotlin.math.abs(accX)
                                                    ) claimed = true
                                                    if (claimed) {
                                                        c.consume()
                                                        if (!fired && accY > 60.dp.toPx() && !portalOpen) {
                                                            fired = true
                                                            matchPluginTabByX(startXFrac)?.let { portalTabId = it }
                                                            portalOpen = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                            ) {
                                if (vm.messages.isEmpty()) {
                                    EmptyState(model, Modifier.fillMaxSize())
                                } else {
                                    // 高度按内容收缩（不 fillMaxSize）：不足一屏时列表只有内容那么高、
                                    // 自然从顶部排；超过一屏才撑满，reverseLayout 的底边锚定这时才生效。
                                    LazyColumn(
                                        state = listState,
                                        reverseLayout = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        items(vm.messages.asReversed(), key = { it.id }) { msg ->
                                            MessageRow(
                                                msg,
                                                // 只有「最后一条助手回复」提供重试/候选切换：中途消息重试会让其后的对话失去依附的上下文
                                                isLastAssistant = msg.role == "assistant" && msg.id == vm.messages.lastOrNull()?.id,
                                                busy = vm.busy,
                                                onRetry = { vm.retryLast() },
                                                onSwitchAlt = { d -> vm.switchAlt(msg.id, d) },
                                                actions = messageActions,
                                                onAction = { rec, action, m ->
                                                    // 角标点击派发进插件 service.js（预算同 afterReply 10s）；
                                                    // 结果不进对话——插件用 gguf.tts / toast 自己反馈
                                                    scope.launch {
                                                        val payload = JSONObject()
                                                            .put("actionId", action.id)
                                                            .put("sessionId", vm.currentId?.toString() ?: JSONObject.NULL)
                                                            .put(
                                                                "message",
                                                                JSONObject()
                                                                    .put("id", m.id)
                                                                    .put("role", m.role)
                                                                    .put("content", m.content)
                                                                    .put("reasoning", m.reasoning)
                                                                    .put("ts", m.ts)
                                                            )
                                                        PluginRuntime.dispatch(rec.manifest.id, "messageAction", payload, 10_000)
                                                    }
                                                },
                                                cardHost = { pid -> plugins.firstOrNull { it.manifest.id == pid && it.enabled } },
                                            )
                                        }
                                    }
                                }
                                // 上滑浏览时浮出「回到底部」；点一下跳回（回到底部即自动贴底跟随）
                                if (vm.messages.isNotEmpty() && !atBottom) {
                                    Surface(
                                        color = tones.card,
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, tones.line2),
                                        shadowElevation = 4.dp,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 12.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(36.dp)
                                                .clickable {
                                                    scope.launch { listState.scrollToItem(0) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.ArrowDownward,
                                                contentDescription = "回到底部",
                                                Modifier.size(18.dp),
                                                tint = tones.muted
                                            )
                                        }
                                    }
                                }
                            }
                            Composer(vm, model, ready = serverState == ServerState.RUNNING)
                        }
                        LaunchpadOverlay(
                            open = launchpadOpen,
                            topPadding = inner.calculateTopPadding(),
                            plugins = enabledPlugins,
                            onDismiss = { launchpadOpen = false },
                            onSelect = { id ->
                                portalTabId = id
                                portalOpen = true
                                launchpadOpen = false
                            },
                        )
                      }
                    }
                }
            }
        }
    }

    // 自己发出新消息时回到底部（贴底后布局自动跟随流式增长，无需逐 token 滚动）
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(0)
    }

    pendingDeleteSession?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${s.title}」吗？该会话的消息将从本机移除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteChat(s.id); pendingDeleteSession = null }) {
                    Text("删除", color = tones.bad)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") } }
        )
    }
    vm.exportResult?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearExportResult() },
            title = { Text("批量导出") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.clearExportResult() }) { Text("好") } }
        )
    }
    pendingTagEdit?.let { s ->
        TagEditDialog(
            initialTags = s.tags,
            onDismiss = { pendingTagEdit = null },
            onConfirm = { tags -> vm.updateTags(s.id, tags); pendingTagEdit = null }
        )
    }
}

/** 标签编辑弹窗（历史抽屉入口，迁自原便签选项卡）：逗号/空格分隔多个标签，规范化交给宿主 cleanTags。 */
@Composable
private fun TagEditDialog(initialTags: List<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val tones = LocalWarmTones.current
    var text by remember(initialTags) { mutableStateOf(initialTags.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column {
                Text("用逗号或空格分隔多个标签，用于历史会话的分类检索。", fontSize = 12.5.sp, color = tones.faint)
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(tones.card, RoundedCornerShape(10.dp))
                        .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(fontSize = 13.5.sp, color = tones.ink),
                        cursorBrush = SolidColor(tones.accent),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tags = text.split(Regex("[,，;；#、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                onConfirm(tags)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 历史会话抽屉：新对话 + 会话列表（点击切换、删除需确认、可多选批量导出）。 */
@Composable
private fun HistoryDrawer(
    vm: ChatViewModel,
    onClose: () -> Unit,
    onRequestDelete: (com.ggufserver.app.data.ChatSummary) -> Unit,
    onRequestEditTags: (com.ggufserver.app.data.ChatSummary) -> Unit,
    onExport: (Set<Long>) -> Unit,
) {
    val tones = LocalWarmTones.current
    val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }

    ModalDrawerSheet(
        drawerContainerColor = tones.side,
        modifier = Modifier.width(296.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selecting) "已选 ${selected.size} 项" else "会话记录",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, color = tones.faint,
                    modifier = Modifier.weight(1f)
                )
                if (vm.sessions.isNotEmpty()) {
                    if (selecting) {
                        DrawerAction(
                            if (selected.size == vm.sessions.size) "全不选" else "全选"
                        ) {
                            selected = if (selected.size == vm.sessions.size) emptySet()
                            else vm.sessions.map { it.id }.toSet()
                        }
                        DrawerAction("取消") { selecting = false; selected = emptySet() }
                    } else {
                        DrawerAction("导出…") { selecting = true; selected = emptySet() }
                    }
                }
            }
            if (!selecting) {
                Surface(
                    color = tones.card,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tones.line2),
                    modifier = Modifier.fillMaxWidth().clickable { vm.newChat(); onClose() }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.AddComment, null, Modifier.size(16.dp), tint = tones.muted)
                        Text("新对话", fontSize = 14.sp, color = tones.ink)
                    }
                }
                Spacer(Modifier.size(12.dp))
            }
            if (vm.sessions.isEmpty()) {
                Text("还没有历史会话", fontSize = 13.sp, color = tones.faint, modifier = Modifier.padding(4.dp))
            } else {
                // 标签智能文件夹（参考小模型轮椅）：多标签会话出现在多个文件夹；
                // 无标签归「未分类」且排最后；全都没打标签时退回平铺列表。多选导出模式也平铺。
                val untagged = "未分类"
                val groups = remember(vm.sessions) {
                    val g = LinkedHashMap<String, MutableList<ChatSummary>>()
                    vm.sessions.forEach { s ->
                        if (s.tags.isEmpty()) g.getOrPut(untagged) { mutableListOf() }.add(s)
                        else s.tags.forEach { t -> g.getOrPut(t) { mutableListOf() }.add(s) }
                    }
                    g.entries.sortedWith(
                        compareBy({ it.key == untagged }, { -(it.value.maxOf { s -> s.updatedAt }) })
                    )
                }
                val flat = selecting || (groups.size == 1 && groups.first().key == untagged)
                var collapsed by remember { mutableStateOf(setOf<String>()) }
                LazyColumn(Modifier.weight(1f)) {
                    if (flat) {
                        items(vm.sessions, key = { it.id }) { s ->
                            SessionRow(
                                vm, s, fmt, selecting, checked = s.id in selected, indent = 0.dp,
                                onToggleSelect = {
                                    selected = if (s.id in selected) selected - s.id else selected + s.id
                                },
                                onOpen = { vm.openChat(s.id); onClose() },
                                onRequestDelete = { onRequestDelete(s) },
                                onRequestEditTags = { onRequestEditTags(s) },
                            )
                        }
                    } else {
                        groups.forEach { (tag, list) ->
                            item(key = "folder:$tag") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            collapsed = if (tag in collapsed) collapsed - tag else collapsed + tag
                                        }
                                        .padding(horizontal = 6.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.ExpandMore, null,
                                        Modifier.size(15.dp).rotate(if (tag in collapsed) -90f else 0f),
                                        tint = tones.faintIcon
                                    )
                                    Text(
                                        tag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (tag == untagged) tones.faint else tones.accent,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text("${list.size}", fontSize = 11.sp, color = tones.faint)
                                }
                            }
                            if (tag !in collapsed) {
                                items(list, key = { "s:$tag:${it.id}" }) { s ->
                                    SessionRow(
                                        vm, s, fmt, selecting = false, checked = false, indent = 12.dp,
                                        onToggleSelect = {},
                                        onOpen = { vm.openChat(s.id); onClose() },
                                        onRequestDelete = { onRequestDelete(s) },
                                        onRequestEditTags = { onRequestEditTags(s) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (selecting) {
                    Button(
                        onClick = { onExport(selected); selecting = false; selected = emptySet() },
                        enabled = selected.isNotEmpty(),
                        shape = RoundedCornerShape(11.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    ) {
                        Text("导出所选（${selected.size}）", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** 历史抽屉里的单个会话行（平铺与文件夹内共用）。 */
@Composable
private fun SessionRow(
    vm: ChatViewModel,
    s: ChatSummary,
    fmt: SimpleDateFormat,
    selecting: Boolean,
    checked: Boolean,
    indent: androidx.compose.ui.unit.Dp,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onRequestDelete: () -> Unit,
    onRequestEditTags: () -> Unit,
) {
    val tones = LocalWarmTones.current
    val active = s.id == vm.currentId
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .background(if (active && !selecting) tones.userAvatar else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { if (selecting) onToggleSelect() else onOpen() }
            .padding(start = if (selecting) 0.dp else 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Checkbox(checked = checked, onCheckedChange = { onToggleSelect() })
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                fontSize = 13.5.sp, color = tones.ink2,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(fmt.format(Date(s.updatedAt)), fontSize = 11.sp, color = tones.faint)
        }
        if (!selecting) {
            // 标签编辑入口（便签选项卡下线后迁到这里，见《工具插件规范》§11 迁移终态）
            IconButton(onClick = onRequestEditTags, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Sell, contentDescription = "编辑标签", Modifier.size(16.dp), tint = tones.faint)
            }
            IconButton(onClick = onRequestDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除会话", Modifier.size(17.dp), tint = tones.faint)
            }
        }
    }
}

/** 抽屉标题行右侧的文字动作。 */
@Composable
private fun DrawerAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.5.sp, color = LocalWarmTones.current.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

/** 模型配置抽屉（从右边缘滑入）：System Prompt / 温度 / Top-P，「应用」后重启服务生效。 */
@Composable
private fun ConfigDrawer(vm: ChatViewModel, restarting: Boolean, onClose: () -> Unit) {
    val tones = LocalWarmTones.current
    ModalDrawerSheet(
        drawerContainerColor = tones.side,
        modifier = Modifier.width(300.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    "模型配置",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, color = tones.faint,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 14.dp)
                )

                Text("系统提示词（System Prompt）", fontSize = 12.5.sp, color = tones.muted)
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(tones.card, RoundedCornerShape(10.dp))
                        .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
                ) {
                    BasicTextField(
                        value = vm.sysPrompt,
                        onValueChange = { vm.sysPrompt = it },
                        textStyle = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, color = tones.ink),
                        cursorBrush = SolidColor(tones.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 180.dp)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        decorationBox = { field ->
                            Box {
                                if (vm.sysPrompt.isEmpty()) {
                                    Text(
                                        "留空 = 内置安全提示词（拒绝危害指导、虚构/医疗自动附声明）；填写后整体覆盖",
                                        fontSize = 13.sp, lineHeight = 19.sp, color = tones.faint
                                    )
                                }
                                field()
                            }
                        }
                    )
                }

                Spacer(Modifier.size(16.dp))
                SliderBlock("温度（temperature）", vm.temperature, 0f..2f) { vm.temperature = it }
                SliderBlock("Top-P", vm.topP, 0f..1f) { vm.topP = it }
                // 参数导演启用时，本机聊天的采样参数被它按任务逐轮接管，滑杆只在其未生效时兜底——UI 明示避免「滑杆失灵」误会
                val paramsDirectorOn = PluginStore.plugins.collectAsStateWithLifecycle().value
                    .any { it.manifest.id == "com.gguf.params" && it.enabled && !it.bypassed }
                if (paramsDirectorOn) {
                    Text(
                        "参数导演插件已启用：本机聊天按任务自动调参，以上滑杆仅作为兜底默认值。",
                        fontSize = 11.5.sp, lineHeight = 16.sp, color = tones.accent
                    )
                }

                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("发送后收起键盘", fontSize = 13.5.sp, color = tones.ink)
                        Text("发送消息后自动失去输入焦点（立即生效）", fontSize = 11.5.sp, lineHeight = 16.sp, color = tones.faint)
                    }
                    Switch(checked = vm.blurOnSend, onCheckedChange = { vm.updateBlurOnSend(it) })
                }

                Spacer(Modifier.weight(1f))
                Text(
                    "「应用」后重启服务生效，重启期间右上角服务器灯闪橙色；" +
                        "温度/Top-P 同时成为对外 API 的默认值。系统提示词仅作用于本机聊天，" +
                        "保持不变时命中前缀缓存（KV 复用，不重复预填充）。",
                    fontSize = 11.5.sp, lineHeight = 17.sp, color = tones.faint
                )
                Spacer(Modifier.size(10.dp))
                Button(
                    onClick = { vm.applyModelConfig(); onClose() },
                    enabled = !restarting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                ) {
                    Text(if (restarting) "重启中…" else "应用", fontSize = 14.5.sp)
                }
            }
        }
    }
}

@Composable
private fun SliderBlock(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val tones = LocalWarmTones.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.5.sp, color = tones.muted, modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.2f", value),
                fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = tones.ink2
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = tones.accent,
                activeTrackColor = tones.accent,
                inactiveTrackColor = tones.line2
            )
        )
    }
}

/**
 * 服务器图标 + 右上角状态灯。
 * 常亮 = 在线待命；呼吸闪烁 = 正在干活（生成回复 / 重启加载中）。
 */
@Composable
private fun ServerIconWithLight(iconTint: Color, lightColor: Color, ringColor: Color, blinking: Boolean) {
    val alpha = if (blinking) {
        val pulse = rememberInfiniteTransition(label = "server-light")
        pulse.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(550, easing = LinearEasing), RepeatMode.Reverse),
            label = "alpha"
        ).value
    } else {
        1f
    }
    Box {
        Icon(Icons.Outlined.Dns, contentDescription = "服务器设置", tint = iconTint)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 3.dp, y = (-3).dp)
                .size(10.dp)
                .background(ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(7.dp).background(lightColor.copy(alpha = alpha), CircleShape))
        }
    }
}

/**
 * 门户选项卡的种类：插件（动态，按 PluginStore 显示顺序；便签也是其中之一，启用 com.gguf.notes
 * 插件后才出现）/ 浏览器（内置，恒最后一个）。[id] 是 rememberSaveable 持久化、手势匹配、
 * closeRequests 比对的稳定 key。
 */
private sealed class PortalTabInfo(val id: String, val label: String) {
    data class Plugin(val rec: PluginRecord) : PortalTabInfo(rec.manifest.id, rec.manifest.name)
    object Browser : PortalTabInfo(BROWSER_ID, "浏览器")

    companion object {
        const val BROWSER_ID = "browser"
    }
}

/**
 * 用触点的 x/屏宽 比例匹配启用插件的手势区间（覆盖优先于 manifest；只认 top 边缘），
 * 命中则门户应直接定位到该插件选项卡；未命中返回 null（调用方保持当前选项卡不变）。
 * 直接读 [PluginStore.plugins] 的最新快照，不依赖 Composable 里派生的列表——手势检测协程
 * 由 pointerInput(Unit) 长期驻留，捕获的局部变量在插件后来才启用/禁用时会过期，这里用
 * StateFlow.value 保证每次真正触发手势时都是当下最新的启用集合。
 */
private fun matchPluginTabByX(xFraction: Float): String? {
    val candidates = PluginStore.plugins.value.filter { it.enabled }.sortedBy { it.order }
    for (rec in candidates) {
        val range = effectivePluginGesture(rec) ?: continue
        if (xFraction >= range.start && xFraction <= range.endInclusive) return rec.manifest.id
    }
    return null
}

/**
 * 交互门户：聊天区顶部的下拉面板（参考小模型轮椅的便签卷帘门，扩展成选项卡门户）。
 * 拉手条常驻：点击或往下拖 = 展开；再点 / 往上拖 = 收起。
 * 选项卡：每个已启用插件一个（ui.html 面板，便签插件启用后也在其中）· 浏览器（系统 WebView，
 * 无自带内核；模型写完网页会自动拉下门户切到这里渲染），恒定顺序为 [插件…, 浏览器]。
 * 选项卡行可能溢出，用 horizontalScroll 承接。
 */
@Composable
private fun ColumnScope.Portal(
    open: Boolean,
    tabs: List<PortalTabInfo>,
    selectedId: String,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    browserHtml: String?,
    browserSeq: Int,
    pluginWebViewCache: MutableMap<String, WebView>,
) {
    val tones = LocalWarmTones.current
    // 卷帘动画：权重乘上动画进度，门户平滑滑出/收回，消息区同步让位（不突兀）
    val frac by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "portal-expand"
    )
    if (frac < 0.01f) return
    // 弹性高度：与消息区按比例分配（键盘弹出时两者一起收缩，输入框永远可见）。面板给得比消息区更高
    // （weight 1.7 : 1）让插件 UI 更舒展、少被挤。收起手势只在面板最底部的「卷帘门拉手」上
    // （见 PortalGrabber）——面板主体不拦截任何竖直手势，插件 WebView / 文本框内部得以自由滚动
    // （此前根级 detectVerticalDragGestures + consume 把面板内的滑动吃去关门户，既滚不动、
    // 内容超出面板处又像被裁切）。
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1.7f * frac)
            .background(tones.side)
    ) {
        // 选项卡行：只放标签 + 收起箭头（不再挂竖直手势，避免拦截面板滚动）；标签横向滚动溢出。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { t ->
                    when (t) {
                        is PortalTabInfo.Browser -> PortalTabChip(t.label, Icons.Outlined.Language, selectedId == t.id) { onSelect(t.id) }
                        is PortalTabInfo.Plugin -> PluginTabChip(t.rec, selectedId == t.id) { onSelect(t.id) }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.ExpandMore, contentDescription = "收起门户",
                modifier = Modifier
                    .size(22.dp)
                    .rotate(180f)
                    .clip(CircleShape)
                    .clickable { onOpenChange(false) },
                tint = tones.faintIcon
            )
        }
        when (val sel = tabs.firstOrNull { it.id == selectedId }) {
            is PortalTabInfo.Plugin -> PluginPanelTab(sel.rec.manifest.id, pluginWebViewCache, Modifier.weight(1f))
            else -> BrowserTab(browserHtml, browserSeq, Modifier.weight(1f))
        }
        // 卷帘门拉手：收起门户的唯一手势入口（上拉或点击）。放在面板最底部，面板主体因此不拦截
        // 竖直手势 → 插件 UI / 文本框内部得以自由滚动。
        PortalGrabber(onClose = { onOpenChange(false) })
    }
}

/**
 * 门户底部的卷帘门拉手：常驻窄横条，中间一个 grabber。往上拖或点一下 = 收起门户。
 * 这是关闭门户的唯一手势入口——面板主体（选项卡行 / WebView）因此不再拦截任何竖直手势，
 * 插件 UI 内部得以自由滚动（此前根级 detectVerticalDragGestures 会把面板内的滑动吃去关门户，
 * 既滚不动、内容超出面板处又像被裁切）。
 */
@Composable
private fun PortalGrabber(onClose: () -> Unit) {
    val tones = LocalWarmTones.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = tones.line, thickness = 1.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(Unit) {
                    var acc = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f; fired = false },
                        onDragEnd = { }
                    ) { change, dy ->
                        acc += dy
                        if (!fired && acc < -40f) { fired = true; onClose() }
                        change.consume()
                    }
                }
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tones.faintIcon)
            )
        }
    }
}

@Composable
private fun PortalTabChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) tones.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (selected) tones.accent else tones.faintIcon)
        Text(label, fontSize = 12.5.sp, color = if (selected) tones.accent else tones.muted, maxLines = 1)
    }
}

/** 插件选项卡的标签胶囊：图标用插件自己的 icon.png（主题色 tint），右上角badge>0 时加个小红点。 */
@Composable
private fun PluginTabChip(rec: PluginRecord, selected: Boolean, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) tones.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        PluginIcon(rec.dir, size = 14.dp, tint = if (selected) tones.accent else tones.faintIcon)
        Text(
            rec.manifest.name, fontSize = 12.5.sp, color = if (selected) tones.accent else tones.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (rec.badge > 0) Box(Modifier.size(6.dp).background(tones.bad, CircleShape))
    }
}

/**
 * 插件面板选项卡：AndroidView 承载插件的 ui.html WebView。工厂函数优先复用
 * [pluginWebViewCache] 里的既有实例（先从旧父容器摘下再返回，避免 Android
 * “child already has a parent” 崩溃）——门户收起 / 切走再切回都不会丢状态，
 * 只有插件被禁用时上层才会清缓存 + 调 detachPanelWebView 真正销毁。
 * 进入/离开这个选项卡时对应通知 gguf.events 'show'/'hide'。
 */
@Composable
private fun PluginPanelTab(pluginId: String, cache: MutableMap<String, WebView>, modifier: Modifier = Modifier) {
    val tones = LocalWarmTones.current
    // key(pluginId)：AndroidView 的 factory 每个组合节点只执行一次，切换选项卡时若不换节点，
    // WebView 会停留在上一个插件的实例上（换 pluginId 参数不会触发 factory 重跑）。
    androidx.compose.runtime.key(pluginId) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    val cached = cache[pluginId]
                    if (cached != null) {
                        (cached.parent as? ViewGroup)?.removeView(cached)
                        cached
                    } else {
                        WebView(ctx).also { wv ->
                            if (PluginRuntime.preparePanelWebView(wv, pluginId)) cache[pluginId] = wv
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        LaunchedEffect(pluginId) { PluginRuntime.notifyPanelShow(pluginId) }
        DisposableEffect(pluginId) { onDispose { PluginRuntime.notifyPanelHide(pluginId) } }
    }
}

/**
 * 顶栏图标槽：44dp 紧凑点击域（IconButton 强制 48dp 最小尺寸，在「算好槽位、两端对齐」
 * 的顶栏网格里会挤爆一行——44dp 触控仍达标，图形照旧居中）。
 */
@Composable
private fun TopBarSlot(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 顶栏的插件图标按钮：主题色 tint 的 icon.png，badge>0 时右上角一个小红点。 */
@Composable
private fun PluginTopBarButton(rec: PluginRecord, selected: Boolean, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    TopBarSlot(onClick = onClick) {
        Box {
            PluginIcon(rec.dir, size = 22.dp, tint = if (selected) tones.accent else tones.muted)
            if (rec.badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(8.dp)
                        .background(tones.accent, CircleShape)
                )
            }
        }
    }
}

/** 顶栏「插件快捷区」与「固定功能区」之间的竖线分隔符。 */
@Composable
private fun TopBarDivider() {
    val tones = LocalWarmTones.current
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .height(20.dp)
            .width(1.dp)
            .background(tones.line2)
    )
}

/**
 * 启动台：点顶栏「启动台」图标从顶栏下方拉出的半屏毛玻璃面板，方格列出全部已启用插件。
 * 点某个格子 = 收起启动台 + 打开门户到该插件选项卡；点面板外遮罩 / 返回键收起。
 * 背后聊天内容在开启时被 blur（API 31+ 生效；低版本降级为半透明遮罩，仍有层次）。
 */
@Composable
private fun LaunchpadOverlay(
    open: Boolean,
    topPadding: Dp,
    plugins: List<PluginRecord>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val tones = LocalWarmTones.current
    val frac by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "launchpad-open"
    )
    if (frac < 0.01f) return
    Box(Modifier.fillMaxSize().padding(top = topPadding)) {
        // 遮罩：随进度淡入，点击收起
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = frac }
                .background(Color.Black.copy(alpha = 0.18f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        // 半屏下拉面板：毛玻璃质感（半透明底色，透出背后模糊内容），从顶栏下沿滑出
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.52f)
                .graphicsLayer {
                    translationY = (frac - 1f) * size.height
                    alpha = frac
                }
                .background(tones.side.copy(alpha = 0.88f))
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Text(
                "启动台",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = tones.faint,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp)
            )
            if (plugins.isEmpty()) {
                Text(
                    "还没有启用任何插件。到「设置 → 插件管理」里开启后会出现在这里。",
                    fontSize = 13.sp, color = tones.muted, lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(84.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    gridItems(plugins, key = { it.manifest.id }) { rec ->
                        LaunchpadCell(rec) { onSelect(rec.manifest.id) }
                    }
                }
            }
            HorizontalDivider(color = tones.line, thickness = 1.dp)
        }
    }
}

/** 启动台里的一个插件格子：图标卡 + 名字，点一下开门户到它。 */
@Composable
private fun LaunchpadCell(rec: PluginRecord, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(tones.card)
                .border(1.dp, tones.line2, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            PluginIcon(rec.dir, size = 28.dp, tint = tones.accent)
            if (rec.badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(7.dp)
                        .background(tones.bad, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            rec.manifest.name,
            fontSize = 11.5.sp,
            color = tones.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp)
        )
    }
}

/** 浏览器选项卡：系统 WebView（不带自研内核）。地址栏 + 刷新；模型产出的 HTML 自动载入。 */
@Composable
private fun BrowserTab(html: String?, seq: Int, modifier: Modifier = Modifier) {
    val tones = LocalWarmTones.current
    var url by rememberSaveable { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun go() {
        val u = url.trim()
        if (u.isNotEmpty()) {
            val full = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
            webView?.loadUrl(full)
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(tones.card, RoundedCornerShape(10.dp))
                .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                textStyle = TextStyle(fontSize = 12.5.sp, color = tones.ink),
                cursorBrush = SolidColor(tones.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box {
                        if (url.isEmpty()) Text("输入网址，或等模型写好网页自动展示", fontSize = 12.5.sp, color = tones.faint)
                        field()
                    }
                }
            )
            Icon(
                Icons.Filled.Refresh, contentDescription = "刷新/前往",
                Modifier.size(17.dp).clickable { if (url.isNotBlank()) go() else webView?.reload() },
                tint = tones.muted
            )
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        setBackgroundColor(android.graphics.Color.WHITE)
                    }.also { webView = it }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    // 新的 HTML 到来（或 WebView 重建）时载入模型产出的页面
    LaunchedEffect(webView, seq) {
        val wv = webView
        if (wv != null && html != null && seq > 0) {
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }
}

/** 空态：本机芯片图标 + 模型状态说明。 */
@Composable
private fun EmptyState(model: String, modifier: Modifier = Modifier) {
    val tones = LocalWarmTones.current
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(56.dp).background(tones.side, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Memory, null, Modifier.size(28.dp), tint = tones.accent)
        }
        Spacer(Modifier.size(16.dp))
        Text(model, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tones.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            "已在本机就绪，对话不出这台手机。",
            fontSize = 13.sp, lineHeight = 21.sp, color = tones.muted, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MessageRow(
    msg: ChatMessage,
    isLastAssistant: Boolean = false,
    busy: Boolean = false,
    onRetry: () -> Unit = {},
    onSwitchAlt: (Int) -> Unit = {},
    actions: List<Pair<PluginRecord, PluginMessageAction>> = emptyList(),
    onAction: (PluginRecord, PluginMessageAction, ChatMessage) -> Unit = { _, _, _ -> },
    cardHost: (String) -> PluginRecord? = { null },
) {
    val tones = LocalWarmTones.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 方圆头像：用户暖灰底，助手赤陶底芯片。卡片消息也不例外——插件只接管正文区域的
        // 渲染容器（§6.12），头像/动作行/布局框架全是宿主的，与普通消息一致、可自然混排。
        val isUser = msg.role == "user"
        val cardRec = if (!isUser && msg.cardPlugin != null) cardHost(msg.cardPlugin) else null
        Box(
            Modifier
                .size(28.dp)
                .background(if (isUser) tones.userAvatar else tones.accent, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isUser) Icon(Icons.Outlined.Person, null, Modifier.size(17.dp), tint = tones.muted)
            else Icon(Icons.Filled.Memory, null, Modifier.size(16.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            if (msg.reasoning.isNotBlank()) ReasoningRow(msg)
            if (isUser) {
                // 用户输入按原文显示，不做 MD 解析（星号缩进等按字面意思保留）
                if (msg.content.isNotEmpty()) {
                    Text(msg.content, fontSize = 15.sp, lineHeight = 25.sp, color = tones.ink)
                }
            } else if (msg.cardPlugin != null) {
                // 插件消息卡片（§6.12）：卡片属于这条消息，正文（若有）照常渲染、卡片跟在其后
                // 同条混排；content 与卡片内部文本相同（纯卡片回合）时只渲染卡片，不重复显示。
                val cardText = remember(msg.cardData) {
                    runCatching { JSONObject(msg.cardData ?: "{}").optString("text") }.getOrDefault("")
                }
                val hasBody = msg.content.isNotBlank() && msg.content != cardText
                if (hasBody) MarkdownMessage(msg.content, false)
                if (cardRec != null) {
                    PluginCardView(cardRec, msg)
                } else {
                    // 插件不在/未启用 → 内部文本降级为普通文字（正文已渲染过就不重复）
                    if (!hasBody && msg.content.isNotEmpty()) MarkdownMessage(msg.content, false)
                    Text(
                        "插件卡片（${msg.cardPlugin} 未启用，已按文字显示）",
                        fontSize = 10.5.sp, color = tones.faint, modifier = Modifier.padding(top = 3.dp)
                    )
                }
            } else if (msg.content.isNotEmpty() || msg.streaming) {
                // 模型回复走 Markdown 渲染（流式时光标一起渲染进最后一个块）
                MarkdownMessage(msg.content, msg.streaming)
            }
            msg.error?.let {
                Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = tones.bad, modifier = Modifier.padding(top = 4.dp))
            }
            // 便签写入确认条（图标 + 淡赤陶底，不混入正文）
            msg.noted?.takeIf { it.isNotBlank() }?.let { noted ->
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tones.accent.copy(alpha = 0.08f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.PushPin, null, Modifier.size(13.dp), tint = tones.accent)
                    // **不要硬套便签的措辞**：notice 是所有插件共用的通道（§6.6），乐句会写
                    // 「这段乐句没认出可用的音符」、朗读会写别的——旧代码给不以「已/便签」开头的
                    // 一律加「已记入便签：」前缀，于是真机上出现了「已记入便签：这段乐句没认出可用的音符」。
                    // 插件自己写的确认句就是完整句子，原样显示即可（很老的便签数据会少个前缀，可接受）。
                    val label = noted
                    Text(
                        label,
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = tones.muted, maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 平均生成速度（token/s）：流式时实时刷新，收尾后被服务端 timings 校准
            val speed = if (msg.genTokens > 1 && msg.genMs > 500) msg.genTokens * 1000f / msg.genMs else 0f
            if (msg.streaming) {
                if (speed > 0f) SpeedLabel(speed)
            } else if (msg.content.isNotBlank() || (isLastAssistant && !msg.noted.isNullOrBlank())) {
                // 提问和回复都可以单独复制；助手回复右侧淡淡标注平均速度；
                // 最后一条助手回复额外提供 重试 与 ‹ K/N › 候选切换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.content.isNotBlank()) CopyAction(msg.content)
                    // 插件消息角标（按 roles 过滤）：与复制同一视觉语言，点击派发给插件
                    if (msg.content.isNotBlank()) {
                        for ((rec, action) in actions) {
                            if (msg.role !in action.roles) continue
                            Spacer(Modifier.width(10.dp))
                            MessageActionBadge(rec, action) { onAction(rec, action, msg) }
                        }
                    }
                    if (speed > 0f) {
                        Spacer(Modifier.width(10.dp))
                        SpeedLabel(speed)
                    }
                    // 卡片消息不提供重试：它由插件产生，不是模型流式回复，重试语义不成立
                    if (isLastAssistant && msg.cardPlugin == null) {
                        Spacer(Modifier.width(10.dp))
                        RetryControls(msg, busy, onRetry, onSwitchAlt)
                    }
                }
            }
        }
    }
}

/** 不起眼的小字：这条回复的平均生成速度。 */
@Composable
private fun SpeedLabel(tokPerSec: Float) {
    Text(
        String.format(Locale.US, "%.1f token/s", tokPerSec),
        fontSize = 10.5.sp,
        color = LocalWarmTones.current.faint,
        modifier = Modifier.padding(top = 3.dp)
    )
}

/**
 * 最后一条助手回复的重试控件：重试（重新生成一个候选）+ ‹ K/N › 候选切换。
 * 与 CopyAction 同一视觉语言（淡图标 + 小字），生成中禁用。
 */
@Composable
private fun RetryControls(msg: ChatMessage, busy: Boolean, onRetry: () -> Unit, onSwitchAlt: (Int) -> Unit) {
    val tones = LocalWarmTones.current
    Row(
        Modifier.padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (msg.alts.size > 1) {
            val canPrev = !busy && msg.altIdx > 0
            val canNext = !busy && msg.altIdx < msg.alts.lastIndex
            Icon(
                Icons.Filled.ChevronLeft, contentDescription = "上一个候选",
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canPrev) { onSwitchAlt(-1) },
                tint = if (canPrev) tones.muted else tones.faint.copy(alpha = 0.45f)
            )
            Text("${msg.altIdx + 1}/${msg.alts.size}", fontSize = 11.sp, color = tones.faint)
            Icon(
                Icons.Filled.ChevronRight, contentDescription = "下一个候选",
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canNext) { onSwitchAlt(1) },
                tint = if (canNext) tones.muted else tones.faint.copy(alpha = 0.45f)
            )
            Spacer(Modifier.width(4.dp))
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = !busy, onClick = onRetry)
                .padding(horizontal = 2.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "重试", Modifier.size(13.dp), tint = tones.faintIcon)
            Text("重试", fontSize = 11.5.sp, color = tones.faint)
        }
    }
}

/** 消息下方淡淡的「复制」动作，点击后短暂变成「已复制」。 */
@Composable
private fun CopyAction(text: String) {
    val tones = LocalWarmTones.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    Row(
        Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = "复制",
            Modifier.size(13.dp),
            tint = if (copied) tones.ok else tones.faintIcon
        )
        Text(if (copied) "已复制" else "复制", fontSize = 11.5.sp, color = if (copied) tones.ok else tones.faint)
    }
}

/**
 * 插件消息卡片（《工具插件规范》§6.12）：聊天列表里由插件 WebView 自绘的一条消息。
 * 每条卡片一个 WebView（透明背景，气泡形状由插件页面自己画），随 LazyColumn 滚动
 * 懒创建/回收；高度由卡片页 scrollHeight 经桥回报（与设置卡片同机制），回收后保留
 * 高度值，重建前占位不跳动。点击/长按/滑动等交互都是卡片页内的标准 DOM 事件。
 */
@Composable
private fun PluginCardView(rec: PluginRecord, msg: ChatMessage) {
    val heights by PluginRuntime.cardHeights.collectAsStateWithLifecycle()
    val cardKey = "${rec.manifest.id}#${msg.id}"
    val h = (heights[cardKey] ?: 64).coerceIn(32, 4000)
    androidx.compose.runtime.key(cardKey) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    PluginRuntime.prepareCardWebView(
                        wv, rec.manifest.id, msg.id.toString(),
                        msg.cardFile ?: "card.html", msg.cardData ?: "{}",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(h.dp)
        )
        DisposableEffect(cardKey) {
            onDispose { PluginRuntime.detachCardWebView(rec.manifest.id, msg.id.toString()) }
        }
    }
}

/**
 * 插件的消息角标（《工具插件规范》§6.11）：消息下方动作行里属于某插件的一个动作按钮，
 * 图标用插件自己的 icon（主题色 tint 的 alpha 蒙版）+ 短文字，与「复制」「重试」同一视觉语言。
 * 点击后短暂亮成主题色作为已响应反馈（真正的动作反馈由插件自己给：发声 / toast / 角标数字）。
 */
@Composable
private fun MessageActionBadge(rec: PluginRecord, action: PluginMessageAction, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    var flashed by remember { mutableStateOf(false) }
    LaunchedEffect(flashed) {
        if (flashed) { delay(700); flashed = false }
    }
    Row(
        Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                flashed = true
                onClick()
            }
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        PluginIcon(
            rec.dir, size = 13.dp,
            tint = if (flashed) tones.accent else tones.faintIcon,
            file = action.icon ?: "icon.png",
        )
        Text(action.label, fontSize = 11.5.sp, color = if (flashed) tones.accent else tones.faint)
    }
}

/** 思考过程：淡淡的一行，点开看内容。 */
@Composable
private fun ReasoningRow(msg: ChatMessage) {
    val tones = LocalWarmTones.current
    var open by rememberSaveable(msg.id) { mutableStateOf(false) }
    Row(
        Modifier.clickable { open = !open }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp), tint = tones.faintIcon)
        Text(
            if (msg.streaming && msg.content.isEmpty()) "思考中…" else "思考过程",
            fontSize = 12.5.sp, color = tones.faint
        )
    }
    if (open) {
        Surface(
            color = tones.side,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, tones.line),
            modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth()
        ) {
            Box(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    msg.reasoning,
                    fontSize = 12.sp, lineHeight = 18.sp, color = tones.muted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 输入卡（Claude 官网式两段布局）：上面多行文本区，下面左侧淡淡的模型名、右侧发送键。 */
@Composable
private fun Composer(vm: ChatViewModel, model: String, ready: Boolean) {
    val tones = LocalWarmTones.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        color = tones.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, tones.line2),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicTextField(
                value = vm.input,
                onValueChange = { vm.input = it },
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = tones.ink),
                cursorBrush = SolidColor(tones.accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { field ->
                    Box {
                        if (vm.input.isEmpty()) {
                            Text("问点什么…", fontSize = 15.sp, color = tones.faint)
                        }
                        field()
                    }
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    model,
                    fontSize = 12.sp, color = tones.faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, end = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                val canSend = vm.input.isNotBlank() && !vm.busy && ready
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            when {
                                vm.busy -> tones.muted
                                canSend -> tones.accent
                                else -> tones.line2
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = canSend || vm.busy) {
                            if (vm.busy) {
                                vm.stopStreaming()
                            } else {
                                vm.send()
                                if (vm.blurOnSend) {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (vm.busy) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", Modifier.size(16.dp), tint = Color.White)
                    } else {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "发送", Modifier.size(17.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}
