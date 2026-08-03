package com.ggufserver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ggufserver.app.MainViewModel
import com.ggufserver.app.ServerBus
import com.ggufserver.app.ServerState
import com.ggufserver.app.data.ModelFile
import com.ggufserver.app.model.StorageLocation
import com.ggufserver.app.plugin.PluginStore
import com.ggufserver.app.util.NetworkUtils
import java.io.File
import java.util.Locale

/**
 * 设置页 = 服务器配置。服务器没启动时它就是 App 的入口；
 * 启动后入口换成聊天，这里从聊天右上角的服务器图标进来（onBack 返回聊天）。
 *
 * 布局遵循移动端设置页惯例（iOS 分组列表 / Material settings）：
 * 组标题带小图标、放在卡片【外面】，卡片只装内容——段落感来自「标题 + 卡片」的节奏；
 * 组内不加逐项分隔线，相关设置聚成小组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel = viewModel(), onBack: (() -> Unit)? = null) {
    // 插件管理是设置页内的一个轻量子页面（不经过 MainActivity 的顶层导航），
    // 用本地状态切换即可：返回时回到设置页本身，而不是聊天页。系统返回键也要先收这一层——
    // 否则会被 MainActivity 顶层的 BackHandler 直接吃掉、一路跳回聊天页，跳过设置页本身。
    var showPluginManager by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPluginManager) { showPluginManager = false }
    if (showPluginManager) {
        PluginManagerScreen(onBack = { showPluginManager = false })
        return
    }

    val tones = LocalWarmTones.current
    val state by vm.state.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val activeAlias by ServerBus.activeAlias.collectAsStateWithLifecycle()
    val servedPort by ServerBus.port.collectAsStateWithLifecycle()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importModel(it) } }

    // 从系统授权页回来时自动刷新（免得授权完还要手动点刷新）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.refreshLocations()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val idle = state == ServerState.STOPPED || state == ServerState.ERROR

    Scaffold(
        containerColor = tones.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tones.bg),
                title = { Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = tones.ink) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回聊天", tint = tones.muted)
                        }
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val callName = if (state == ServerState.RUNNING) activeAlias else vm.modelName
            ServiceGroup(state, activeModel, if (state == ServerState.RUNNING) servedPort else (vm.port.toIntOrNull() ?: 8080), callName)
            StorageGroup(vm)
            ModelsGroup(vm, enabled = idle, onPick = { picker.launch(arrayOf("*/*")) })
            ConfigGroup(vm, enabled = idle)
            ActionButton(state) { if (idle) vm.startServer() else vm.stopServer() }
            PluginEntryGroup(onOpen = { showPluginManager = true })
            LogsGroup(vm)
            Spacer(Modifier.height(8.dp))
        }
    }

    vm.importing?.let { p -> ImportDialog(p.name, p.copied, p.total) }
    vm.pendingDelete?.let { m -> DeleteConfirmDialog(m, onConfirm = vm::confirmDelete, onDismiss = vm::cancelDelete) }
}

// ---------- 分组骨架 ----------

/** 组标题：小图标 + 加粗标题，放在卡片外面；右侧可放动作。 */
@Composable
private fun GroupHeader(icon: ImageVector, title: String, trailing: (@Composable () -> Unit)? = null) {
    val tones = LocalWarmTones.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = tones.accent)
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink2,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 一个设置分组 = 组标题 + 一张卡片。 */
@Composable
private fun Group(
    icon: ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    container: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader(icon, title, trailing)
        WarmCard(Modifier.fillMaxWidth(), container = container ?: LocalWarmTones.current.card) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** 组标题右侧的文字动作（展开/清空…）。 */
@Composable
private fun HeaderAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp, color = LocalWarmTones.current.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

// ---------- 暖色扁平小组件 ----------

/** 输入框：标签在上，白底细边圆角框。 */
@Composable
private fun WarmField(
    label: String,
    value: String,
    enabled: Boolean,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    val tones = LocalWarmTones.current
    Column(Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Text(label, fontSize = 12.5.sp, color = tones.muted)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(tones.card, RoundedCornerShape(10.dp))
                .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { s -> onChange(if (numeric) s.filter { it.isDigit() } else s) },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.5.sp, color = tones.ink,
                    fontFamily = if (numeric) FontFamily.Monospace else FontFamily.Default
                ),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                cursorBrush = SolidColor(tones.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 暖色描边按钮。 */
@Composable
private fun WarmButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tones = LocalWarmTones.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(tones.card, shape)
            .border(1.dp, tones.line2, shape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon?.let {
                Icon(it, null, Modifier.size(15.dp), tint = tones.accent)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 13.sp, color = tones.ink2, maxLines = 1)
        }
    }
}

/** 可选择行：选中时暖灰底 + 赤陶对勾（替代默认单选钮）。 */
@Composable
private fun SelectRow(
    title: String,
    meta: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tones = LocalWarmTones.current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tones.userAvatar else Color.Transparent)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp, color = tones.ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            meta?.let { Text(it, fontSize = 11.5.sp, color = tones.faint) }
        }
        if (selected) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = tones.accent)
            Spacer(Modifier.width(6.dp))
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val tones = LocalWarmTones.current
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = tones.ink)
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 16.sp, color = tones.faint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

// ---------- 各分组 ----------

/** 「服务」组：状态 + 局域网 API 地址（运行中时整卡泛暖）。 */
@Composable
private fun ServiceGroup(state: ServerState, activeModel: String?, port: Int, callName: String) {
    val tones = LocalWarmTones.current
    val clipboard = LocalClipboardManager.current
    val ip = remember(state, port) { NetworkUtils.localIpAddress() }
    val running = state == ServerState.RUNNING
    val host = ip ?: "手机IP"
    val base = "http://$host:$port/v1"
    val model = callName.ifBlank { "local" }
    val (label, color) = when (state) {
        ServerState.STOPPED -> "已停止" to tones.faint
        ServerState.STARTING -> "启动中…" to tones.warn
        ServerState.RUNNING -> "运行中" to tones.ok
        ServerState.ERROR -> "出错" to tones.bad
    }

    Group(
        Icons.Outlined.Dns, "服务",
        container = if (running) tones.accentSoft else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color = color, shape = CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("状态：$label", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = tones.ink)
                activeModel?.let {
                    Text("模型：$it", fontSize = 12.5.sp, color = tones.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = tones.line)
        Text("API 地址（OpenAI 兼容 · 局域网）", fontSize = 12.5.sp, color = tones.muted)
        CopyableField(base, clipboard)
        Text("模型调用名（API 的 model 字段填这个）", fontSize = 12.5.sp, color = tones.muted)
        CopyableField(model, clipboard)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarmButton("复制地址", icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(base))
            }
            WarmButton("复制 curl", icon = Icons.Filled.ContentCopy, modifier = Modifier.weight(1f)) {
                val curl = "curl $base/chat/completions -H \"Content-Type: application/json\" " +
                    "-d '{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'"
                clipboard.setText(AnnotatedString(curl))
            }
        }
        val hint = when {
            ip == null -> "未检测到局域网 IP，请确认手机已连 Wi-Fi。"
            !running -> "服务启动后，用同一 WiFi 的设备访问上面的地址即可。"
            else -> "已在监听，可直接调用（model 填上面的名字，或 local 也行）。"
        }
        Text(hint, fontSize = 12.sp, lineHeight = 17.sp, color = if (ip == null) tones.bad else tones.muted)
    }
}

@Composable
private fun CopyableField(value: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val tones = LocalWarmTones.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tones.card, RoundedCornerShape(10.dp))
            .border(1.dp, tones.line2, RoundedCornerShape(10.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = tones.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "复制", Modifier.size(16.dp), tint = tones.muted)
        }
    }
}

@Composable
private fun StorageGroup(vm: MainViewModel) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current

    // SD卡/共享存储可自选任意目录（内部私有目录固定，放哪都一样）
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            treeUriToPath(uri)?.let { vm.setCustomDir(it) }
        }
    }
    val legacyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshLocations()
    }

    val customPath = vm.customDirPath
    val customUnreadable = remember(vm.locations, customPath) {
        customPath.isNotBlank() && runCatching {
            val d = File(customPath); !(d.isDirectory && d.listFiles() != null)
        }.getOrDefault(true)
    }

    Group(
        Icons.Outlined.Folder, "模型存放位置",
        trailing = {
            Icon(
                Icons.Filled.Refresh, contentDescription = "刷新（插拔SD卡后）",
                tint = tones.muted,
                modifier = Modifier.clip(CircleShape).clickable { vm.refreshLocations() }.padding(5.dp).size(17.dp)
            )
        }
    ) {
        vm.locations.forEach { loc ->
            val isCustom = customPath.isNotBlank() && loc.path == customPath
            SelectRow(
                title = loc.label,
                meta = if (isCustom) loc.path else "可用 ${formatBytes(loc.freeBytes)}",
                selected = vm.selectedLocation.path == loc.path,
                enabled = true,
                onSelect = { vm.selectLocation(loc) },
                trailing = if (isCustom) {
                    {
                        IconButton(onClick = { vm.clearCustomDir() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "移除自定义目录", Modifier.size(16.dp), tint = tones.faint)
                        }
                    }
                } else null
            )
        }
        WarmButton("选择自定义目录（SD卡 / 共享存储）", icon = Icons.Outlined.FolderOpen) {
            treePicker.launch(null)
        }
        if (customUnreadable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚠ 无法读取自定义目录，需要「所有文件访问」权限。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = tones.bad,
                    modifier = Modifier.weight(1f)
                )
                HeaderAction("去授权") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        openAllFilesAccess(context)
                    } else {
                        legacyPerm.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
        }
        Text(
            "内部（私有）位置固定；插着 SD 卡会多出「SD卡/外置存储」（免授权）；" +
                "「自定义」可选共享存储 / SD 卡的任意目录，已放好的 .gguf 直接被扫到。",
            fontSize = 12.sp, lineHeight = 17.sp, color = tones.faint
        )
    }
}

@Composable
private fun ModelsGroup(vm: MainViewModel, enabled: Boolean, onPick: () -> Unit) {
    val tones = LocalWarmTones.current
    Group(
        Icons.Outlined.Memory, "模型",
        trailing = {
            WarmButton(
                "上传 GGUF", icon = Icons.Filled.UploadFile,
                enabled = enabled && vm.importing == null,
                modifier = Modifier.width(IntrinsicSize.Max)
            ) { onPick() }
        }
    ) {
        if (vm.models.isEmpty()) {
            Text("此位置还没有模型。点「上传 GGUF」导入，或把 .gguf 直接拷到该目录。", fontSize = 12.5.sp, lineHeight = 18.sp, color = tones.muted)
        } else {
            vm.models.forEach { model ->
                SelectRow(
                    title = model.name,
                    meta = formatBytes(model.sizeBytes),
                    selected = vm.selected?.file == model.file,
                    enabled = enabled,
                    onSelect = { vm.selectModel(model) },
                    trailing = { DeleteIcon(enabled) { vm.requestDelete(model) } }
                )
            }
            WarmField("模型调用名（API 的 model 字段，可自由修改）", vm.modelName, enabled) { vm.modelName = it }
            MmprojSelector(vm, enabled)
        }
    }
}

@Composable
private fun DeleteIcon(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", Modifier.size(17.dp), tint = LocalWarmTones.current.faint)
    }
}

@Composable
private fun MmprojSelector(vm: MainViewModel, enabled: Boolean) {
    val tones = LocalWarmTones.current
    HorizontalDivider(color = tones.line)
    Text("视觉投影 mmproj（图片输入，可选）", fontSize = 12.5.sp, color = tones.muted)
    if (vm.mmprojFiles.isEmpty()) {
        Text(
            "上传对应模型的 mmproj-*.gguf 即可支持图片输入（同样用「上传 GGUF」导入）。",
            fontSize = 12.sp, lineHeight = 17.sp, color = tones.faint
        )
    } else {
        SelectRow(
            title = "无（纯文本）",
            meta = null,
            selected = vm.selectedMmproj == null,
            enabled = enabled,
            onSelect = { vm.selectMmproj(null) }
        )
        vm.mmprojFiles.forEach { mp ->
            SelectRow(
                title = mp.name,
                meta = formatBytes(mp.sizeBytes),
                selected = vm.selectedMmproj?.file == mp.file,
                enabled = enabled,
                onSelect = { vm.selectMmproj(mp) },
                trailing = { DeleteIcon(enabled) { vm.requestDelete(mp) } }
            )
        }
        SwitchRow(
            "自动压缩图片（加速视觉）",
            "服务端自动把大图缩小后再识别，客户端无需改动",
            vm.compressImages, enabled
        ) { vm.compressImages = it }
        if (vm.compressImages) {
            WarmField("图片最大边长 px（越小越快，建议 256–512）", vm.maxImageDim, enabled, numeric = true) { vm.maxImageDim = it }
        }
        if (vm.selectedMmproj != null) {
            Text(
                "⚠ 手机 CPU 跑视觉仍偏慢：即使压缩后一张图约几十秒，请把客户端超时设大（如 300 秒）。",
                fontSize = 12.sp, lineHeight = 17.sp, color = tones.bad
            )
        }
    }
}

@Composable
private fun ConfigGroup(vm: MainViewModel, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Group(
        Icons.Outlined.Tune, "运行参数",
        trailing = { HeaderAction(if (expanded) "收起" else "展开") { expanded = !expanded } }
    ) {
        WarmField("端口 (--port)", vm.port, enabled, numeric = true) { vm.port = it }
        SwitchRow("关闭思考模式（reasoning）", "推荐开启：思考型模型不会把回复都用在思考上", vm.disableThinking, enabled) { vm.disableThinking = it }
        if (expanded) {
            WarmField("上下文长度 (-c)", vm.contextSize, enabled, numeric = true) { vm.contextSize = it }
            WarmField("线程数 (-t)", vm.threads, enabled, numeric = true) { vm.threads = it }
            EngineBenchRow(vm, enabled)
            WarmField("GPU 层数 (-ngl，0=纯 CPU；测评会自动填)", vm.nGpuLayers, enabled, numeric = true) { vm.nGpuLayers = it }
            SwitchRow("禁用内存映射（--no-mmap）", "模型放在 SD 卡加载失败时可开启", vm.noMmap, enabled) { vm.noMmap = it }
            WarmField("API Key（可选，留空不鉴权）", vm.apiKey, enabled) { vm.apiKey = it }
        }
    }
}

/**
 * “最优引擎选择测评”：对当前选中模型实测 CPU / GPU 速度，自动选最快的引擎并记住（按模型）。
 * GPU 在本机更慢或崩溃会被自动判为 CPU；更快才选 GPU——由真机数字决定，不写死。
 */
@Composable
private fun EngineBenchRow(vm: MainViewModel, enabled: Boolean) {
    val tones = LocalWarmTones.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("最优引擎选择测评", fontSize = 14.sp, color = tones.ink)
                Text(
                    if (vm.hasGpuBackend) "实测本机 CPU / GPU 速度，自动选最快的引擎"
                    else "实测本机 CPU 速度并记住（本包未含 GPU 后端）",
                    fontSize = 12.sp, color = tones.faint, lineHeight = 16.sp
                )
            }
            TextButton(
                onClick = { vm.runEngineBenchmark() },
                enabled = enabled && !vm.benchmarking && vm.selected != null
            ) {
                if (vm.benchmarking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tones.accent)
                } else {
                    Text("测评", color = tones.accent)
                }
            }
        }
        if (vm.benchmarking && vm.benchmarkProgress.isNotBlank()) {
            Text(vm.benchmarkProgress, fontSize = 12.sp, color = tones.accent, lineHeight = 16.sp)
        } else {
            val note = vm.selectedEngineNote
            if (note.isNotBlank()) {
                Text("上次测评：$note", fontSize = 12.sp, color = tones.faint, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ActionButton(state: ServerState, onClick: () -> Unit) {
    val tones = LocalWarmTones.current
    val running = state == ServerState.RUNNING || state == ServerState.STARTING
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(13.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = tones.bad) else ButtonDefaults.buttonColors()
    ) {
        Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(if (running) "停止服务" else "启动服务", fontSize = 15.sp)
    }
}

/** 「插件」组：一行导航到插件管理页（第二阶段新增，见《工具插件规范》§9）。 */
@Composable
private fun PluginEntryGroup(onOpen: () -> Unit) {
    val tones = LocalWarmTones.current
    val plugins by PluginStore.plugins.collectAsStateWithLifecycle()
    val enabledCount = plugins.count { it.enabled }
    Group(Icons.Filled.Extension, "插件") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpen)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("插件管理", fontSize = 14.sp, color = tones.ink)
                Text(
                    if (plugins.isEmpty()) "尚未安装插件，默认全部关闭"
                    else "已安装 ${plugins.size} 个 · 已启用 $enabledCount 个",
                    fontSize = 11.5.sp, color = tones.faint
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tones.faintIcon)
        }
    }
}

@Composable
private fun LogsGroup(vm: MainViewModel) {
    val tones = LocalWarmTones.current
    val context = LocalContext.current
    val logs by vm.logs.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(logs.size) { scroll.scrollTo(scroll.maxValue) }
    // 占用大小随日志增长/开关变化刷新
    val sizeText = remember(logs.size, vm.logRecording) { formatBytes(vm.logFileSize()) }
    // 导出：把日志文件写到用户选的位置（系统「另存为」），供分析 bug
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write((vm.logFile()?.takeIf { it.exists() }?.readText() ?: "（无日志）").toByteArray())
            }
        }
    }
    Group(
        Icons.Outlined.Terminal, "日志",
        trailing = { HeaderAction("清空") { vm.clearLogs() } }
    ) {
        SwitchRow(
            "日志记录模式",
            "记录服务、网关、插件、错误等系统调用与行为，落盘到文件、供导出分析问题",
            checked = vm.logRecording, enabled = true,
            onChange = { vm.toggleLogRecording(it) }
        )
        if (vm.logRecording) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("日志占用：$sizeText", fontSize = 12.5.sp, color = tones.muted, modifier = Modifier.weight(1f))
                HeaderAction("导出日志") { exporter.launch("GGUF-debug-log.txt") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = tones.side,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
        ) {
            Box(Modifier.fillMaxWidth().verticalScroll(scroll).padding(10.dp)) {
                Text(
                    text = if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    color = tones.muted
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(model: ModelFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除模型") },
        text = { Text("确定要删除 ${model.name} 吗？\n这会从存储中真正删除该文件（${formatBytes(model.sizeBytes)}），不可恢复。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ImportDialog(name: String, copied: Long, total: Long) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text("正在导入模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (total > 0) {
                    LinearProgressIndicator(progress = { (copied.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${formatBytes(copied)} / ${formatBytes(total)}")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("已复制 ${formatBytes(copied)}")
                    }
                }
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

/** 系统目录选择器返回的 tree Uri → 真实文件路径（llama-server 子进程只认真实路径）。 */
private fun treeUriToPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val split = docId.split(":", limit = 2)
    val volume = split[0]
    val rel = split.getOrNull(1).orEmpty()
    val base = if (volume.equals("primary", ignoreCase = true)) {
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (rel.isBlank()) base else "$base/$rel"
}

/** 跳到系统的「所有文件访问」授权页（Android 11+）。 */
private fun openAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val direct = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
