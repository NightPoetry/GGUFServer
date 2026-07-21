#!/usr/bin/env bash
#
# 从零交叉编译 llama.cpp 的 llama-server + llama-bench（Android arm64-v8a），装进 app 的 jniLibs。
# 与 rebuild.sh 等价，但自带 llama.cpp / Vulkan 依赖的拉取，适合全新机器。
#
# 加速方案（见 docs/GPU加速与引擎测评.md）：
#   - CPU：GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL 编出一组 CPU 变体，启动时按特性自动选
#     （armv8.0 兜底 / armv8.2+dotprod / armv8.6+i8mm / armv9+sve...）——不再手动 -march。
#   - GPU：可选 Vulkan（带 BDA 空指针补丁，Mali 等驱动必需）。
#
# 用法：
#   export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/26.3.11579264"
#   ./scripts/build-llama-android.sh              # CPU 多变体 + Vulkan
#   WITH_VULKAN=0 ./scripts/build-llama-android.sh # 只 CPU（APK 更小）

set -euo pipefail

ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-28"
WITH_VULKAN="${WITH_VULKAN:-1}"

: "${ANDROID_NDK:?请先设置 ANDROID_NDK，例如 export ANDROID_NDK=\$HOME/Library/Android/sdk/ndk/26.3.11579264}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$ROOT_DIR/android/app/src/main/jniLibs/$ANDROID_ABI"
WORK_DIR="$ROOT_DIR/.build"
SRC_DIR="$WORK_DIR/llama.cpp"
DEPS_DIR="$WORK_DIR/deps"
BUILD_DIR="$SRC_DIR/build-android-dl"

mkdir -p "$WORK_DIR" "$JNILIBS_DIR"

# ---- 1. fetch llama.cpp ----------------------------------------------------
if [ ! -d "$SRC_DIR/.git" ]; then
  echo ">> 克隆 llama.cpp ..."
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$SRC_DIR"
else
  echo ">> 更新 llama.cpp ..."
  git -C "$SRC_DIR" pull --ff-only || true
fi

# ---- 1b. 应用 BDA 空指针补丁（Mali 等驱动上报 bufferDeviceAddress 却没导出函数指针 → 初始化 segfault）--
PATCH="$SCRIPT_DIR/patches/ggml-vulkan-bda-env-gate.patch"
if [ -f "$PATCH" ]; then
  if git -C "$SRC_DIR" apply --check "$PATCH" 2>/dev/null; then
    echo ">> 应用 BDA 补丁 ..."; git -C "$SRC_DIR" apply "$PATCH"
  else
    echo ">> BDA 补丁已应用或不适用，跳过。"
  fi
fi

# ---- 1c. Vulkan 依赖（仅 WITH_VULKAN=1）------------------------------------
VK_ARGS=()
if [ "$WITH_VULKAN" = "1" ]; then
  mkdir -p "$DEPS_DIR"
  [ -d "$DEPS_DIR/Vulkan-Headers" ] || git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers "$DEPS_DIR/Vulkan-Headers"
  [ -d "$DEPS_DIR/SPIRV-Headers" ]  || git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers "$DEPS_DIR/SPIRV-Headers"
  GLSLC="$ANDROID_NDK/shader-tools/$(uname | tr '[:upper:]' '[:lower:]')-x86_64/glslc"
  VK_ARGS=(
    -DGGML_VULKAN=ON
    -DVulkan_INCLUDE_DIR="$DEPS_DIR/Vulkan-Headers/include"
    -DVulkan_GLSLC_EXECUTABLE="$GLSLC"
    -DSPIRV-Headers_DIR="$DEPS_DIR/SPIRV-Headers"
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
    -DCMAKE_CXX_FLAGS="-I$DEPS_DIR/Vulkan-Headers/include"
  )
fi

# ---- 2. configure ----------------------------------------------------------
echo ">> 配置 CMake（CPU 多变体 + backend-dl${WITH_VULKAN:+ + Vulkan}）..."
cmake -S "$SRC_DIR" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF "${VK_ARGS[@]}"

# ---- 3. build server + bench ----------------------------------------------
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
echo ">> 编译 llama-server + llama-bench (-j$JOBS) ..."
cmake --build "$BUILD_DIR" --target llama-server llama-bench --config Release -j"$JOBS"

# ---- 4. collect into jniLibs ----------------------------------------------
echo ">> 清理旧的 native 产物 ..."
rm -f "$JNILIBS_DIR"/*.so
# 可执行文件改名 lib*.so（Android 仅 nativeLibraryDir 可执行）
cp "$BUILD_DIR/bin/llama-server" "$JNILIBS_DIR/libllamaserver.so"
cp "$BUILD_DIR/bin/llama-bench"  "$JNILIBS_DIR/libllamabench.so"
# 全部 .so（含 CPU 变体 + Vulkan）
find "$BUILD_DIR/bin" -maxdepth 1 -name '*.so' -type f -exec cp {} "$JNILIBS_DIR/" \;

echo ""
echo "✅ 完成。native 产物已放入：$JNILIBS_DIR"
ls -1 "$JNILIBS_DIR"
echo ""
echo "下一步：用 Android Studio 打开 android/ 目录，或跑 scripts/debug.sh，编译并安装 APK。"
