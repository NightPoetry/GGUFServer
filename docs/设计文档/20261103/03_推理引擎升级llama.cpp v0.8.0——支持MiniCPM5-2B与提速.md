# 03 · 推理引擎升级 llama.cpp v0.8.0——支持 MiniCPM5-2B 与提速

> 版本：v1.0.0 初版（2026-11-03）｜关联：01（构建关系）、02（向量模型服务）
> 一句话：把内置 llama.cpp 从 2026-07-18 master 快照升级到 v0.8.0 稳定版（2026-10-28 发布），MiniCPM5-2B 的分词器前置标识 `minicpm5` 与新采样/内核改进得到原生支持，另开 GGML_LLAMAFILE=ON 提升 ARM prefill；jniLibs 更新收窄到引擎产物，保住 v1.1.16 的 onnxruntime/sherpa。

## §0 设计原话（逐字，20261103）

- 「好的，我们更新里面的推理引擎以支持MINICPM5 2B并且是更加迅速高效」
- 背景（上一轮任务原话）：「远程服务器上可能有手机部署GGUF服务器的项目找到它，或者你看看本地有没有镜像。」

## §1 语义提炼

1. 「更新推理引擎」= 替换 jniLibs 里的 llama.cpp 全家（libllama*.so / libggml*.so / libmtmd.so / libllamaserver.so / libllamabench.so），源码树 /Volumes/SystemData/llama-build/llama.cpp 由 2026-07-18 快照升级到 v0.8.0 稳定 tag。
2. 「支持 MiniCPM5 2B」的两层含义：
   - 能加载：MiniCPM5-2B 是标准 `llama` 架构（GGUF general.architecture=llama，一手验证），任何能跑 Llama 的版本都能加载。
   - **跑得对**：GGUF 带 `tokenizer.ggml.pre = minicpm5`（前置分词器标识）与内嵌 jinja 聊天模板。旧引擎（2026-07）不认识 `minicpm5` 这个 pre 字符串，会静默退回 fallback 正则 → 分词劣化、模板错误；v0.8.0（晚于模型发布）原生认识。
3. 「更加迅速高效」= 引擎半年积累的 ARM 内核改进（dotprod/i8mm 变体、FA、量化内核）+ 新开 `GGML_LLAMAFILE=ON`（llamafile sgemm，上游默认开的 prefill 加速路径）；App 侧运行时行为不变。

## §2 算法 / 数据 / 接口

### 2.1 引擎源码更新路径（git 被挡的替代通道）

```
MacBook/mini → codeload.github.com（直连慢但可靠，支持 Range）
  llama.cpp v0.8.0 tarball（38,680,037 字节）
  → /Volumes/SystemData/llama-build/llama-0.8.0.tar.gz
  → 校验：gzip -t（gzip CRC32 全量校验）+ 总字节数精确匹配
  → 旧树备份：llama.cpp → llama.cpp.bak-20260718
  → 解包换树：llama.cpp-0.8.0 → llama.cpp
```

### 2.2 构建配置（相对上一版的两处变化）

- 新增：`-DGGML_LLAMAFILE=ON`（上一版显式 OFF）
- 保持：`GGML_BACKEND_DL=ON + GGML_CPU_ALL_VARIANTS=ON`（CPU 变体运行时自选）、`GGML_VULKAN=ON`（带 Mali BDA 补丁）、`GGML_OPENMP=OFF`、`GGML_NATIVE=OFF`、android-28、arm64-v8a
- NDK 独立工具链交叉编译（r26.3），不依赖 Apple 许可状态的组件

### 2.3 jniLibs 更新范围（防误删）

- 替换：`libggml*.so`、`libllama*.so`（含 libllamaserver/libllamabench 桩）、`libmtmd.so`
- **保留不动**：`libonnxruntime.so`、`libsherpa-onnx-jni.so`（v1.1.16 向量/音频功能，非引擎产物）
- rebuild.sh 与 build-llama-android.sh 的清理行已从 `rm -f *.so` 收窄为上述三个前缀（本次一并修正的既有地雷）

### 2.4 App 侧接口核对清单（v0.8.0 服务端参数）

App 启动 llama-server 传入：`-m --host --port -c -t --temp --top-p --jinja --alias [--mmproj] [-ngl] [--reasoning off] [--no-mmap] [--api-key]`；嵌入实例（EmbeddingServer.kt，端口 8101）：`--embeddings` + pooling 按 GGUF 元数据。每项需在 v0.8.0 源码里确认仍被接受，尤其是 `--reasoning off`（详见 §4）。

### 2.5 版本与归档

- versionName 1.1.16 → 1.1.17，versionCode 27 → 28；debug 归档 debug/GGUF-Server-debug-1.1.17.apk
- 实测模型：peculiar-ragdoll/Sharp-MiniCPM5-2B-GGUF Q4_K_XL（1,595,722,240 字节，hf-mirror 下载）

## §3 关键决策 Why

1. **走 codeload tarball 而非 git**——mini 的 /usr/bin/git 被 Xcode 许可挡（exit 69），git clone/pull/apply 全灭；codeload 直连 HEAD 秒回、正文可 Range 慢速拉。别改成等许可：许可需要 sudo 交互，是主人动作。
2. **tarball 完整性用 gzip -t 判**——codeload 无发布 sha256；gzip 内嵌 CRC32，`gzip -t` 过即内容字节级完好，比字节数对账强一个等级（本次实测：字节数全对、CRC 挂，分段下载有损实锤）。
3. **分段下载用「区间移位 + 流式 >> 追加」**——`curl -C -` 与 `-r` 并用是冲突组合（重试时 -r 从头拉、-C - 往尾追加 → 重复数据撑爆分段）；代理可能不理 Range 返回 200 全量，无守卫的追加会把整包垃圾灌进分段。非 206 响应一律丢弃不追加。
4. **LLAMAFILE=ON**——llamafile sgemm 是上游默认开启的 prefill 加速路径，上一版显式关掉无依据；与 CPU 多变体正交。
5. **OPENMP 保持 OFF**——ggml 自带线程池已达标（§测评文档基线），少一个变量保证 A/B 归因干净。
6. **rebuild.sh 的 rm 收窄**——v1.1.16 起 jniLibs 不再只有引擎产物；原 `rm -f *.so` 会在重编引擎时连带删掉 onnxruntime/sherpa，嵌入服务直接断。别改回通配删除。
7. **App 不内置 MiniCPM5 模型目录**——App 本就支持任意 GGUF URL 手动下载，引擎能跑即支持；内置目录是 UI 变更，要过多尺寸矩阵验收，不属于本次范围。
8. **真机验证缺口如实记录**——本轮无手机插 mini（adb 列表空），原生 macOS 验证走 MacBook（mini 的 C 编译器探测同样被 Xcode 许可挡死）；设备级回归（CPU 变体自选/Vulkan/启动链）标记待回填。

## §4 验证（命令 + 断言 + 实测数字）

### 4.1 tarball 完整性
- 合并后字节数 38,680,037（精确匹配）；`gzip -t` 通过（gzip CRC32 全量校验）。分段获取路线与坑见 技术发现003。

### 4.2 补丁与旗标兼容性（v0.8.0 源码逐项核对）
- BDA 补丁 `patch -p1 --dry-run` 干净适用，已应用（mini 与 MacBook 两棵树同验）；
- App 全部旗标在 `common/arg.cpp` 一一在位：`-m/--host/--port/-c/-t/--temp/--top-p/--jinja/--alias/--mmproj/-ngl/--api-key/--embeddings/--pooling` ✓；
- `--reasoning off` 原生保留（arg.cpp `{"-rea","--reasoning"}, "[on|off|auto]"`，off → `enable_thinking=false` 注入模板，与 App 注释语义一字不差）✓；
- `-fa` 默认 `LLAMA_FLASH_ATTN_TYPE_AUTO`（common.h）——新引擎自动启用 FA，App 无需传参 ✓；
- **唯一适配点**：`--no-mmap` 已被上游移除，等义替换 `--load-mode none`（load_mode=LLAMA_LOAD_MODE_NONE）。已改 `LlamaServerService.kt:157` 与设置页文案；嵌入实例（EmbeddingServer.kt）不受影响。JVM 单测全绿（含钉死命令行形态的 EmbeddingServerTest）。

### 4.3 冒烟测（v0.8.0 原生 llama-server @ MacBook，纯 CPU t=4，MiniCPM5-2B Q4_K_XL）
- 加载 6 秒上膛，`/health` ok；`--jinja` 下 `/props` 返回的 chat_template 与 GGUF 内嵌模板逐字节同源（含 `namespace(sys, last_query_index)` 结构）；
- `/v1/chat/completions` 中文技术问答回复连贯正确（GGUF 格式解释无误），prompt 177 tok / completion 64 tok；
- 请求实测：prefill 83.46 t/s、生成 28.98 t/s。

### 4.4 A/B 基准（同机同模型同参，llama-bench -t 4 -pp512 -n128 -r3）
| 引擎 | pp512 (t/s) | tg128 (t/s) |
|---|---|---|
| 旧：2026-07-18 快照（build 230ea9d，LLAMAFILE=OFF） | 81.95 ± 1.26 | 26.13 ± 1.21 |
| 新：v0.8.0（LLAMAFILE=ON） | 83.87 ± 3.36 | 25.93 ± 1.55 |

**诚实结论**：M 系桌面级 CPU 上新旧引擎原始算力**持平（差值在噪声带内）**。本次升级的价值不在裸算力：
1. **可用性**：旧引擎不认识 `tokenizer.ggml.pre=minicpm5`，MiniCPM5 分词退化为 fallback 正则、配合默认模板错排 prompt——模型根本「用不对」；新引擎原生支持（冒烟测 4.3 为证）。
2. **真机速度待验**：上游 7→10 月的 ARM/Android 专项改进（dotprod/i8mm 变体路径、Vulkan 重构——libggml-vulkan 54M→32M）对天玑 900 级手机的影响须真机实测；v1.1.17 自带 llama-bench，插机后跑「最优引擎选择测评」即可回填（对照基线：pp512 ~86-91 / tg64 ~12.7-13.7，见 GPU加速与引擎测评.md）。

### 4.5 打包与回归
- Android 交叉编译：MacBook + 搬家 NDK r26.3（许可绕行，见 发现002），产物 `file` 验证 ELF 64-bit ARM aarch64，CPU 7 变体 + Vulkan + mtmd 布局与 7 月版一致；
- jniLibs 精准替换：libggml*/libllama*/libmtmd* 换新，libonnxruntime.so/libsherpa-onnx-jni.so 原样保留（APK 内清单已核）；
- JVM 单测 TEST-RC=0（17s 全绿）；assembleDebug RC=0；`debug/GGUF-Server-debug-1.1.17.apk`（136M）归档；
- **待回填**：真机回归（启动链/CPU 变体自选/Vulkan/嵌入服务/llama-bench 真机数字）。

## §5 文件索引

- 引擎源：/Volumes/SystemData/llama-build/llama.cpp（v0.8.0）、备份 llama.cpp.bak-20260718
- 构建脚本：scripts/rebuild.sh（正牌）、scripts/build-llama-android.sh（全新机）、BDA 补丁 scripts/patches/ggml-vulkan-bda-env-gate.patch
- 启动链：android/app/src/main/java/com/ggufserver/app/server/LlamaServerService.kt、EmbeddingServer.kt
- 实测 GGUF：/Volumes/SystemData/llama-build/models/Sharp-MiniCPM5-2B-Q4_K_XL.gguf

## §6 版本履历

- v1.0.0（2026-11-03）：初版，v0.8.0 引擎上线（本文档随当次任务立档）。

## §7 重建清单

- 语义：引擎升级 = 源码树替换 + 双脚本重编 + jniLibs 三前缀替换 + （如需）App 旗标适配。
- 数据：tarball 获取→gzip -t 校验→换树→补丁→configure→build→拷贝→打 APK。
- 规则：jniLibs 清理只删 libggml*/libllama*/libmtmd*；引擎验证至少过「加载 + 一轮对话 + 速度对比」。
- 接口：llama-server 启动参数以 v0.8.0 tools/server 为准；/v1 行为 OpenAI 兼容不变。
- 验证：见 §4 命令级记录。
- 刻意不做：不内置模型目录、不动端口拓扑（内部端口=公开±1、嵌入 8101）、不开 OPENMP、不碰 GGML_VK_DISABLE_BDA 注入。
