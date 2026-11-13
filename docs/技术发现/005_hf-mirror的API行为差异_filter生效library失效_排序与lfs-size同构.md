# 发现005 · hf-mirror的API行为差异_filter生效library失效_排序与lfs-size同构

**一句话结论**：hf-mirror 对 HF 官方 API 的代理并不完全同构——`library=gguf` 参数被忽略（会返回非 GGUF 模型），必须用 `filter=gguf`；`sort=downloads&direction=-1`、`search=`、`limit=`、`/tree/main`（含 `lfs.size` 字段）与 `/resolve/main/` 文件通道全部同构可用。

**日期**：2026-11-04

**环境**：hf-mirror.com（HF 国内镜像），mini 与 MacBook 实测；用途为 GGUF-Server「模型市场」插件的数据源选型。

**症状**：`GET /api/models?library=gguf&sort=downloads&direction=-1&limit=5` 返回的第一名是 `sentence-transformers/all-MiniLM-L6-v2`（全站下载王，library 是 sentence-transformers/onnx）——library 过滤根本没生效；换成 `filter=gguf` 后返回清一色 GGUF 仓库。

**根因**：镜像层对 query 参数做了白名单式转发（或上游 HF 的 `library` 参数语法变化），未识别的参数被静默丢弃——**静默失效比报错更危险**，调用方拿到的是"看似成功"的错误数据。

**解法（可复现）**：
```bash
# 热门 GGUF：filter 而非 library
curl "https://hf-mirror.com/api/models?filter=gguf&sort=downloads&direction=-1&limit=5"
# 关键字搜索（可与 sort 组合）
curl "https://hf-mirror.com/api/models?search=minicpm5&limit=10"
# 单仓文件清单（lfs.size 才是大文件真实字节数，size 字段同值但以 lfs.size 为准做回退链）
curl "https://hf-mirror.com/api/models/<owner>/<repo>/tree/main"
# 文件下载（直出内容，实测 11MB/s 级）
curl -L "https://hf-mirror.com/<owner>/<repo>/resolve/main/<file>"
```

**验证**：GGUF-Server 设计文档04 的 HubApi 用 `filter=gguf` 取热门列表，实测返回 unsloth/Qwen3.8 等纯 GGUF 仓库按下载量降序（8.25M/7.9M/7.1M…）；Sharp-MiniCPM5-2B-GGUF 经 `/resolve/main/` 下载 1.6GB 字节精确（CRC 级验证见其技术发现链）。

**边界与坑**：
- 搜索**不要**带 `sort=`：HF 搜索默认按相关性排，设计要求「搜出什么就是什么」；
- `/api/models` 单响应体量在 KB 级（默认不含文件清单），配 `limit=30` 远低于镜像 2MB 级响应上限；
- 镜像站参数行为可能随其升级变化，选型时先用 curl 验一遍再固化（本次即先验后码）。

**关联**：GGUF-Server 设计文档04（模型市场）、发现003（同站文件通道的下载纪律）。
