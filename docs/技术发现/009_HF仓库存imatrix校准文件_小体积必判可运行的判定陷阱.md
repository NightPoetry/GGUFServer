# 发现009 · HF 仓库存 imatrix 校准文件_小体积必判可运行的判定陷阱

**一句话结论**：HF/HF-mirror 的 GGUF 仓库常附带 `*.imatrix-unsloth.gguf`（或含 imatrix 字样）文件——这是 llama.cpp `llama-imatrix` 生成的**量化校准重要性矩阵统计**（几十 KB~几十 MB），不是模型本体、不可用 llama.cpp 推理；任何「按文件大小 vs RAM 判定可运行」的逻辑都会把它判成「可运行」（13MB ≤ 0.75×RAM）并在轻→重排序的 best 挑选里抢走推荐位（实案：27B 仓库，本体 Q2_K 应判「勉强」却被 13MB imatrix 的「可运行」顶掉）。
**日期**：2026-11-16
**环境**：GGUF-Server 模型市场（hf-mirror）；unsloth 等发布的量化仓库标配附带
**症状**：27B 级仓库列表里出现 13MB 文件判定「可运行」并成为推荐下载；本体（10GB 级 Q2_K）明明应判「勉强」。
**根因**：判定以文件大小为唯一维度；imatrix 文件名常无有效量化 tag（parseQuant=null）或其名内含 "IMATRIX" 字样；`IMATRIX-unsloth.gguf` 的尾段解析不匹配量化正则，但大小判定先行通过。
**解法（可复现）**：
```kotlin
// ① 目录树解析层直接排除（与 mmproj 同款待遇）
if (path.contains("imatrix", ignoreCase = true)) continue
// ② best 挑选层双保险：推荐位只认有效量化命名的文件
val runnablePool = verdicts.filter { it.quant != null }
val best = runnablePool.firstOrNull { it.verdict == "ok" }
    ?: runnablePool.firstOrNull { it.verdict == "tight" }
```
**验证**：HubJudgeTest 新增用例（imatrix 13MB verdict=ok 但 bestName=真量化 tight 文件）通过；单测 20 项全绿。
**边界与坑**：
- imatrix 对要「自己做量化」的用户有价值（llama-quantize --imatrix 使用）；如果 UI 提供「高级：含校准文件」开关可兼顾，默认隐藏。
- mmproj（多模态投影）同理是配件文件，parseTree 早已排除；两者一起构成「非本体 gguf」黑名单，后续再发现新变体按同款处理。
- imatrix 质量取决于校准语料与使用场景的匹配（长上下文需重校准）；对直接使用者无影响。
**关联**：unsloth issue #5500；ggml-org/llama.cpp discussion #5263；设计文档07 §2。
