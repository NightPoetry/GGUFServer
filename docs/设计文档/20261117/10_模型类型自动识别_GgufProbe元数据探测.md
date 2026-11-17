# 设计文档10 · 模型类型自动识别（向量/语言模型，GgufProbe 元数据探测）

## §0 设计原话（逐字）
> 「它应该能自动判断出什么模型是向量模型，什么模型是语言模型吧？以及多模态模型的支持问题。就像是。 LM Studio 里面那样，对吧？就是每一种模型是什么功能，可以自己勾选。当然它最好有，它最好是预勾选，就通过一些手段判断，比如说是否有这个视觉投影啊什么的进行预勾选，就说更智能一些，因为很多东西都是能够通过机械的方式判断出来的，我们尽可能的降低用户的负担。比如说如果我下了一个非向量模型，结果它还在向量模型的启用列表里，然后启动的时候就会失败。对吧？这就是给用户造成负担，你好好设计设计。」（2026-11-17）

## §1 语义提炼
向量模型（嵌入服务）选择列表此前全量列出所有 .gguf，语言模型混在其中——用户误选后嵌入实例启动失败（负结果：给用户造成负担）。设计：App 内自动读 GGUF 元数据判定模型类型（向量/语言），向量列表默认只列向量模型，其余隐藏防误选（可展开手动选择）；主服务模型列表为向量模型打徽标防对话误用；多模态以 mmproj 伴随文件为信号（同目录探测）。

## §2 算法/数据/接口
1. **GgufProbe（data/GgufProbe.kt，纯 Kotlin 可 JVM 单测）**：`probe(File)` 只读 GGUF 头部元数据区（不碰张量区）——magic 校验、version≥2、tensor_count 跳过、kv_count 遍历；KV 按 v2/v3 编码（u64 长度字符串/u32 类型/数组跳转）遍历，提取 `general.architecture` 与 `<arch>.pooling_type`（u32 或 string 形态都接）。kvCount 上限 1 万防御；单值/数组跳转用 seek 不读字节。
2. **类型判定 `kindOf`**：`pooling_type != null && != 0`（NONE=0）→ EMBEDDING；`architecture` 含 BERT 系子串（nomic-bert/jina-bert/bert/gte/e5/xlm-roberta）→ EMBEDDING；解析失败 → UNKNOWN；其余 → LLM。依据 LM Studio 同口径（lmstudio-bug-tracker#2177）。
3. **MainViewModel**：`modelKinds: Map<path, Kind>`（refresh 后 IO 协程逐个探测）；`showAllInEmbeddingList` 开关；`kindOf(model)` 兜底 UNKNOWN。
4. **EmbeddingGroup UI**：默认只列 EMBEDDING 行；顶部识别结果说明行；LLM 行隐藏（「显示全部 N 个非向量模型」展开，行带「语言模型」警示徽标）；UNKNOWN（探测失败）与 EMBEDDING 同列可选。
5. **ModelsGroup UI（主服务）**：EMBEDDING 模型行标「向量」徽标+副文案「向量模型不适合对话，请在向量模型（嵌入服务）组启用」——用户仍可强制选用（不阻断，提示优先）。
6. **判定落点注意**：识别结果不落盘（每次 refresh 重扫，头部解析毫秒级）；不阻断任何启动路径（预判=提示与过滤，不做硬禁用——最终真相在 llama-server 运行时）。

## §3 关键决策 Why
1. 读元数据而非试启动：试启动失败循环重、耗时、给用户负担；GGUF 头解析毫秒级且确定。
2. pooling_type 优先于架构名：Qwen3-Embedding 用 qwen2 架构（语言系架构名），判据必须是「有非 NONE pooling」而非架构白名单；架构名单只作 BERT 系补充。
3. UNKNOWN 不过滤：探测失败时宁可显示让用户手动判断，不做静默吞没。

## §4 验证
- GgufProbeTest 4 项：bert+pooling=EMBEDDING（字节级构造 GGUF 头）；qwen2+pooling=EMBEDDING（架构名不参与判定的反例）；qwen2 无 pooling=LLM；非 GGUF=UNKNOWN。全绿（JVM）。
- assembleDebug 通过；24 项 Hub 单测全绿。

## §5 文件索引
- data/GgufProbe.kt（新增）、MainViewModel.kt（modelKinds/showAllInEmbeddingList/kindOf）、ui/SettingsScreen.kt（EmbeddingGroup 过滤+徽标、ModelsGroup 徽标）、test/data/GgufProbeTest.kt。

## §6 版本履历
- 20261117 v1.1.22 增补：初版。
