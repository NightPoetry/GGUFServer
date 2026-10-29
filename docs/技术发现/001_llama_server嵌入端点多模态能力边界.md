# 发现001 · llama_server嵌入端点多模态能力边界

**一句话结论**：llama.cpp 的 `/v1/embeddings` **图片向量底层已支持**（带 `--mmproj` 启动即可，输入用 llama.cpp 私有的 `{prompt_string, multimodal_data:[base64]}` 形状，非 OpenAI image_url 格式）；**语音向量框架有口但模型缺货**（libmtmd 支持音频输入，但当前可转换 mmproj 的模型清单全是视觉系）；且其多模态嵌入是「LLM+mmproj 联合池化」，**不是 CLIP 式图文对齐的独立向量空间**。

**日期**：2026-10-30

**环境**：GGUF-Server（Android）向量模型服务设计调研；llama.cpp 检出为本机 `/Users/nightpoetry/AGI/04-KVMem工程/kvmem-llama.cpp/llama.cpp/`（2026-10 前后主线版本，带 /v1/responses 等新路由）；源码证据为 server 源码静态查证，未实弹跑图。

**症状**：给 App 加向量模型服务时需回答「支不支持图片/语音多模态向量」，官方 README 只有 `/embedding` 节一句「also supports multimodal embeddings」，`/v1/embeddings` 的多模态输入形状无处可查。

**根因**：能力分散在三处、文档未汇总——
1. `tools/server/server-context.cpp` 的 `handle_embeddings_impl` 把 `ctx_server.mctx`（多模态上下文）传进 `tokenize_input_prompts`——嵌入与多模态并不互斥；
2. `server-common.cpp` 的 `tokenize_input_subprompt` 只认 `{"prompt_string": "...", "multimodal_data": ["<base64>"]}` 对象形状；mctx 为 null 时传图报 "Multimodal data provided, but model does not support multimodal requests"；
3. mmproj 加载（`has_mmproj` → mtmd context init）不受 `params.embedding` 门控——`--embeddings` 与 `--mmproj` 可以同时用；embedding 模式只锁输出上限（n_tokens=1）与 pooling。

**解法（可复现）**：
```bash
# 启动：嵌入模式 + 视觉投影一起带
llama-server -m <多模态模型>.gguf --embeddings --mmproj <mmproj>.gguf --host 0.0.0.0 --port 8101

# 图片向量请求（llama.cpp 私有形状，不是 OpenAI chat 的 image_url！）
curl http://<host>:8101/v1/embeddings -H "Content-Type: application/json" \
  -d '{"input": {"prompt_string": "描述", "multimodal_data": ["<图片base64>"]}}'
# 非兼容路径：用 "content" 字段替代 "input" 同形状也可（自动降为非 OAI 响应）
```

**验证**：源码静态查证（handle_embeddings_impl / tokenize_input_subprompt / mmproj 加载路径三处证据链闭合）；实弹图片向量未跑（端上 CPU 视觉编码慢，需求未确认前不做）。

**边界与坑**：
- 输入形状是 llama.cpp 私有的 `{prompt_string, multimodal_data}`，OpenAI SDK 的 image_url content parts 在嵌入端点**不走**（那是 chat 路径的 parse_message_content）；
- 多模态嵌入=LLM 联合池化，图与文进同一池化空间但**非 CLIP 对齐训练**，文搜图效果取决于模型，别当标准 CLIP 向量用；
- 语音：libmtmd（PR #12849）框架层支持 audio 输入，但 mtmd README 的可转换模型清单（Gemma 3 / Qwen2.5-VL / MiniCPM-V 4.6 / InternVL / SmolVLM…）**没有一个语音嵌入模型**——做了口子也没模型可跑，等上游；
- 手机 CPU 视觉编码极慢（本项目实测 256px 识别一张图约 40s，嵌入同量级）：少量图片入库可行，批量灌库不现实。

**关联**：GGUF-Server 设计文档02（向量模型服务，当前纯文本实现与 fail-open 兜底）；本项目嵌入实例未带 --mmproj，补图片向量=向量模型组加 mmproj 家族配对选择器（照聊天服务同款逻辑）+ buildCommand 加 `--mmproj`，改动很小。
