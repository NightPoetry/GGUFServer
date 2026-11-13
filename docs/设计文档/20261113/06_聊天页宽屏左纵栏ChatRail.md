# 设计文档06 · 聊天页宽屏左纵栏（ChatRail）

## §0 设计原话（逐字）
> 「说着平板左侧纵栏，但是所有的菜单还是在顶部」（2026-11-12，附 768 聊天页截图：菜单图标横排在顶部，与发布会视频字幕「平板 · 左侧纵栏」承诺不符）

## §1 语义提炼
聊天页在 ≥600dp 宽屏下没有左纵栏——v1.1.20 的多尺寸改造只落在了模型市场页（HubRail）和设置页排版上，聊天页只把顶栏图标行距拉宽（wideBar 分支），导航仍是顶部横排。本设计把聊天页顶栏的全部职能迁入左侧纵栏，与 HubRail/FasFox 语态对齐；窄屏（<600dp）保持原顶栏不动。

## §2 算法/数据/接口
- **布局开关**：`isWide = LocalConfiguration.current.screenWidthDp >= 600`（与全项目 600dp 断点一致）。
- **宽屏结构**：外层 `Row(fillMaxSize)` = `ChatRail(200dp)` + `1dp 竖分隔线(tones.line)` + `Scaffold(weight(1f))`。窄屏结构不变。
- **ChatRail 构成**（自上而下）：
  1. 品牌行：GGUF-Server（accent 加粗 17sp）+ ServerIconWithLight（绿=RUNNING/橙=其他，busy 时呼吸闪烁）；
  2. 插件直显区：`panelTopbar=true` 的启用插件逐条渲染（PluginIcon 20dp + 名称 13.5sp，激活=accentSoft 底圆角横条，点击=开/收门户并定位该选项卡）；
  3. 分隔线（有插件条目时）；
  4. 固定条目五条：启动台（开合状态 accent 高亮）/ 历史会话（开左抽屉）/ 新对话（vm.newChat）/ 模型配置（开右抽屉）/ 设置（onOpenSettings）；
  5. foot 信息块：服务状态（服务运行中/未就绪）+ 当前模型名（alias），bg 底圆角块。
- **行为语义原样迁移**：顶栏五槽位回调逐一对齐（launchpadOpen 取反、drawerState.open、vm.newChat、configDrawerState.open、onOpenSettings、ServerState/activeRequests/busy 三输入驱动服务灯）。
- **窄屏回归**：`if (!isWide) TopAppBar(...)` —— 顶栏整段只在窄屏组合，宽屏不再组合（顶栏的下拉开门户手势随之只在窄屏可用；宽屏门户经消息区顶部热区下拉或纵栏插件条目开启）。

## §3 关键决策 Why
1. **纵栏复刻 HubRail 而非新设计**：用户已认证 HubRail 语态（「看看fasfox怎么做的」整改），两页一致才成体系；条目式（图标+文字一行一条）替代图标网格，可读性优先。
2. **「设置」进纵栏**：顶栏原五槽位没有独立设置入口（服务灯即入口），宽屏下聊天↔设置往返频繁，纵栏尾部常驻一条降低导航成本；窄屏仍走服务灯。
3. **启动台/门户/抽屉语义不重设计**：宽屏只是容器变化，交互模型与窄屏全同——保证两形态行为一致、回归风险最小。
4. **200dp 定宽**：与 HubRail 同宽，5 条目 13.5sp 文字+20dp 图标的舒适下限；foot 块 alias 最长 ellipsis。

## §4 验证
- 构建：gradle assembleDebug BUILD SUCCESSFUL（versionCode 32 / versionName 1.1.21）。
- 模拟器实拍（hubtest2, android-34 arm64, density 160）：768×1024 聊天页=纵栏+历史会话表格+composer，品牌行/五条目/foot「服务运行中 MiniCPM5-2B-Q4_K_XL」/右缘分隔线全部在位；375×812 回归=顶栏横排图标原样、无纵栏；1440×900=760dp 封顶列内 rail+聊天（封顶列边缘与窗口级抽屉浮层关系确认）。
- 行为验证：历史会话/新对话/模型配置/设置四条目点击等效旧顶栏槽位（tap 坐标命中即生效）；服务灯绿=运行中随服务启动/停止切换。

## §5 已知边界（刻意不做）
1. 启动台半屏毛玻璃面板的 blur 只作用于聊天列（rail 在 Scaffold 外不参与 blur）——宽屏开启动台时纵栏保持清晰，接受此不一致（低频路径）。
2. 顶栏下拉开门户手势只在窄屏存在；宽屏经纵栏插件条目/消息区顶部热区开启。
3. 抽屉（历史/配置）在宽屏仍是窗口级模态浮层，不钉成 pane——桌面三栏的「会话记录|聊天|推理参数」是抽屉展开态，非固定布局。

## §6 文件索引
- ui/ChatScreen.kt：ChatRail/ChatRailItem 新增（文末）；外层 Row 包裹 + topBar 条件化（约 304 行）；import statusBarsPadding。
- build.gradle.kts：versionCode 31→32、versionName 1.1.20→1.1.21。

## §7 版本履历
- 20261113 v1.1.21：初版（用户令「说着平板左侧纵栏，但是所有的菜单还是在顶部」补账）。
