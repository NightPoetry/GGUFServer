# 设计文档 2：向量模型服务_第二llama-server实例提供v1_embeddings（v1.0.0）

> 20261029 · 立档。一句话：**设置页选一个专用嵌入 GGUF，启动服务时随主模型一并拉起第二个 llama-server（固定 8101、绑 0.0.0.0、`--embeddings` 模式），对局域网提供 OpenAI 兼容 /v1/embeddings，UI 全套沿用现有设计语言，拉起 fail-open 主服务零风险**。

## §0 设计原话（逐字，20261029）

> 「搞定之后开始正事，就是加入向量模型的服务，UI什么的维持与现在的一致性」

## §1 语义提炼

1. **「向量模型的服务」= 对局域网提供 OpenAI 兼容的 `/v1/embeddings` 端点**，供 RAG / 语义检索等外部用途——不是给 App 内部用的检索（那已有 BM25-lite）。这意味着必须绑 `0.0.0.0` 对局域网开放，与「记忆模型」（8100、127.0.0.1、纯内部管道）定位相反。
2. **复用双实例先例**：App 已有第二个 llama-server 实例的成熟先例（`MemoryModelServer`，重型记忆插件用，固定 8100）。嵌入服务照同款模式长成 `EmbeddingServer`：独立进程、固定端口 **8101**、对象单例 + synchronized 生命周期 + /health 轮询就绪。
3. **专用嵌入模型 + `--embeddings` 模式**：llama-server 文档原文「`--embedding, --embeddings`：restrict to only support embedding use case; use only with dedicated embedding models」——所以 UI 提示只能配专用嵌入模型（Qwen3-Embedding / EmbeddingGemma 等）；`--pooling` 不传，按 GGUF 元数据自动。
4. **生命周期跟随主服务**：`LlamaServerService.startServer` 后台线程拉起、`cleanup` 一并卸载、原地重启（换配置）与 GPU 回退重启时 `ensure` 幂等复用（同模型已运行→原样返回）。**拉起 fail-open**：嵌入模型坏了只影响嵌入卡状态+日志，主服务不受任何影响。
5. **UI 维持现有一致性**（用户原话「UI什么的维持与现在的一致性」）：新组「向量模型（嵌入服务）」完全复用既有组件——选择器与 mmproj 选择器同构（`SelectRow`「不启用」+ 模型列表 + 删除角标）、运行状态沿用「服务」组的语言（状态圆点 + `CopyableField` + 复制 curl）+ 「自检」`HeaderAction`，提示文案用现有 12sp faint 行距 17sp 的样式。
6. **API Key 同值透传**：与主服务同一 `--api-key`，两个端点的鉴权行为一致。

## §2 算法 / 数据 / 接口

### 接口（对外）

```
POST http://<手机IP>:8101/v1/embeddings        # OpenAI 兼容，llama-server 原生实现
     {"model":"<嵌入调用名>","input":"文本"}     # Authorization: Bearer <API Key>（若设置）
GET  http://<手机IP>:8101/v1/models            # 嵌入调用名 = 文件名去 .gguf 后缀
```

### 端口拓扑（三实例并存互不冲突）

| 实例 | 端口 | 绑定 | 用途 |
| --- | --- | --- | --- |
| Gateway（插件网关） | 用户设（默认 8080） | 0.0.0.0 | chat/completions，插件中间件唯一落点（§6.8 拓扑不变） |
| 主 llama-server | port+1 | 127.0.0.1 | 被网关转发 |
| 记忆模型 MemoryModelServer | 8100 | 127.0.0.1 | 重型记忆插件内部管道 |
| **嵌入服务 EmbeddingServer（本档）** | **8101** | **0.0.0.0** | **对局域网提供 /v1/embeddings** |

### 命令行（`EmbeddingServer.buildCommand` 纯函数，JVM 单测钉死形态）

```
libllamaserver.so -m <嵌入GGUF绝对路径> --host 0.0.0.0 --port 8101 -c 2048 -t <线程数>
    --embeddings --alias <文件名去后缀> [--api-key <与主服务同值>]
环境变量与主服务同款安全阀：GGML_VK_DISABLE_BDA=1、GGML_VK_VISIBLE_DEVICES=""
```

### 数据流

- 设置持久化：`SettingsStore.embeddingModelPath`（SharedPreferences 键 `embModelPath`，空=不启用）→ `ServerConfig.embeddingModelPath` → intent extra `embeddingModelPath` → service 后台 `EmbeddingServer.ensure(...)`。
- 选择即持久化（`selectEmbedding` 立即写 settings），启动服务时生效；换模型 = 重启服务。
- 状态：`EmbeddingServer.status: StateFlow<Status>`（Phase: IDLE/STARTING/RUNNING/ERROR + model/alias/error），设置页 `collectAsStateWithLifecycle` 直订。
- 文件找回：`refresh()` 按持久化路径在当前目录模型列表里找回 `selectedEmbedding`，文件被删自动退回「不启用」。

## §3 关键决策 Why

- **Why 独立第二实例而不是主实例换模型**：llama-server 一进程一模型；用户要的是聊天与检索**同时**可用。且 `--embeddings` 会 restrict 掉 chat 用途，同一实例两者不可兼得。
- **Why 照 MemoryModelServer 模式**：那是本项目验证过的双实例生命周期模板（进程/锁/health 轮询/IO 排空），照抄降低风险；差异只在绑定面与端口（别改这里：8100 是记忆模型专属，嵌入服务用 8101，两者可并存）。
- **Why 0.0.0.0 直绑不走 Gateway**：嵌入请求没有插件中间件需求，Gateway 是 chat 管线的落点（§6.8）；直绑少一层转发与故障面，与「嵌入是给外部设备的独立服务」语义一致。
- **Why ctx 固定 2048**：RAG 分块文本远用不满大上下文；嵌入模型小，省内存优先。线程数跟随主服务设置（同一台机器，同一次设置动作）。
- **Why 拉起 fail-open**：嵌入是增值能力，主服务的可用性永远第一——坏模型只能让自己 ERROR，绝不能拖垮启动路径。
- **Why 自检按钮打 127.0.0.1 而不是局域网 IP**：验证的是「服务本身能产出向量」，绕开 WiFi/防火墙变量；外部连通性由服务组的 IP 提示覆盖（与主服务同一套口径）。
- **Why 别区分「嵌入模型/对话模型」两个列表**：GGUF 无可靠元数据可从文件名判型，强过滤（如名字含 Embedding）会误伤（MiniCPM-V 系列命名五花八门）；两个列表都全量展示+文案说明「选专用嵌入模型」，配错由 llama-server 拒绝并进 ERROR 态，fail-open 兜底。

## §4 验证

- [x] **JVM 单测 42 项全绿**（39+3 新增 `EmbeddingServerTest`）：`--embeddings` 必须在（少了 /v1/embeddings 404）、绑 0.0.0.0、端口 8101、`-c 2048`/`-t` 透传、别名透传、API Key 只在有值时透传、对话模型专属参数（--mmproj/--reasoning/--temp/--top-p/--jinja/-ngl）一律不出现。mini 上 `mini-build.sh test` → `BUILD SUCCESSFUL`，42 tests / 0 failures（20261029 实测）。
- [x] **插件离线自检**：`check-plugins.js` ✓ 全部通过（20261029 实测，确认宿主改动零副作用）。
- [x] **debug 包归档**：`mini-build.sh debug` → `debug/GGUF-Server-debug-1.1.16.apk`（142MB，20261029）。
- [ ] **真机端到端**：待回填——上传 Qwen3-Embedding-0.6B → 选入向量模型组 → 启动服务 → 状态变绿 → 电脑 `curl http://<手机IP>:8101/v1/embeddings` 返回 1024 维 → 自检按钮日志「返回 1024 维向量」→ 停止服务两实例齐停。
- [ ] **配错模型 fail-open 实弹**：待回填——把对话模型配进向量模型组，预期主服务正常运行、嵌入组显示 ERROR+日志可导出。

## §5 文件索引

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| server/EmbeddingServer.kt | 新增 | 嵌入服务对象单例：buildCommand 纯函数 / ensure 幂等拉起 / stop / StateFlow 状态 / selfTest 自检 |
| server/LlamaServerService.kt | 修改 | startServer 末尾后台线程 fail-open 拉起；cleanup 增 EmbeddingServer.stop()；EXTRA_EMBEDDING 透传 |
| data/SettingsStore.kt | 修改 | embeddingModelPath 持久化（键 embModelPath） |
| model/ServerConfig.kt | 修改 | + embeddingModelPath 字段（默认空） |
| MainViewModel.kt | 修改 | selectedEmbedding 状态/selectEmbedding 即时持久化/refresh 找回/embeddingStatus 订阅/testEmbedding 自检/buildConfig 透传 |
| ui/SettingsScreen.kt | 修改 | 新增 EmbeddingGroup（模型组与运行参数组之间），全复用现有组件 |
| app/src/test/.../server/EmbeddingServerTest.kt | 新增 | 3 项命令行形态钉死单测 |
| android/app/build.gradle.kts | 修改 | versionName 1.1.15→1.1.16 / versionCode 26→27 / versionNote |
| README.md | 修改 | 功能全览新增「向量模型服务（/v1/embeddings）」节 |

## §6 版本履历

- v1.0.0（20261029）：立档——向量模型服务全链路（配置/进程/UI/单测）落地，v1.1.16 debug 包归档，真机端到端待回填。

## §7 重建清单（照本篇重塑所需的最小信息集）

1. **语义**：嵌入服务=随主服务拉起的第二个 llama-server，对局域网提供 /v1/embeddings；选专用嵌入模型；坏了不拖累主服务。
2. **数据**：settings 键 `embModelPath`（绝对路径，空=不启用）；状态 Status(Phase, model, alias, error) 经 StateFlow 出。
3. **规则**：端口固定 8101；绑 0.0.0.0；`--embeddings` 必带；pooling 不传（GGUF 自动）；ctx 2048；线程随主服务；API Key 同值透传；别名=文件名去后缀；ensure 同模型幂等；cleanup 必停。
4. **接口**：§2 三条端点 + 四实例端口拓扑表。
5. **界面**：设置页「向量模型（嵌入服务）」组=说明行 + 「不启用」+模型列表（可删）+ 运行态（状态点/地址/调用名/复制 curl/自检）或错误态；样式全用现有组件（Group/SelectRow/CopyableField/WarmButton/HeaderAction）。
6. **验证**：§4——单测 42 全绿已实测，真机两条待回填。
7. **刻意不做**：①不区分嵌入/对话模型两个列表（GGUF 判型不可靠，靠 fail-open 兜底）②嵌入实例不走 Gateway/插件管线（无中间件需求）③嵌入 ctx 不开放设置（省内存，2048 够 RAG 分块）④不做嵌入服务独立启停（生命周期跟随主服务，独立前台服务是另一档复杂度）⑤不把 /v1/embeddings 接进 App 内记忆检索（那已有 BM25-lite 且不占内存）。
