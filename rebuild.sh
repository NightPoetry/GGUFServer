#!/usr/bin/env bash
#
# 重新编译 native 二进制 + APK（使用已装在 /Volumes/SystemData 的工具链）。
# 改了 llama.cpp 版本、或 Android 代码后，跑这个即可。
#
#   ./rebuild.sh                 # CPU 多变体（运行时按 CPU 特性自动选 dotprod/i8mm，老机回退）+ Vulkan GPU
#   WITH_VULKAN=0 ./rebuild.sh   # 精简：只打 CPU 多变体，不含 Vulkan（APK 更小；GPU 那条路本机无收益时用）
#
# 加速原理见 docs/GPU加速与引擎测评.md：
# - 不再手动指定 -march。GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL 会编出一组 CPU 变体
#   （android_armv8.0 / armv8.2+dotprod / armv8.6+i8mm / armv9+sve...），启动时自动挑最快且兼容的那个。
# - Vulkan 后端带 BDA 空指针补丁（Mali 等驱动必需）；app 启动时按引擎注入 GGML_VK_DISABLE_BDA / VISIBLE_DEVICES。
#
set -euo pipefail

ENV=/Volumes/SystemData/gguf-toolchain/env.sh
[ -f "$ENV" ] || { echo "❌ 找不到工具链环境 $ENV，工具链可能被删了"; exit 1; }
# shellcheck disable=SC1090
source "$ENV"

LLAMA=/Volumes/SystemData/llama-build/llama.cpp
BUILD="$LLAMA/build-android-dl"
DEPS=/Volumes/SystemData/llama-build/deps
GLSLC="$ANDROID_NDK/shader-tools/darwin-x86_64/glslc"
JNI="$PROJ/android/app/src/main/jniLibs/arm64-v8a"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 6)"
WITH_VULKAN="${WITH_VULKAN:-1}"

# 1) （必要时）重新 configure
if [ ! -d "$BUILD" ] || [ -n "${RECONFIGURE:-}" ]; then
  echo ">> 配置 CMake（CPU 多变体 + backend-dl${WITH_VULKAN:+ + Vulkan}）..."
  VK_ARGS=()
  if [ "$WITH_VULKAN" = "1" ]; then
    VK_ARGS=(
      -DGGML_VULKAN=ON
      -DVulkan_INCLUDE_DIR="$DEPS/Vulkan-Headers/include"
      -DVulkan_GLSLC_EXECUTABLE="$GLSLC"
      -DSPIRV-Headers_DIR="$DEPS/install/share/cmake/SPIRV-Headers"
      -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
      -DCMAKE_CXX_FLAGS="-I$DEPS/install/include -I$DEPS/Vulkan-Headers/include"
    )
  fi
  "$CMAKE_BIN" -S "$LLAMA" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF "${VK_ARGS[@]}"
fi

# 2) 编译 llama-server（服务）+ llama-bench（“最优引擎选择测评”用）
echo ">> 编译 llama-server + llama-bench (-j$JOBS) ..."
"$CMAKE_BIN" --build "$BUILD" --target llama-server llama-bench -j"$JOBS"

# 3) 拷贝到 jniLibs：全部 .so（含 CPU 变体 + Vulkan），可执行文件改名 lib*.so
echo ">> 更新 jniLibs ..."
mkdir -p "$JNI"; rm -f "$JNI"/*.so
find "$BUILD/bin" -maxdepth 1 -name '*.so' -exec cp {} "$JNI/" \;
cp "$BUILD/bin/llama-server" "$JNI/libllamaserver.so"
cp "$BUILD/bin/llama-bench"  "$JNI/libllamabench.so"
echo ">> jniLibs 内容："; ls -1 "$JNI" | sed 's/^/     /'

# 4) 打 APK
echo ">> 打包 APK ..."
cd "$PROJ/android"
"$GRADLE" assembleDebug --no-daemon --console=plain
# 版本化归档到 debug/（与 scripts/debug.sh 一致；同版本号覆盖）
VER=$(grep -o 'versionName = "[^"]*"' "$PROJ/android/app/build.gradle.kts" | cut -d'"' -f2)
mkdir -p "$PROJ/debug"
OUT="$PROJ/debug/GGUF-Server-debug-${VER:-unknown}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"

echo ""
echo "✅ 完成： $OUT"
echo "   插上手机（开 USB 调试）后运行： ./deploy.sh"
