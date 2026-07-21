#!/bin/bash
# 正式包打包脚本（标准流程，见 docs/打包流程.md）
# 用法：./scripts/release.sh
# 前提：已在 android/app/build.gradle.kts 把 versionName +0.0.1、versionCode +1
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
source /Volumes/SystemData/gguf-toolchain/env.sh

# 从 build.gradle.kts 读当前版本号
VER=$(grep -o 'versionName = "[^"]*"' "$PROJ/android/app/build.gradle.kts" | cut -d'"' -f2)
[ -n "$VER" ] || { echo "读不到 versionName"; exit 1; }

# 本版本主要升级备注：从 build.gradle.kts 的 `// versionNote: ...` 注释读
# （打包流程要求版本号后跟一句升级备注，见 docs/打包流程.md）
NOTE=$(grep -o '// versionNote:.*' "$PROJ/android/app/build.gradle.kts" | head -1 | sed 's|// versionNote:[[:space:]]*||')
[ -n "$NOTE" ] || { echo "错误：build.gradle.kts 里缺少 '// versionNote: <本版本主要升级>'（打包流程要求版本号后跟升级备注，见 docs/打包流程.md）"; exit 1; }

RELEASE_DIR="$PROJ/release"
mkdir -p "$RELEASE_DIR"

OUT="$RELEASE_DIR/GGUF-Server-release-$VER.apk"
if [ -e "$OUT" ]; then
    echo "错误：$OUT 已存在。正式包版本号必须先 +0.0.1（改 android/app/build.gradle.kts），旧包留档不覆盖。"
    exit 1
fi

echo "== 打正式包 v$VER =="
cd "$PROJ/android"
"$GRADLE" assembleRelease

cp app/build/outputs/apk/release/app-release.apk "$OUT"

# 记录本版本升级备注到 CHANGELOG（版本号后跟主要升级备注，最新在最上）
CHANGELOG="$RELEASE_DIR/CHANGELOG.md"
# 新建时写 4 行头（标题/空/说明/空），第 4 行的空行是新条目的插入锚点
[ -f "$CHANGELOG" ] || printf '# 正式包版本历史\n\n每个版本号后跟该版本主要升级备注（打包时由 scripts/release.sh 自动追加）。\n\n' > "$CHANGELOG"
ENTRY="- **v$VER**（$(date +%Y-%m-%d)）— $NOTE"
if ! grep -qF "**v$VER**" "$CHANGELOG"; then
    # 插到说明段之后（第 4 行空行之后），保持最新在最上
    awk -v e="$ENTRY" 'NR==4{print; print e; next} {print}' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"
fi

echo ""
echo "完成：$OUT"
echo "备注：$NOTE"
echo "已记入：$CHANGELOG"
ls -lh "$RELEASE_DIR"/GGUF-Server-release-*.apk 2>/dev/null
echo ""
echo "提醒：正式包与调试包签名不同，不要装到插着数据线的开发机上（会要求先卸载、清掉会话和模型）。"
