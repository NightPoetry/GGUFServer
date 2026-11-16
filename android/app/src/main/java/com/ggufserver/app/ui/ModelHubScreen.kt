package com.ggufserver.app.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ggufserver.app.ServerBus
import com.ggufserver.app.plugin.HubApi
import com.ggufserver.app.plugin.HubJudge
import com.ggufserver.app.plugin.ModelHubDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型市场（原生内置页，v1.1.19 起从插件形态转正）：
 * 热门 GGUF 按下载量降序 / 关键字搜索（不过滤不排序，设计文档04 §0 原话）/
 * 按手机内存磁盘判定「带得动」（HubJudge）/ 任选量化下载（ModelHubDownloader，跨页面存续）。
 *
 * 多尺寸自适应（v1.1.20，设计文档04 增补2）：
 *  - compact（宽 <600dp，手机竖屏）：横向顶栏（返回+标题）+ 内容列——原布局。
 *  - medium（宽 ≥600dp，平板/手机横屏/桌面模式）：顶栏转**左侧纵栏**（返回 + 竖排标题 + tab 纵列），
 *    内容列右置；超宽时内容封顶 720dp 居中防拉伸（密度纪律）。
 * 断点判据是纯函数 [hubLayout]（进单测）；旋转由 Activity configChanges 自理，状态经 remember 存活仅重布局。
 * 单页滚动纪律：外壳（栏/设备行/进度卡/搜索框）固定，列表是唯一滚动区。
 */
enum class HubLayout { COMPACT, MEDIUM }

/** 宽度断点：Material 宽度尺寸类的 600dp 线。纯函数，阈值有单测钉着。 */
fun hubLayout(widthDp: Float): HubLayout =
    if (widthDp >= 600f) HubLayout.MEDIUM else HubLayout.COMPACT

enum class HubTab { POPULAR, SEARCH }

data class HubRow(val model: HubApi.HubModel, val judgment: HubJudge.Judgment?, val failed: Boolean)

/** 页面级状态：列表抓取 + 逐仓判定（顺序请求，对镜像站礼貌；切页用 seq 防串台）。 */
class HubState {
    var tab by mutableStateOf(HubTab.POPULAR)
    var keyword by mutableStateOf("")
    var rows by mutableStateOf<List<HubRow>>(emptyList())
    var status by mutableStateOf("加载中…")
    var foot by mutableStateOf("")
    var loading by mutableStateOf(false)
    var deviceLine by mutableStateOf("读取设备信息…")
    var ram by mutableStateOf(0L)
    var disk by mutableStateOf(0L)
    var expanded by mutableStateOf(emptySet<String>())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val judged = ConcurrentHashMap<String, HubJudge.Judgment>()
    private var appContext: Context? = null
    private var seq = 0

    fun dispose() = scope.cancel()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            runCatching { ModelHubDevice.info(appContext!!) }
                .onSuccess { (t, d) ->
                    ram = t
                    disk = d
                    deviceLine = "内存 ${human(t)} · 目标目录可用 ${human(d)}（下载落到当前选中的模型位置）"
                }
            load()
        }
    }

    fun switchTab(t: HubTab) {
        tab = t
        load()
    }

    fun submitSearch() {
        if (tab == HubTab.SEARCH && keyword.isNotBlank()) load()
    }

    fun load() {
        val ctx = appContext ?: return
        val mySeq = ++seq
        scope.launch {
            loading = true
            status = "请求列表中…"
            foot = ""
            try {
                val models = withContext(Dispatchers.IO) {
                    if (tab == HubTab.SEARCH) HubApi.search(keyword.trim(), 30) else HubApi.popular(30)
                }
                if (mySeq != seq) return@launch
                if (models.isEmpty()) {
                    status = if (tab == HubTab.SEARCH) "没搜到，换个关键字试试。" else "列表为空。"
                    rows = emptyList()
                    loading = false
                    return@launch
                }
                rows = models.map { HubRow(it, judged[it.id], false) }
                status = ""
                var hidden = 0
                for (m in models) {
                    if (mySeq != seq) return@launch
                    val j = try {
                        judgeRepo(m.id)
                    } catch (e: Exception) {
                        rows = rows.map { if (it.model.id == m.id) it.copy(failed = true) else it }
                        continue
                    }
                    if (mySeq != seq) return@launch
                    // 热门页：完全带不动的整条隐藏（原话「过滤掉完全不能运行的」）；
                    // 搜索页：不过滤，原样展示只打标（原话「搜出什么来就是什么」）。
                    if (!j.runnable && tab == HubTab.POPULAR) {
                        hidden++
                        rows = rows.filter { it.model.id != m.id }
                    } else {
                        rows = rows.map { if (it.model.id == m.id) it.copy(judgment = j) else it }
                    }
                    foot = when {
                        tab == HubTab.POPULAR && hidden > 0 ->
                            "已按手机配置隐藏 $hidden 个带不动的模型；切到「搜索」可不过滤地看全部。"
                        tab == HubTab.SEARCH ->
                            "搜索结果不做过滤；「带不动」只是提示，仍可强行下载（不建议）。"
                        else -> foot
                    }
                }
                loading = false
            } catch (e: Exception) {
                if (mySeq == seq) {
                    status = "拉取失败：${e.message ?: e}（检查网络后重进或重新搜索）"
                    loading = false
                }
            }
        }
    }

    private suspend fun judgeRepo(repoId: String): HubJudge.Judgment {
        judged[repoId]?.let { return it }
        val files = withContext(Dispatchers.IO) { HubApi.tree(repoId) }
        val j = withContext(Dispatchers.Default) {
            HubJudge.judge(files.map { it.path to it.size }, ram, disk)
        }
        judged[repoId] = j
        return j
    }

    fun startDownload(context: Context, repoId: String, name: String, size: Long) {
        try {
            ModelHubDownloader.start(
                context.applicationContext, repoId, name,
                HubApi.downloadUrl(repoId, name), size,
            )
        } catch (e: Exception) {
            ServerBus.appendLog("[模型市场] ${e.message}")
        }
    }

    fun toggleExpand(repoId: String) {
        expanded = if (repoId in expanded) expanded - repoId else expanded + repoId
    }

    companion object {
        fun human(b: Long): String = when {
            b >= 1073741824L -> String.format("%.1f GB", b / 1073741824.0)
            b >= 1048576L -> "${b / 1048576L} MB"
            b >= 1024L -> "${b / 1024L} KB"
            else -> "$b B"
        }

        fun humanDl(n: Long): String = when {
            n >= 1_000_000L -> String.format("%.1fM 下载", n / 1_000_000.0)
            n >= 1_000L -> "${n / 1_000L}K 下载"
            else -> "$n 下载"
        }
    }
}

/** 设备快照取数（下载判定与磁盘校验同一口径，收在这里避免 UI 直接摸 Android 服务）。 */
object ModelHubDevice {
    fun info(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        val dir = com.ggufserver.app.plugin.ModelHub.selectedModelsDir(context)
        val stat = runCatching { android.os.StatFs(dir.path) }.getOrNull()
        val free = stat?.let { runCatching { it.availableBytes }.getOrDefault(dir.freeSpace) } ?: dir.freeSpace
        return (mem.totalMem) to free
    }
}

@Composable
fun ModelHubScreen(onBack: () -> Unit) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current
    val hub = remember { HubState() }
    val dl by ModelHubDownloader.state.collectAsState()

    DisposableEffect(Unit) {
        hub.init(context)
        onDispose { hub.dispose() }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(tones.bg)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        when (hubLayout(maxWidth.value)) {
            HubLayout.MEDIUM -> {
                Row(Modifier.fillMaxSize()) {
                    HubRail(hub = hub, onBack = onBack)
                    // weight(fill=false)：内容列只占自然宽（≤720 封顶），不被强制拉满整个右栏
                                        Spacer(Modifier.width(1.dp).fillMaxHeight().background(tones.line))
HubMainColumn(
                        hub = hub, dl = dl, showTabs = false,
                        modifier = Modifier.weight(1f, fill = false).fillMaxHeight()
                    )
                }
            }
            HubLayout.COMPACT -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", Modifier.size(22.dp), tint = tones.ink)
                        }
                        Text("模型市场", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tones.ink)
                    }
                    HubMainColumn(hub = hub, dl = dl, showTabs = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * 宽屏左侧纵栏——照 FasFox 面板侧栏语态重写（用户令「看看fasfox怎么做的」）：
 * 横排品牌行（返回钮+标题）+ 横排导航条目（图标+文字一行一条，选中=浅赤陶底圆角横条）
 * + 底部 foot 信息块；右缘 1dp 分隔线。竖排一字标题与药丸 chip 已废弃。
 */
@Composable
private fun HubRail(hub: HubState, onBack: () -> Unit) {
    val tones = LocalWarmTones.current
    Column(Modifier.width(200.dp).fillMaxHeight().background(tones.card)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", Modifier.size(20.dp), tint = tones.ink)
            }
            Spacer(Modifier.width(4.dp))
            Text("模型市场", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tones.accent)
        }
        Spacer(Modifier.height(4.dp))
        HubNavItem(
            icon = Icons.Outlined.Whatshot, label = "热门",
            active = hub.tab == HubTab.POPULAR
        ) { hub.switchTab(HubTab.POPULAR) }
        HubNavItem(
            icon = Icons.Outlined.Search, label = "搜索",
            active = hub.tab == HubTab.SEARCH
        ) { hub.switchTab(HubTab.SEARCH) }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = tones.line)
        Column(
            Modifier.fillMaxWidth().padding(12.dp)
                .background(tones.bg, RoundedCornerShape(8.dp))
                .padding(10.dp, 8.dp)
        ) {
            if (hub.ram > 0) {
                Text("内存 " + HubState.human(hub.ram), fontSize = 11.sp, color = tones.ink2, lineHeight = 17.sp)
                Text("目录可用 " + HubState.human(hub.disk), fontSize = 11.sp, color = tones.ink2, lineHeight = 17.sp)
            } else {
                Text("读取设备信息…", fontSize = 11.sp, color = tones.muted, lineHeight = 17.sp)
            }
            Text("下载落到当前选中的模型位置", fontSize = 10.sp, color = tones.faint, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            "GGUF 镜像源", fontSize = 10.sp, color = tones.faint,
            modifier = Modifier.padding(start = 14.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun HubNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) tones.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(9.dp, 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon, contentDescription = null, Modifier.size(20.dp),
            tint = if (active) tones.accent else tones.muted
        )
        Text(
            label, fontSize = 13.5.sp,
            color = if (active) tones.accent else tones.muted,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 内容主列（两种布局共用）：设备行 + 下载进度卡 + （compact 时）tab 行 + （搜索页）搜索框 + 状态/脚注 + 列表。
 * 列表是唯一滚动区；超宽时整列封顶 720dp 居中（密度纪律：行不无限拉伸）。
 */
@Composable
private fun HubMainColumn(
    hub: HubState,
    dl: ModelHubDownloader.Snap,
    showTabs: Boolean,
    modifier: Modifier = Modifier,
) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current
    Column(
        modifier
            .widthIn(max = 720.dp)
            .padding(horizontal = 14.dp)
    ) {
        Text(hub.deviceLine, fontSize = 11.5.sp, color = tones.muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))

        // 下载进度卡（有任务时页首常驻；下载跨页面存续）
        if (dl.phase != ModelHubDownloader.Phase.IDLE) {
            val pct = if (dl.total > 0) ((dl.received * 100) / dl.total).coerceAtMost(100).toInt() else 0
            Surface(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                color = tones.card, shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, tones.line)
            ) {
                Column(Modifier.padding(9.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dl.name.ifBlank { dl.repoId }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = tones.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(
                            when (dl.phase) {
                                ModelHubDownloader.Phase.RUNNING ->
                                    "${HubState.human(dl.received)} / ${HubState.human(dl.total)}"
                                ModelHubDownloader.Phase.DONE -> "完成"
                                ModelHubDownloader.Phase.CANCELLED -> "已取消（可续传）"
                                ModelHubDownloader.Phase.FAILED -> "失败"
                                else -> ""
                            },
                            fontSize = 11.sp, color = tones.muted
                        )
                    }
                    if (dl.phase == ModelHubDownloader.Phase.RUNNING) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp)
                                .background(tones.line, RoundedCornerShape(999.dp))
                        ) {
                            Box(
                                Modifier.fillMaxWidth(pct / 100f).height(6.dp)
                                    .background(tones.accent, RoundedCornerShape(999.dp))
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("下载中 · $pct% · ${HubState.human(dl.speedBps)}/s", fontSize = 11.sp, color = tones.muted)
                            Text(
                                "取消", fontSize = 11.5.sp, color = tones.accent,
                                modifier = Modifier.clickable { ModelHubDownloader.cancel() }
                            )
                        }
                    } else {
                        Text(
                            when (dl.phase) {
                                ModelHubDownloader.Phase.DONE -> "已存进模型目录，模型列表里直接选。"
                                ModelHubDownloader.Phase.FAILED -> dl.error
                                else -> ""
                            },
                            fontSize = 11.sp, color = if (dl.phase == ModelHubDownloader.Phase.FAILED) tones.bad else tones.muted
                        )
                        if (dl.phase != ModelHubDownloader.Phase.RUNNING) {
                            Text(
                                "收起", fontSize = 11.5.sp, color = tones.accent,
                                modifier = Modifier.padding(top = 4.dp).clickable { ModelHubDownloader.dismiss() }
                            )
                        }
                    }
                }
            }
        }

        if (showTabs) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubTabChip("热门", hub.tab == HubTab.POPULAR, Modifier.weight(1f)) { hub.switchTab(HubTab.POPULAR) }
                HubTabChip("搜索", hub.tab == HubTab.SEARCH, Modifier.weight(1f)) { hub.switchTab(HubTab.SEARCH) }
            }
        }
        if (hub.tab == HubTab.SEARCH) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = hub.keyword,
                    onValueChange = { hub.keyword = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = tones.ink),
                    cursorBrush = SolidColor(tones.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { hub.submitSearch() }),
                    modifier = Modifier
                        .weight(1f)
                        .background(tones.card, RoundedCornerShape(999.dp))
                        .border(1.dp, tones.line, RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box {
                            if (hub.keyword.isEmpty()) Text("输入关键字，如 qwen3 / minicpm5 / coder", fontSize = 12.5.sp, color = tones.faint, maxLines = 1)
                            inner
                        }
                    }
                )
                Text(
                    "搜索", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tones.card,
                    modifier = Modifier
                        .background(tones.accent, RoundedCornerShape(999.dp))
                        .clickable { hub.submitSearch() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        if (hub.status.isNotEmpty()) {
            Text(hub.status, fontSize = 12.sp, color = tones.muted, modifier = Modifier.padding(top = 10.dp))
        }
        if (hub.foot.isNotEmpty()) {
            Text(hub.foot, fontSize = 11.sp, color = tones.muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            items(hub.rows, key = { it.model.id }) { row ->
                HubRowItem(hub, row)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun HubTabChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    Box(
        modifier
            .background(if (on) tones.accentSoft else tones.card, RoundedCornerShape(8.dp))
            .border(1.dp, if (on) tones.accent else tones.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(7.dp, 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 12.5.sp,
            color = if (on) tones.accent else tones.muted,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun HubBadge(verdict: String) {
    val tones = LocalWarmTones.current
    val (label, color) = when (verdict) {
        "ok" -> "可运行" to tones.ok
        "tight" -> "勉强" to tones.warn
        else -> "带不动" to tones.bad
    }
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.09f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 2.5.dp)
    )
}

@Composable
private fun HubChip(text: String) {
    val tones = LocalWarmTones.current
    Text(
        text, fontSize = 11.sp, color = tones.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(tones.card, RoundedCornerShape(999.dp))
            .border(1.dp, tones.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 2.5.dp)
    )
}

@Composable
private fun HubRowItem(hub: HubState, row: HubRow) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current
    val j = row.judgment

    Column(
        Modifier
            .fillMaxWidth()
            .background(tones.card, RoundedCornerShape(8.dp))
            .border(0.6.dp, tones.line, RoundedCornerShape(8.dp))
            .padding(10.dp, 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.model.id, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(HubState.humanDl(row.model.downloads), fontSize = 11.sp, color = tones.muted)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                row.failed -> Text("判定失败，重新进入本页可重试", fontSize = 11.sp, color = tones.bad)
                j == null -> HubChip("判定中…")
                else -> {
                    val anyOk = j.files.any { it.verdict == "ok" }
                    HubBadge(if (!j.runnable) "no" else if (anyOk) "ok" else "tight")
                    if (j.bestName != null) {
                        // 弹性截断：窄屏时推荐量化 chip 让位给下载按钮（密度纪律：按钮永不被挤走）
                        Row(Modifier.weight(1f, fill = false)) {
                            HubChip("推荐 ${j.bestName} · ${HubState.human(j.bestSize)}")
                        }
                    }
                    if (j.runnable && j.bestName != null) {
                        Text(
                            "下载", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tones.card,
                            modifier = Modifier
                                .background(tones.accent, RoundedCornerShape(999.dp))
                                .clickable { hub.startDownload(context, row.model.id, j.bestName, j.bestSize) }
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        if (j != null && j.files.isNotEmpty()) {
            Row(
                Modifier.padding(top = 6.dp)
                    .background(tones.card, RoundedCornerShape(999.dp))
                    .border(1.dp, tones.line, RoundedCornerShape(999.dp))
                    .clickable { hub.toggleExpand(row.model.id) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (row.model.id in hub.expanded) "收起 ${j.files.size} 个量化" else "全部 ${j.files.size} 个量化",
                    fontSize = 11.sp, color = tones.ink2
                )
                Icon(
                    if (row.model.id in hub.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, Modifier.size(14.dp), tint = tones.muted
                )
            }
            if (row.model.id in hub.expanded) {
                j.files.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            f.name, fontSize = 11.sp, color = tones.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        HubChip(HubState.human(f.size))
                        Spacer(Modifier.width(4.dp))
                        HubBadge(f.verdict)
                        if (f.verdict != "no") {
                            Text(
                                "下载", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tones.card,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .background(tones.accent, RoundedCornerShape(999.dp))
                                    .clickable { hub.startDownload(context, row.model.id, f.name, f.size) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
