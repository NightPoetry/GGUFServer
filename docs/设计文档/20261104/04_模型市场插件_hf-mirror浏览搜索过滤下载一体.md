# 04 · 模型市场插件——hf-mirror 浏览/搜索/过滤/下载一体

> 版本：v1.0.0 初版（2026-11-04）｜关联：01（构建关系）、02（向量模型服务）、03（引擎升级）、工具插件规范 §6/§8
> 一句话：第 13 个内置插件 com.gguf.modelhub「模型市场」——设置页入口全屏页，接 hf-mirror（HF 国内同构镜像）：热门 GGUF 按下载量降序、按手机内存/磁盘判定「带得动」并隐藏完全跑不动的（搜索模式不过滤不排序原样展示）、任选量化直接下载进当前选中模型位置，下完自动进模型列表；UI/编排在插件页、判定与下载在宿主（Kotlin 单测钉死语义）。

## §0 设计原话（逐字，20261104）

> 「它是不是有很强的插件系统来着。能不能加入一个下载插件，就是集成我们国内可以流畅下载的那个模型服务网站，然后能出现模型列表，然后从热门到不热门排列，并且是根据手机的配置过滤掉那些完全不能运行的模型，然后得到一个列表，同时也可以直接搜索。直接搜索的话就不过滤了，你搜出什么来就是什么关键字搜索，然后这样用户就可以不用自己下模型再导入了，可以直接下下到指定的位置读取」

## §1 语义提炼

1. 「一个下载插件」→ 走既有插件体系：`assets/plugins/com.gguf.modelhub/`，manifest 用 `settingsCard: hub.html`（与重型记忆设置卡片同一条 `prepareSettingsWebView` 链路），设置页「模型」组加全屏入口按钮。
2. 「国内可以流畅下载的那个模型服务网站」→ hf-mirror.com（HF 官方 API 与文件的同构镜像；上一轮实测下载 11MB/s 级）。API 行为差异见技术发现005：列表过滤用 `filter=gguf`（`library=` 参数被镜像忽略）。
3. 「从热门到不热门排列」→ `/api/models?filter=gguf&sort=downloads&direction=-1&limit=30`。
4. 「根据手机的配置过滤掉完全不能运行的模型」→ 判定器（HubJudge，Kotlin）按「仓库最小量化文件」裁定：连 tight 都没有 = 完全不能运行 → 热门页整条隐藏 + 页脚提示隐藏计数；tight 及以上保留并标注推荐量化。
5. 「直接搜索就不过滤，搜出什么来就是什么」→ 搜索 URL **不带 sort、不带 filter**（站点相关性排序原样透传），结果不做隐藏，只打「可运行/勉强/带不动」标。
6. 「不用自己下模型再导入，直接下到指定位置读取」→ 下载落设置页**当前选中的模型位置**（ModelStore.locations() + settings.locationPath 同一口径），完成后 MainViewModel 自动 refresh——文件直接出现在模型列表。

## §2 算法 / 数据 / 接口

### 2.1 hf-mirror API（HubApi，纯 Kotlin 可单测）

| 用途 | 端点 | 说明 |
|---|---|---|
| 热门 | `/api/models?filter=gguf&sort=downloads&direction=-1&limit=30` | GGUF 库内下载量降序 |
| 搜索 | `/api/models?search=<kw>&limit=30` | 无 sort 无 filter（原话要求） |
| 目录树 | `/api/models/<repoId>/tree/main` | 只取 `.gguf` 文件（排除目录项与 `mmproj*`），size 取 `lfs.size` 回退 `size` |
| 下载 | `/<repoId>/resolve/main/<path>` | 路径分段 encodeURIComponent |

插件页面经 `gguf.net.fetchJson`（宿主代取，过 CORS）调列表/树，权限 `net:internet`（2MB 响应上限对 API JSON 充裕）。

### 2.2 设备判定（HubJudge，阈值钉死进单测）

- 从文件名尾段识别量化（`-` 或 `.` 后的 `q4_k_xl`/`iq3_xxs`/`bf16`…；认不出按 6.0 位宽参与排序）
- verdict：`ok` = size ≤ 0.75×totalRam 且 ≤ 0.9×freeDisk；`tight` = size ≤ totalRam 且 ≤ freeDisk；否则 `no`
- best = 最小的 ok；无 ok 取最小 tight；`runnable = best != null`
- 浏览页隐藏规则：`runnable == false`（完全不能运行）→ 整条隐藏；`tight` 保留但打「勉强」标

### 2.3 下载（ModelHubDownloader，宿主应用级单例）

- HttpURLConnection 流式写 `<名字>.part`；续传带 `Range: bytes=<have>-`，若服务器回 200 全量则弃 .part 重来；流断自动续传重试，封顶 8 次；速度 EMA（600ms 采样）
- 完成后改名落位，同名不覆盖（`名字 (1).gguf`）；取消保留 .part
- 一次只允许一个下载；**下载 URL 必须以 `https://hf-mirror.com/` 开头**（这是「从模型市场下载」的通道，不是任意 URL 下载器）
- 状态 StateFlow：`hub.downloadStatus` 桥返回快照 JSON（phase/received/total/speed/error/file）

### 2.4 桥新增（PluginBridge 分发表，全部免权限，与 host.models 同级）

`host.deviceInfo`（总内存/availRam/目标目录 freeDisk/ABI）、`hub.judge`、`hub.download`、`hub.downloadStatus`、`hub.cancel`、`hub.dismiss`。

### 2.5 插件包（assets/plugins/com.gguf.modelhub/）

- `manifest.json`：`entry.ui: hub.html` + `settingsCard: hub.html` + `permissions: ["net:internet"]`；无 hooks → 无需 service.js
- `hub.html`：热门/搜索双 tab；行式列表（repo id + 下载量 + 徽标 + 推荐量化 chip + 下载按钮 + `<details>` 展开全部量化任选）；下载进度卡（进度条/速度/取消/完成态）页首常驻；判定懒加载（顺序请求不轰站点 + 页内缓存 + 切页 seq 防串台）
- `icon.png`：96×96 白色单色 alpha 蒙版（规范 §3，宿主按主题着色）

## §3 关键决策 Why

1. **插件 + 宿主混合，而非纯插件或纯原生页**——下载引擎无论如何都得在宿主（WebView 没有后台存续保证、没有写模型目录的通道）；判定器也放宿主（阈值是语义，进 JVM 单测）。插件壳的价值：入口与 UI 迭代不占宿主编译、与既有插件体系一致（用户原话「下载插件」）、可像其他插件一样被启用/停用。别把判定逻辑搬进 JS——那会绕开单测。
2. **搜索不带排序与过滤**——设计原话白纸黑字。`searchUrl` 有专门的失败断言（HubApiTest「搜索URL不带排序不过滤」），谁想"顺手优化"排序会被测试拦住。
3. **隐藏规则只看「最小量化也带不动」**——原话是「过滤掉**完全**不能运行的」：仓库只要有一个能跑的量化就保留（推荐那个），不因 Q8 太大而错杀整个仓库。
4. **下载源钉死镜像站域名**——桥的 download 校验前缀，杜绝插件页被注入后借道把任意 URL 的内容写进模型目录。模型市场是功能通道，不是通用下载器。
5. **一次只跑一个下载**——手机带宽下并发下载互抢且无意义；同时简化进度卡 UI（单卡）。
6. **入口放设置页「模型」组而非聊天面板**——用户场景是「装模型」，模型管理就在那；且 settingsCard 全屏链路是现成验证过的（插件管理页同款）。聊天侧手势/面板与既有 12 插件的冲突矩阵不必新增变量。
7. **热门页懒判定顺序请求**——30 个仓库 30 次树查询，顺序打点对镜像站礼貌，且行骨架先出、判定渐次回填，感知速度不受影响；页内缓存让切 tab 不重复请求。

## §4 验证（命令 + 断言 + 实测数字）

- JVM 单测 **61 项全绿**（42 → 61）：`HubJudgeTest` 9 项（量化识别含 `-`/`.` 双命名、三档阈值边界、磁盘不足不算舒适、best 挑选、全 no 无 best、未知量化排序位、toJson 形状）＋ `HubApiTest` 8 项（热门 URL 四要素、**搜索 URL 无 sort 无 filter 的失败断言**、limit 钳制、URL 编码、列表/树解析容错、域名钉死）；`BuiltinManifestTest` 自动枚举校验新插件 manifest 与 icon.png 存在性；`check-plugins.js` 插件自检通过。
- 插件页 JS：node `--check` 语法零错；emoji 扫描（U+1F300–1FAFF / U+2600–27BF）零命中。
- 打包：`assembleDebug` RC=0（2m24s），`debug/GGUF-Server-debug-1.1.18.apk`（136M，versionCode 29）归档，APK 内含 modelhub 三件（manifest/hub.html/icon.png）。
- 迭代过程如实记录：SettingsScreen 曾因插入锚漏了 `if` 收尾括号编译失败一次；ModelHub 曾因裸引用 BASE 与 fold 语法错失败一轮；HubJudge.parseQuant 的二级 substringAfterLast 语义错（无点号返回空串）由单测当场抓住——单测纪律生效的实证。
- **待回填（真机）**：hub 页多尺寸目验（375/494/768/1440 矩阵）、真实下载全流程（含断网续传）、下载完成自动入列表、dark 模式下 icon 可读性。

## §5 文件索引

- 插件：`android/app/src/main/assets/plugins/com.gguf.modelhub/{manifest.json, hub.html, icon.png}`
- 宿主：`plugin/ModelHub.kt`（HubApi + HubJudge + ModelHubDownloader + ModelHub）、`plugin/PluginBridge.kt`（六桥口）、`ui/SettingsScreen.kt`（入口按钮 + HubScreen 全屏页）、`MainViewModel.kt`（DONE → refresh）、`assets/gguf-sdk.js`（gguf.hub.* / gguf.host.deviceInfo）
- 测试：`android/app/src/test/java/com/ggufserver/app/plugin/{HubJudgeTest, HubApiTest}.kt`

## §6 版本履历

- v1.1.18（versionCode 29，2026-11-04）：模型市场 v1.0.0 初版（本文档）。

## §7 重建清单

- 语义：浏览（降序+隐藏完全不能跑）/搜索（原样+打标）/判定（0.75×RAM & 0.9×disk → ok；≤RAM&≤disk → tight）/下载（镜像站单源、单任务、断点续传、落当前选中位置）。
- 数据流：页面 fetchJson→列表 → 逐仓 tree→hub.judge→渲染；下载 hub.download→宿主单例→StateFlow→hub.downloadStatus 轮询→DONE→ViewModel refresh。
- 规则：阈值改动必须先改 HubJudgeTest；搜索参数「无 sort 无 filter」有失败断言看守。
- 接口：桥六口名与入参见 §2.4；镜像站端点见 §2.1。
- 界面：设置页「模型」组首行「模型市场」按钮 → 全屏 HubScreen（WebView 壳）。
- 验证：§4 命令级记录；真机矩阵回填处。
- 刻意不做：不做多任务并行下载；不做任意 URL 下载通道；不做模型详情页外链浏览（树信息已够选型）；判定逻辑不下放 JS；暂不做 HF 官方站回退（镜像不可达时页面报错即可，属网络层问题）。
PLACEHOLDER-MARKER

---

## 20261104 增补 · 转正为原生内置页（v1.1.19）

### 增补原话（逐字）
> 「行，那就把这个内置到APP里」

### 语义与改动
「内置到 APP 里」= 从插件形态（v1.1.18 的 WebView settingsCard）转正为 App 本体原生功能：设置页入口直达原生 Compose 页 `ui/ModelHubScreen.kt`（与宿主同包、同 WarmTones 主题、自适应深浅色）。assets 插件包（manifest/hub.html/icon）删除；桥六口（host.deviceInfo/hub.judge/hub.download/hub.downloadStatus/hub.cancel/hub.dismiss）与 SDK 的 gguf.hub/gguf.host.deviceInfo 包装回撤；`bootstrapBuiltins` 增加一次性清理（删除设备上残留的 com.gguf.modelhub 已装副本，只删这一个已知 id，不动其他插件）。宿主引擎原样复用（ModelHub.kt），HubApi 新增原生 fetchBody/popular/search/tree，不再绕 net.fetchJson。原生页行为与 §2/§3 完全一致（热门降序+隐藏、搜索原样+打标、判定懒加载顺序请求、下载进度卡、展开选量化）。

### Why
用户在看过插件形态后明确要本体化——插件壳在此场景只剩管理页开关与 WebView 间接层两个副作用：页面随 WebView 生命周期重建（列表状态易丢）、多一层桥编排。原生页换来：状态由 Kotlin 持有、进度卡与服务卡同一设计语言、不再有「插件被停用导致入口消失」的分叉。代价是 UI 迭代要重编译——对桌面端 App 这是可接受的交换。别把判定/下载逻辑挪回任何 JS 侧；别恢复六个桥口（已无调用方）。

### 验证（增补）
JVM 单测 61 项全绿；assembleDebug RC=0；`debug/GGUF-Server-debug-1.1.19.apk`（136M，versionCode 30）归档：APK 内 modelhub 插件资产 0 项（已移除）、ModelHub 符号在 classes3.dex（232 处）/classes9.dex（50 处）——内容校验方法学见技术发现006（shell 管道假零教训）。

### 增补版履历
- v1.1.19（2026-11-04）：转正原生内置页（本增补节）。

---

## 20261104 增补2 · 多尺寸自适应（v1.1.20）

### 增补原话（逐字）
> 「按照最新的UI适配原则，让其适应宽屏、竖屏、窄屏等等各种可能的屏幕状态。比如最上边的那个工具栏，如果在宽屏中，它应该跑到左边去，变成纵栏。」

### 语义与改动
断点纯函数 `hubLayout(宽度dp)`：<600dp = compact（横向顶栏，原布局，手机竖屏 375/390 与分界带 494）；≥600dp = medium（**左侧纵栏**：返回钮 + 「模型市场」竖排一字一行 + tab 纵列，即原话「宽屏中顶栏跑到左边去变成纵栏」；平板/手机横屏/桌面模式）。内容主列两形态共用（设备行/进度卡/tab或纵列态搜索框/状态/列表），超宽时 720dp 封顶防拉伸（密度纪律）；行内「推荐量化」chip 加弹性截断（`weight(1f, fill=false)`），窄屏下下载按钮永不被挤走。单页滚动纪律保持：外壳固定，列表唯一滚动区。旋转由 Activity `configChanges` 自理（MainActivity 既有配置），remember 状态跨转屏存活仅重布局。HubApi/HubJudge/下载引擎零改动。

### Why
1. **断点抽纯函数**——阈值是要钉死的语义（600dp = Material 宽度尺寸类分界），进单测防"顺手调数字"；HubLayoutTest 3 项（375/390/494→compact；599.9→compact、600→medium；768/840/1440→medium）。
2. **宽屏用宽度断点而非高度**——横屏手机（如 844×390）按 Material 宽度类属 medium，得纵栏；这与 Jetpack WindowManager 的 WindowWidthSizeClass 口径一致，别自创"高度低于 X 就回到顶栏"的特例。
3. **竖排标题一字一行**——纵栏形态的题眼；横排标题塞 148dp 纵栏会换行成两段，视觉重心散。
4. **720dp 封顶**——桌面模式 1440dp 下列表行无限拉伸会出现"一行读到底"的负面密度；封顶后右侧留白由居中……本版取左对齐（Material list-detail 惯例，右缘留白可接受），刻意不做强制居中（避免与纵向滚动条位置打架）。

### 验证（增补）
JVM 单测 64 项全绿（新增 HubLayoutTest 3 项）；assembleDebug RC=0；`debug/GGUF-Server-debug-1.1.20.apk`（137M，versionCode 31）归档，python zipfile 验证 hubLayout/HubRail 符号在 classes3.dex（×17）、modelhub 插件资产残留 0。真机多尺寸矩阵目验（375/494/600/768/1440 + 横屏）待回填。

### 增补版履历
- v1.1.20（2026-11-04）：多尺寸自适应（本增补节）。

### 20261104 增补2·验证回填：模拟器六档矩阵实拍（用户令「用模拟器自己截图验证」）

mini 装 emulator + android-34 google_apis arm64-v8a（dl.google.com 国内 CDN 实测 12MB/s），AVD headless 启动 20 秒，装 1.1.20 debug 包实跑。六档 = `wm size/density` 运行时切换（mdpi 使像素=dp）。**每档两查**：目验真截图 + uiautomator dump 机审。

| 档位 | 机审（竖排"模"字/热门tab/返回钮） | 目验 |
|---|---|---|
| 375×812 竖屏手机 | 0/1/1 → compact ✓ | 横顶栏、双tab全宽、行内徽标+推荐chip弹性截断+下载钮完整 |
| 494×1013 分界带 | 0/1/1 → compact ✓ | 同上，无溢出 |
| 600×1000 medium 起点 | 1/1/1 → 纵栏 ✓ | 左纵栏（返回+竖排标题+tab纵列）+右内容列 |
| 768×1024 平板竖 | 1/1/1 → 纵栏 ✓ | 同上 |
| 1440×900 桌面模式 | 1/1/1 → 纵栏 ✓ | **内容列 720dp 封顶生效**，右侧留白，行不再拉满 |
| 844×390 手机横屏 | 1/1/1 → 纵栏 ✓ | 纵栏在 390 高下排布完整，列表滚动正常 |

功能面（真网络 hf-mirror）：列表 30 仓真加载、判定徽标（可运行/勉强/带不动）真实渲染、隐藏逻辑实拍（2.4GB 模拟器设备：热门页隐藏 10~17 个带不动仓库并显示脚注计数）。

**模拟器验证抓出的两个真 bug（当场修）**：
1. `HubApi.treeUrl/downloadUrl` 把 repoId 连 "/" 一起编码成 %2F → hf-mirror 路径 404 → 全部行「判定失败」。**且 HubApiTest 把这个错误行为钉死了**（测试与实现一起错）——两处同修，断言改为 "/" 原样保留。教训：测试只能钉住实现，钉不住真值；关键路径要有一次真实端到端验证。
2. 内容列 `weight(1f)` 默认强制填满右栏，`widthIn(max=720)` 拦不住 → 1440 下封顶失效。改 `weight(1f, fill = false)`。

截图入库：`docs/screenshots/模型市场-{档位}-{形态}-{场景}.png` ×6。

### 20261104 增补2·纵栏改版：照 FasFox 面板侧栏语态重写（用户令「左侧边栏丑爆了，看看fasfox怎么做的」）

**参照物**：CodingPlanProxy/frontend `UserLayout.vue` 的 `.sidebar`（FasFox 面板真实实现）——横排品牌行（logo+品牌字同行）、横排导航条目（图标 20px + 文字一行一条，gap 10/padding 9×12/圆角 8）、选中态=浅琥珀底横条+品牌色加粗字（`.nav-item.active`）、底部 foot 分隔线+信息块、右缘 1px 分隔线、宽 210px。

**改动**（HubRail 重写 + 两个系统栏修复）：
1. 竖排一字标题、药丸 chip 全废弃；品牌行=返回钮+「模型市场」横排（accent 加粗）；导航条目=Whatshot/Search 图标+文字横排（`HubNavItem`，选中=accentSoft 底+accent 加粗，与 WarmTones 天然对应 FasFox 的 #fef3e2/#B45309）；foot=分隔线+设备信息块（内存/目录可用从内容行挪入）+「GGUF 镜像源」小字；纵栏宽 200dp、右缘 1dp 分隔线。
2. 根屏补 `statusBarsPadding().navigationBarsPadding()`——此前品牌行被状态栏时钟压住、foot 被手势条裁字，实拍抓出。
3. compact 布局不受影响（375 目验顶栏时钟不再压字）。

**验证**：模拟器重装重截，768/600/375/1440/844/494 六档目验全过（新纵栏在 768 实拍确认，三档入库刷新）；dex 验证 HubNavItem=classes3×6。**流程坑（又立此存照）**：改完只跑 test 不跑 assemble 就 install 归档文件 → 装的是旧包（assemble 更新的是 app-debug.apk，归档要显式 cp）——两次假截图均由此来，今后「打包-归档-安装」三步必须一条链。
