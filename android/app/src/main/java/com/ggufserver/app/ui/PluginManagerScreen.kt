package com.ggufserver.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import com.ggufserver.app.plugin.PluginRuntime
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ggufserver.app.plugin.InstallResult
import com.ggufserver.app.plugin.PluginRecord
import com.ggufserver.app.plugin.PluginStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理页（《工具插件规范》§9）：设置页的一个新入口。展示全部已装插件（内置 + 导入），
 * 可启用/禁用（启用前弹权限确认）、调整显示顺序、编辑手势区间、查看权限与最近错误、卸载；
 * 「导入插件」走 SAF 选 zip，同 id 同版本或更低时二次确认强制覆盖。
 * 视觉对齐 SettingsScreen 的 iOS 分组列表风格：组标题小图标在卡片外，卡片内是列表内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerScreen(onBack: () -> Unit) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugins by PluginStore.plugins.collectAsStateWithLifecycle()
    val sorted = remember(plugins) { plugins.sortedBy { it.order } }

    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    // 展开某插件设置时把该行滚到顶端（否则展开的长卡片落在页面中部/下方，界面别扭）
    val listScroll = rememberScrollState()
    var pluginsRoot by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val rowCoords = remember { mutableMapOf<String, LayoutCoordinates>() }
    // 展开某插件设置时把该行滚到顶端。要点：(1) 设置卡片异步撑高，把展开插件的卡片高度也作为触发条件，
    // 撑高后重滚，确保 content 够高时能滚到行顶；(2) 只在这里即时用 localPositionOf 算一次行的绝对 Y
    // ——不在 onGloballyPositioned 里累加 scrollState.value，否则滚动动画中回调频繁、读值不同步会漂移、滚过头。
    val settingsHeights by PluginRuntime.settingsHeights.collectAsStateWithLifecycle()
    LaunchedEffect(expandedId, settingsHeights[expandedId]) {
        val id = expandedId ?: return@LaunchedEffect
        withFrameNanos { }  // 等一帧让展开内容进入布局、content 变高
        val root = pluginsRoot ?: return@LaunchedEffect
        val rc = rowCoords[id] ?: return@LaunchedEffect
        if (!root.isAttached || !rc.isAttached) return@LaunchedEffect
        // pluginsRoot 是 verticalScroll 之后的滚动内容层，localPositionOf 给的已是行相对内容顶部的
        // 绝对 Y（不随滚动变），直接作为 animateScrollTo 目标即可——不能再加 scrollState.value，否则
        // 已经滚到下面时会多滚当前滚动量、正好过头（图标被顶出屏幕上方）。
        val y = root.localPositionOf(rc, Offset.Zero).y
        listScroll.animateScrollTo(y.toInt().coerceAtLeast(0))
    }
    var pendingEnable by remember { mutableStateOf<PluginRecord?>(null) }
    var pendingUninstall by remember { mutableStateOf<PluginRecord?>(null) }
    var pendingForce by remember { mutableStateOf<Pair<Uri, InstallResult.NeedsForce>?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { PluginStore.installFromZip(it) }
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    when (result) {
                        is InstallResult.Installed -> resultMessage =
                            "已安装「${result.manifest.name}」（${if (result.upgraded) "已升级至 " else ""}v${result.manifest.version}），" +
                                "默认禁用，请在下面的列表中手动启用。"
                        is InstallResult.NeedsForce -> pendingForce = uri to result
                        is InstallResult.Rejected -> resultMessage = "导入失败：${result.reason}"
                        null -> resultMessage = "导入失败：无法读取所选文件。"
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = tones.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tones.bg),
                title = { Text("插件管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tones.ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置", tint = tones.muted)
                    }
                },
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "导入插件", tint = tones.accent)
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(listScroll)
                .onGloballyPositioned { pluginsRoot = it }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            if (sorted.isEmpty()) {
                EmptyPluginHint()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Extension, null, Modifier.size(15.dp), tint = tones.accent)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "已安装插件", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    WarmCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            sorted.forEachIndexed { idx, rec ->
                                val id = rec.manifest.id
                                Box(Modifier.onGloballyPositioned { c -> rowCoords[id] = c }) {
                                PluginRow(
                                    rec = rec,
                                    expanded = expandedId == id,
                                    canMoveUp = idx > 0,
                                    canMoveDown = idx < sorted.lastIndex,
                                    onToggleExpand = { expandedId = if (expandedId == id) null else id },
                                    onRequestEnable = { pendingEnable = rec },
                                    onDisable = {
                                        val reason = PluginStore.setEnabled(id, false)
                                        if (reason != null) resultMessage = reason
                                    },
                                    onClearBypass = { PluginStore.clearBypass(id) },
                                    onMoveUp = { PluginStore.setOrder(reorder(sorted, idx, idx - 1)) },
                                    onMoveDown = { PluginStore.setOrder(reorder(sorted, idx, idx + 1)) },
                                    onGestureRangeChange = { range -> PluginStore.setGestureOverride(id, range) },
                                    onGestureReset = { PluginStore.setGestureOverride(id, null) },
                                    onUninstall = { pendingUninstall = rec },
                                    otherEnabled = sorted.filter { it.manifest.id != id && it.enabled && !it.bypassed },
                                )
                                }
                            }
                        }
                    }
                }
                Text(
                    "插件默认关闭，启用前会展示权限清单；已启用的插件会出现在交互门户与顶栏图标区。" +
                        "内置的「便签」「记忆」也是普通插件——出厂状态下同样未启用，此时对话是纯净流式聊天，" +
                        "在这里手动开启后才具备记便签、跨对话回忆等能力。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = tones.faint
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingEnable?.let { rec ->
        PermissionConfirmDialog(
            rec = rec,
            onConfirm = {
                val reason = PluginStore.setEnabled(rec.manifest.id, true)
                if (reason != null) resultMessage = reason
                pendingEnable = null
            },
            onDismiss = { pendingEnable = null }
        )
    }
    pendingUninstall?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载「${rec.manifest.name}」吗？插件目录与其私有存储的数据都会被一并清除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    PluginStore.uninstall(rec.manifest.id)
                    if (expandedId == rec.manifest.id) expandedId = null
                    pendingUninstall = null
                }) { Text("卸载", color = tones.bad) }
            },
            dismissButton = { TextButton(onClick = { pendingUninstall = null }) { Text("取消") } }
        )
    }
    pendingForce?.let { (uri, needs) ->
        AlertDialog(
            onDismissRequest = { pendingForce = null },
            title = { Text("版本冲突") },
            text = {
                Text(
                    "已安装版本 v${needs.installedVersion}，导入包的版本 v${needs.newVersion} 并不更高。" +
                        "是否强制覆盖安装？（插件私有存储数据保留）"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { PluginStore.installFromZip(it, force = true) }
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            resultMessage = when (result) {
                                is InstallResult.Installed -> "已强制覆盖安装「${result.manifest.name}」（v${result.manifest.version}）。"
                                is InstallResult.Rejected -> "安装失败：${result.reason}"
                                else -> "安装失败：无法读取所选文件。"
                            }
                        }
                    }
                    pendingForce = null
                }) { Text("强制覆盖") }
            },
            dismissButton = { TextButton(onClick = { pendingForce = null }) { Text("取消") } }
        )
    }
    resultMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = { Text("插件") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { resultMessage = null }) { Text("好") } }
        )
    }
}

/** 把 [from] 位置的插件挪到 [to] 位置后的 id 顺序；越界则原样返回。 */
private fun reorder(list: List<PluginRecord>, from: Int, to: Int): List<String> {
    if (to < 0 || to >= list.size) return list.map { it.manifest.id }
    val ids = list.map { it.manifest.id }.toMutableList()
    val item = ids.removeAt(from)
    ids.add(to, item)
    return ids
}

@Composable
private fun EmptyPluginHint() {
    val tones = LocalWarmTones.current
    WarmCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Extension, null, Modifier.size(30.dp), tint = tones.faintIcon)
            Spacer(Modifier.height(10.dp))
            Text("还没有安装任何插件", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "插件默认关闭。点右上角「导入插件」选择 .zip / .gtool 包即可安装，安装后请到这里手动启用。",
                fontSize = 12.5.sp, lineHeight = 18.sp, color = tones.faint,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 单个插件行：图标 + 名称/版本 + 排序 + 启用开关；点击行展开详情。 */
@Composable
private fun PluginRow(
    rec: PluginRecord,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleExpand: () -> Unit,
    onRequestEnable: () -> Unit,
    onDisable: () -> Unit,
    onClearBypass: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onGestureRangeChange: (Pair<Float, Float>?) -> Unit,
    onGestureReset: () -> Unit,
    onUninstall: () -> Unit,
    otherEnabled: List<PluginRecord>,
) {
    val tones = LocalWarmTones.current
    val m = rec.manifest
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PluginIcon(rec.dir, size = 30.dp, tint = if (rec.enabled && !rec.bypassed) tones.accent else tones.faintIcon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        m.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (rec.bypassed) Box(Modifier.size(7.dp).background(tones.bad, CircleShape))
                }
                Text(
                    "v${m.version}" + (m.author.trim().takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    fontSize = 11.5.sp, color = tones.faint
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, contentDescription = "上移",
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (canMoveUp) 1f else 0.3f)
                        .clip(CircleShape)
                        .clickable(enabled = canMoveUp, onClick = onMoveUp),
                    tint = tones.faintIcon
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown, contentDescription = "下移",
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (canMoveDown) 1f else 0.3f)
                        .clip(CircleShape)
                        .clickable(enabled = canMoveDown, onClick = onMoveDown),
                    tint = tones.faintIcon
                )
            }
            Spacer(Modifier.width(4.dp))
            if (rec.bypassed) {
                Text(
                    "恢复", fontSize = 12.5.sp, color = tones.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearBypass)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Switch(checked = rec.enabled, onCheckedChange = { checked -> if (checked) onRequestEnable() else onDisable() })
        }
        if (expanded) {
            PluginDetail(rec, onGestureRangeChange, onGestureReset, onUninstall, otherEnabled)
        }
    }
}

/** 展开的插件详情：简介/作者、权限清单、手势编辑、最近错误、卸载。 */
@Composable
private fun PluginDetail(
    rec: PluginRecord,
    onGestureRangeChange: (Pair<Float, Float>?) -> Unit,
    onGestureReset: () -> Unit,
    onUninstall: () -> Unit,
    otherEnabled: List<PluginRecord>,
) {
    val tones = LocalWarmTones.current
    val m = rec.manifest
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (m.description.isNotBlank()) {
            Text(m.description, fontSize = 12.5.sp, lineHeight = 18.sp, color = tones.muted)
        }

        if (m.settingsCard != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("设置", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.faint)
                PluginSettingsCard(m.id)
            }
        }

        if (m.permissions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("权限", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.faint)
                m.permissions.sortedByDescending { permRisk(it).ordinal }.forEach { p -> PermissionLine(p) }
            }
        }

        // 生效范围三选一（§6.8）：插件用 middleware.configurable 主动暴露，用户选择覆盖
        // manifest 默认——同一个插件既可只服务本机，也可只当对外中间件，或内外通用。
        if (m.scopeConfigurable) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("生效范围", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.faint)
                ScopeSelector(rec)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("手势唤出", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.faint)
            GestureEditor(rec, onGestureRangeChange, onGestureReset, otherEnabled)
        }

        if (rec.errors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最近错误", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.faint)
                val fmt = remember { SimpleDateFormat("M月d日 HH:mm:ss", Locale.CHINA) }
                rec.errors.asReversed().take(5).forEach { e ->
                    Text(
                        "${fmt.format(Date(e.ts))}  ${e.message}",
                        fontSize = 11.sp, lineHeight = 15.sp, color = tones.bad,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (rec.builtin) {
            // 内置插件不可卸载：卸载会清私有数据（便签内容等），而下次启动 assets 又会重新释放
            // 一个空插件回来——等于变相清空用户数据。需要停用时用开关即可，数据全部保留。
            Text(
                "内置插件，不可卸载；禁用即可停用，数据（如便签内容）全部保留。",
                fontSize = 11.5.sp, lineHeight = 15.sp, color = tones.faint,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "卸载插件", fontSize = 12.5.sp, color = tones.bad,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onUninstall).padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 插件设置卡片：展开插件行时渲染 settingsCard 声明的 html，跑在与面板同源、同桥、同权限的 WebView 里。
 * 高度自适应内容：settings.html 经 gguf-sdk 把内容高度回报给宿主（见 PluginBridge.reportHeight），卡片撑到
 * 完整内容高度——避免固定高度把滑块等控件裁掉。不设高度上限：外层插件管理页是 verticalScroll 的 Column，
 * 卡片撑多高都能整页滚到底（曾误加 560dp 上限，又把重型记忆的长设置裁了）。
 * key(pluginId) 保证换插件时重建（AndroidView.factory 每节点只跑一次）；离开/折叠时销毁 WebView + 清高度。
 */
@Composable
private fun PluginSettingsCard(pluginId: String) {
    key(pluginId) {
        val heights by PluginRuntime.settingsHeights.collectAsStateWithLifecycle()
        val reported = heights[pluginId] ?: 0
        val cardH = if (reported > 0) reported.dp.coerceAtLeast(96.dp) else 300.dp
        AndroidView(
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    wv.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    PluginRuntime.prepareSettingsWebView(wv, pluginId)
                }
            },
            modifier = Modifier.fillMaxWidth().height(cardH).clip(RoundedCornerShape(10.dp))
        )
        DisposableEffect(pluginId) {
            onDispose {
                PluginRuntime.detachSettingsWebView(pluginId)
                PluginRuntime.clearSettingsHeight(pluginId)
            }
        }
    }
}

// ============================== 生效范围三选一（规范 §6.8 内外通用管线） ==============================

/**
 * 仅对内 / 内外通用 / 仅对外 的胶囊选择器。选择即持久化（覆盖 manifest.scope）；
 * 当前值 = 用户覆盖 ?: manifest 默认。仅 middleware.configurable 的插件展示（调用方保证）。
 */
@Composable
private fun ScopeSelector(rec: PluginRecord) {
    val tones = LocalWarmTones.current
    val current = rec.scopeOverride ?: rec.manifest.scope
    val options = listOf(
        "仅对内" to setOf("local"),
        "内外通用" to setOf("local", "external"),
        "仅对外" to setOf("external"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, scopes) in options) {
            val selected = current == scopes
            Text(
                label,
                fontSize = 12.sp,
                color = if (selected) Color.White else tones.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) tones.accent else tones.side)
                    .clickable { PluginStore.setScopeOverride(rec.manifest.id, scopes) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
    Text(
        "对内 = 本机聊天界面；对外 = 局域网 API 客户端。中间件管线内外同一条，这里只是选它对谁生效。",
        fontSize = 11.sp, lineHeight = 15.sp, color = tones.faint,
        modifier = Modifier.padding(top = 4.dp)
    )
}

// ============================== 权限展示（规范 §7 风险分级） ==============================

private enum class PermRisk { LOW, MEDIUM, HIGH }
private data class PermInfo(val label: String, val risk: PermRisk, val note: String)

private val PERMISSION_INFO = mapOf(
    "llm" to PermInfo("调用本机模型", PermRisk.LOW, "仅能调用本机已加载的模型进行推理。"),
    "chat:read" to PermInfo("读取当前会话", PermRisk.MEDIUM, "可读取当前会话的消息内容，涉及隐私。"),
    "sessions:read" to PermInfo("读取全部历史会话", PermRisk.HIGH, "可读取你的全部历史对话记录，隐私风险较高。"),
    "chat:context" to PermInfo("注入对话上下文", PermRisk.MEDIUM, "可在发给模型的内容里插入背景信息，可能影响模型行为。"),
    "chat:route" to PermInfo("接管对话回合", PermRisk.HIGH, "可拦截并接管你的对话回合，请确认插件来源可信。"),
    "chat:annotate" to PermInfo("回复后处理", PermRisk.MEDIUM, "可改写模型回复正文、附加确认条。"),
    "chat:action" to PermInfo("消息角标动作", PermRisk.MEDIUM, "可在消息下方添加动作角标；点击时该条消息的内容会交给插件处理。"),
    "chat:card" to PermInfo("插入消息卡片", PermRisk.MEDIUM, "可在对话里插入由插件自行渲染的消息卡片（如语音消息）。"),
    "cap:tts" to PermInfo("接管发声", PermRisk.MEDIUM, "本机所有语音朗读（含其它插件发起的）将改由该插件处理，朗读文本会流经它；禁用即恢复系统发声。"),
    "chat:params" to PermInfo("调整采样参数", PermRisk.MEDIUM, "可按任务类型调整本轮回复的温度等采样参数（外部客户端显式指定的参数不受影响）。"),
    "chat:external" to PermInfo("影响对外 API", PermRisk.HIGH, "会影响局域网内其它设备调用本机 API 时的请求与响应。"),
    "net:lan" to PermInfo("访问局域网", PermRisk.MEDIUM, "可发起局域网 / 本机地址的网络请求。"),
    "net:internet" to PermInfo("访问公网", PermRisk.HIGH, "本应用默认离线，此插件会访问公共互联网。"),
)

private fun permRisk(p: String): PermRisk = PERMISSION_INFO[p]?.risk ?: PermRisk.LOW

@Composable
private fun PermissionLine(perm: String) {
    val tones = LocalWarmTones.current
    val info = PERMISSION_INFO[perm]
    val risk = info?.risk ?: PermRisk.LOW
    val color = when (risk) {
        PermRisk.HIGH -> tones.bad
        PermRisk.MEDIUM -> tones.warn
        PermRisk.LOW -> tones.muted
    }
    Column(Modifier.padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(info?.label ?: perm, fontSize = 12.5.sp, color = tones.ink2)
        }
        if (risk == PermRisk.HIGH && info != null) {
            Text(
                info.note, fontSize = 11.sp, lineHeight = 15.sp, color = color,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/** 启用前的权限确认弹窗：列出全部权限并按风险着色，确认后才真正调用 setEnabled(true)。 */
@Composable
private fun PermissionConfirmDialog(rec: PluginRecord, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tones = LocalWarmTones.current
    val m = rec.manifest
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用「${m.name}」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("启用后该插件将获得以下权限：", fontSize = 13.sp, color = tones.muted)
                if (m.permissions.isEmpty()) {
                    Text("（不需要任何特殊权限）", fontSize = 12.5.sp, color = tones.faint)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.permissions.sortedByDescending { permRisk(it).ordinal }.forEach { p -> PermissionLine(p) }
                    }
                }
                if (m.hooks.any { it != "messageAction" } || m.tools.isNotEmpty()) {
                    Text(
                        "该插件会介入对话流程（钩子/工具）；连续异常会自动熔断，可在管理页一键恢复。",
                        fontSize = 11.5.sp, lineHeight = 16.sp, color = tones.faint
                    )
                } else if (m.messageActions.isNotEmpty()) {
                    Text(
                        "该插件会在消息下方添加动作角标（不介入对话流程）；连续异常会自动熔断，可在管理页一键恢复。",
                        fontSize = 11.5.sp, lineHeight = 16.sp, color = tones.faint
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认启用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================== 手势区间编辑（规范 §4） ==============================

/**
 * 手势区间编辑器：两个 0~1 滑杆改 range（宿主预留区 top[0,0.25] 之外才允许申领，
 * 区间长度不得小于 0.2）。关闭手势 = 回退为仅图标唤出（用 (0,0) 这种起止相等的
 * 退化区间表示「已显式关闭」，与「跟随 manifest 默认」的 null 覆盖区分开）。
 * 与其它已启用插件的区间重叠时只做提示，不阻止保存——命中顺序按管理页显示顺序判定。
 */
@Composable
private fun GestureEditor(
    rec: PluginRecord,
    onRangeChange: (Pair<Float, Float>?) -> Unit,
    onReset: () -> Unit,
    otherEnabled: List<PluginRecord>,
) {
    val tones = LocalWarmTones.current
    val gesture = rec.manifest.gesture
    if (gesture == null || gesture.edge != "top") {
        Text(
            if (gesture == null) "该插件未声明手势，仅可通过顶栏图标唤出面板。"
            else "该插件声明的手势边缘（${gesture.edge}）本版本暂不支持，仅可通过顶栏图标唤出面板。",
            fontSize = 12.sp, lineHeight = 17.sp, color = tones.faint
        )
        return
    }
    val current = rec.gestureOverride ?: (gesture.rangeStart to gesture.rangeEnd)
    val currentlyOn = current.second > current.first
    var start by remember(rec.manifest.id, rec.gestureOverride) { mutableStateOf(if (currentlyOn) current.first else gesture.rangeStart.coerceAtLeast(0.25f)) }
    var end by remember(rec.manifest.id, rec.gestureOverride) { mutableStateOf(if (currentlyOn) current.second else gesture.rangeEnd.coerceAtLeast(start + 0.2f).coerceAtMost(1f)) }
    var enabled by remember(rec.manifest.id, rec.gestureOverride) { mutableStateOf(currentlyOn) }

    val conflictWith = remember(start, end, enabled, otherEnabled) {
        if (!enabled) null else otherEnabled.firstNotNullOfOrNull { other ->
            val r = effectivePluginGesture(other)
            if (r != null && start < r.endInclusive && end > r.start) other.manifest.name else null
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (enabled) "从顶部下拉这段区域可直接唤出" else "已关闭，仅能用顶栏图标唤出",
            fontSize = 11.5.sp, color = tones.faint, modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                enabled = on
                onRangeChange(if (on) start to end else 0f to 0f)
            }
        )
    }
    if (enabled) {
        Text(
            "区间 ${String.format(Locale.US, "%.2f", start)} ~ ${String.format(Locale.US, "%.2f", end)}" +
                "（沿顶部从左到右的比例；最左 0.25 起——最上方一段留给系统状态区）",
            fontSize = 11.sp, color = tones.faint
        )
        Slider(
            value = start,
            valueRange = 0.25f..1f,
            onValueChange = { v -> start = v.coerceAtMost(end - 0.2f).coerceAtLeast(0.25f) },
            onValueChangeFinished = { onRangeChange(start to end) },
            colors = SliderDefaults.colors(thumbColor = tones.accent, activeTrackColor = tones.accent, inactiveTrackColor = tones.line2)
        )
        Slider(
            value = end,
            valueRange = 0.25f..1f,
            onValueChange = { v -> end = v.coerceAtLeast(start + 0.2f).coerceAtMost(1f) },
            onValueChangeFinished = { onRangeChange(start to end) },
            colors = SliderDefaults.colors(thumbColor = tones.accent, activeTrackColor = tones.accent, inactiveTrackColor = tones.line2)
        )
        if (conflictWith != null) {
            Text(
                "⚠ 与「$conflictWith」的手势区间重叠，下拉命中哪个插件按管理页列表顺序判定，建议错开区间。",
                fontSize = 11.sp, lineHeight = 15.sp, color = tones.warn
            )
        }
        if (rec.gestureOverride != null) {
            Text(
                "恢复默认区间", fontSize = 12.sp, color = tones.accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReset).padding(vertical = 2.dp)
            )
        }
    }
}

/**
 * 某插件当前生效的手势区间：手动覆盖优先于 manifest 声明；仅认 `edge == "top"`
 * （v1 只实现顶部边缘，其余按无手势处理）；覆盖区间起止相等视为「已显式关闭」。
 * 供本文件的冲突提示与 ChatScreen 的手势落点匹配共用（同包可见，不加 private）。
 */
fun effectivePluginGesture(rec: PluginRecord): ClosedFloatingPointRange<Float>? {
    val override = rec.gestureOverride
    if (override != null) {
        return if (override.second > override.first) override.first..override.second else null
    }
    val g = rec.manifest.gesture ?: return null
    if (g.edge != "top") return null
    return g.rangeStart..g.rangeEnd
}

// ============================== 插件图标（规范 §3：单色 alpha 蒙版，主题色 tint） ==============================

/** 读插件目录下的图标 PNG（默认 icon.png，消息角标可指定专属图标）并按当前主题色着色；
 *  读取失败（缺失/损坏）时退回 Material 占位图标。 */
@Composable
fun PluginIcon(dir: File, size: Dp, tint: Color, file: String = "icon.png") {
    val bitmap = remember(dir.absolutePath, file) {
        runCatching { BitmapFactory.decodeFile(File(dir, file).absolutePath) }.getOrNull()?.asImageBitmap()
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap, contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(size)
            )
        } else {
            Icon(Icons.Filled.Extension, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.82f))
        }
    }
}
