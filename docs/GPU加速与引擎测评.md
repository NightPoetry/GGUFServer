# GPU 加速与「最优引擎选择测评」

本文记录本机（Redmi ruby / Dimensity 900 MT6877 / Mali-G68 MC4）上把推理从"单纯 CPU 太慢"提速数倍的完整调研、结论与工程实现。

## 1. 结论先行

| 引擎 | prefill (pp512) | 生成 (tg64) | 说明 |
|---|---|---|---|
| 旧线上：基线 CPU（armv8-a，无 dotprod） | ~15.8 t/s | ~9.2 t/s | Q8_0 的整数点积走了慢速回退路径 |
| Vulkan GPU（Mali-G68 全 offload） | ~16.6 t/s | ~7.2 t/s | prefill 打平、**生成更慢**，且每次启动 ~1min 编译 shader |
| **CPU 多变体·自动选 dotprod（armv8.2_2）** | **~86–91 t/s** | **~12.7–13.7 t/s** | **prefill ≈ 5.5×，生成 ≈ 1.4×**，无启动开销 |

**真相不是"CPU 不行"，而是旧库没开 dotprod（ARMv8.2 SDOT）指令**——Q8_0 矩阵乘全靠它。开了之后那个折磨人的 40s prefill 掉到 ~7s。

**GPU（Vulkan）在这台 Mali 上不划算**：手机是统一内存，生成阶段受内存带宽限制、GPU 与 CPU 抢同一条带宽反而更慢；prefill 因 Mali 无 coopmat / 无整数点积、Q8_0 走标量 shader 也快不起来。**但仍作为可选项保留**（换更大模型、或 Adreno/高端 GPU 机型时可能有收益）。到底用不用 GPU 由「最优引擎选择测评」按机型 × 模型实测决定，不写死。

## 2. CPU 多变体（真正的提速）

用 llama.cpp 的运行时变体分发：

```
-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF
```

编出一组 CPU 后端 `.so`（`libggml-cpu-android_armv8.0_1.so` … `armv8.6_1`(i8mm) … `armv9.2_2`(SVE/SME)），**启动时按 `/proc/cpuinfo` 特性自动选最快且兼容的那个**：

- 老芯片（无 dotprod）→ `android_armv8.0_1`，不会 SIGILL；
- 本机 A78 → `android_armv8.2_2`（DOTPROD + FP16_VECTOR）；
- 更新旗舰 → `android_armv8.6_1`（+ i8mm）再加速。

一次满足「最快 + 最广兼容 + 自动回退」，无需手动 `-march`。backend-dl 从可执行文件所在目录（app 里即 `nativeLibraryDir`）搜变体 `.so`。

## 3. Vulkan 的两个坑（都已解决）

### 3.1 BDA 空指针崩溃（`scripts/patches/ggml-vulkan-bda-env-gate.patch`）

Mali 驱动**上报** `bufferDeviceAddress` 特性，但没导出可用的 `vkGetBufferDeviceAddress` 函数指针；ggml-vulkan 无条件调用它 → PC=0 空指针 → 后端**初始化**期 segfault（连 `-ngl 0` 也崩，因为要分配 Vulkan host buffer）。

补丁把它改成 env 可关：

```cpp
device->buffer_device_address = vk12_features.bufferDeviceAddress &&
                                getenv("GGML_VK_DISABLE_BDA") == nullptr;
```

所有用到 `bda_addr` 的路径都被 `buffer_device_address` 门控，关掉只是回退到描述符绑定，功能不受损。**app 启动引擎时始终注入 `GGML_VK_DISABLE_BDA=1`。**

### 3.2 打包 Vulkan 后连 CPU 模式都被拖到 GPU 上跑

统一内存设备上，只要 Vulkan 后端存在，即便 `-ngl 0` 整张计算图也会被塞到慢速 GPU（实测 16.8 而非 dotprod 的 91）。解法：**CPU 引擎（ngl==0）额外注入 `GGML_VK_VISIBLE_DEVICES=""`**，让 Vulkan 报告 0 设备、计算强制留在 CPU。

### 启动引擎的环境/参数矩阵（见 `LlamaServerService`）

| 引擎 | -ngl | 环境变量 |
|---|---|---|
| CPU（dotprod） | 0 | `GGML_VK_DISABLE_BDA=1` + `GGML_VK_VISIBLE_DEVICES=""` |
| GPU（Vulkan） | >0 | `GGML_VK_DISABLE_BDA=1` |

## 4. 「最优引擎选择测评」（设置 → 运行参数）

- `server/EngineBenchmark.kt`：用打包的 `libllamabench.so`（llama-bench 改名）作子进程，对当前模型跑一小段（`-p 256 -n 64 -r 2`）实测 CPU（ngl 0）与 GPU（ngl 99），解析 t/s，按"生成速度优先、相近比 prefill"选最快。
- GPU 档崩溃（子进程 SIGSEGV/被杀）或超时 → 判该模型 GPU 不可用，绝不拖垮 app。
- 结果按模型持久化（`SettingsStore.modelNgl/modelEngineNote/gpuBad`，键=模型绝对路径）；`MainViewModel.effectiveNgl` 启动时据此选 -ngl：GPU 不兼容→0 / 已测评→测评值 / 否则全局字段。
- **默认 ngl=0 即 CPU dotprod**，用户不测评也直接享受 ~5.5× 提速。

## 5. GPU 崩溃 → CPU 自动回退（fail-open）

`LlamaServerService` 的子进程退出处理器：`ngl>0` 且非零退出、非用户停止/配置重启、只回退一次 → 用 `Intent` 复制成 `ngl=0` 重启；崩在 `!reachedRunning`（确定不兼容）则 `setModelGpuBad` 永久标记。**子进程崩溃 app 本体毫发无伤**——这正是"自己探测到崩溃并回退"，而非整个程序挂掉。

## 6. 构建

```bash
./rebuild.sh                 # CPU 多变体 + Vulkan（默认）
WITH_VULKAN=0 ./rebuild.sh   # 只 CPU（APK 更小；Vulkan 本机无收益时用）
```

全新机器用 `scripts/build-llama-android.sh`（自带 llama.cpp / Vulkan headers 拉取 + BDA 补丁 `git apply`）。产物：`libllamaserver.so`（服务）、`libllamabench.so`（测评）、全部 `libggml-cpu-*` 变体、`libggml-vulkan.so`，全进 `jniLibs/arm64-v8a`。

> Vulkan 依赖（Vulkan-Headers / SPIRV-Headers）与工具链在 `/Volumes/SystemData/llama-build/deps`；llama.cpp 补丁后的源码树在 `/Volumes/SystemData/llama-build/llama.cpp`（build-android-dl）。
