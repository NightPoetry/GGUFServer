#!/bin/bash
# 调试包打包 + 版本化归档脚本（标准流程，见 docs/打包流程.md）
# 用法：./scripts/debug.sh
# 与 release.sh 平行：debug 包按版本号留档到 debug/ 文件夹。
# 与 release 的区别：debug 同版本号允许覆盖（调试迭代频繁，每个版本号留最新一份即可）。
# 原生库（jniLibs/llama-server）变更时请改用 ./rebuild.sh（它也会归档到 debug/）。
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
source /Volumes/SystemData/gguf-toolchain/env.sh

# 从 build.gradle.kts 读当前版本号
VER=$(grep -o 'versionName = "[^"]*"' "$PROJ/android/app/build.gradle.kts" | cut -d'"' -f2)
[ -n "$VER" ] || { echo "读不到 versionName"; exit 1; }

DEBUG_DIR="$PROJ/debug"
mkdir -p "$DEBUG_DIR"

echo "== 打调试包 v$VER =="
cd "$PROJ/android"
"$GRADLE" assembleDebug

OUT="$DEBUG_DIR/GGUF-Server-debug-$VER.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"   # 版本化归档（同版本号覆盖）
echo ""
echo "完成：$OUT"
echo "部署：./deploy.sh（自动装当前版本号对应的调试包）"
ls -lh "$DEBUG_DIR"/GGUF-Server-debug-*.apk 2>/dev/null