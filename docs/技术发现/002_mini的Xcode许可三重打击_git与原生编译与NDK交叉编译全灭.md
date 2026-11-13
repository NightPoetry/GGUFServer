# 发现002 · mini的Xcode许可三重打击_git与原生编译与NDK交叉编译全灭

**一句话结论**：Xcode 更新后许可未重新同意，mini 上 git、原生 macOS 编译、NDK 交叉编译三条路全部 exit 69 被挡；解法是人工跑一次 `sudo xcodebuild -license`，在此之前交叉编译可整体搬到许可正常、有 Rosetta 的 Apple Silicon 机器上做（NDK x86_64 工具链 tar 搬家即可）。

**日期**：2026-11-03

**环境**：Mac mini 192.168.3.124（M4，macOS，Xcode 曾自动更新），Android NDK r26.3（darwin-x86_64 预编译），CMake 3.30.5（独立安装，非 Xcode 自带）。

**症状**：
1. `git --version` 即报 "You have not agreed to the Xcode license agreements"（exit 69）——clone/pull/apply 全灭；
2. CMake 原生 macOS 构建在 CMakeTestCCompiler 一步报同一许可文案（Apple clang shim 拒绝工作）；
3. **连 Android NDK 交叉编译也报同一文案**：NDK 自带 clang 17 在 CMake 编译器探测（编译测试程序）时被许可门拦截——NDK 工具链并非与 Apple 许可完全无关。

**根因**：Apple 工具链二进制（含作为 shim 的 /usr/bin/git、/usr/bin/clang）在运行时核对许可状态；Xcode 自动更新会重置许可接受状态。7 月构建能过是因为当时许可有效。NDK clang 侧的具体触发点在其宿主阶段对 Apple 组件的调用（表现为同一文案），未逐层定位——修法一致，无需深挖。

**解法（可复现）**：
```bash
# 正解（需人工，一次性的）：在 mini 上
sudo xcodebuild -license accept
# 许可未接受期间的绕行：把交叉编译搬到许可正常的机器（需 Rosetta 跑 x86_64 NDK 工具链）
ssh mini 'cd $NDK_ROOT && tar cf - build source.properties shader-tools toolchains/llvm/prebuilt/darwin-x86_64' | tar xf - -C ~/ndk-r263
export ANDROID_NDK=~/ndk-r263   # 然后正常 cmake -DCMAKE_TOOLCHAIN_FILE=~/ndk-r263/build/cmake/android.toolchain.cmake
# 产物（arm64 Android ELF）scp 回项目 jniLibs；Gradle 打包留在原机做（JVM 链路不碰 Apple shim）
```

**验证**：2026-11-03 在 MacBook（许可正常 + Rosetta 在位）用搬家的 NDK r26.3 完成 llama.cpp v0.8.0 的 Android arm64-v8a 交叉编译，产物 `file` 验证为 ELF 64-bit ARM aarch64，装进 APK（GGUF-Server-debug-1.1.17）打包成功；同机原生构建（同源码）亦成功。

**边界与坑**：
- `arch -x86_64 /usr/bin/true` 可提前探测 Rosetta 是否可用；
- x86_64 NDK 工具链在 Apple Silicon 上靠 Rosetta 跑，速度略降（本例 38% → 100% 约 8 分钟，可接受）；
- mini 上 `python3`/`git`/`clang` 同属被许可挡的 shim 家族，凡脚本里用到都要换路；
- 修复动作本身（sudo）只能主人做，AI 会话不要试图绕过许可机制本身。

**关联**：发现003（当日源码获取同因受困）、GGUF-Server 设计文档03。
