#!/bin/bash
#
# 远端编译入口 —— 在 Mac mini（192.168.3.124）上执行。
#
# 背景（2026-10-29 定版的工作关系，见 docs/设计文档/）：
#   项目本体与编译工具链都在 mini 的本机盘上（/Volumes/UserData 与 /Volumes/SystemData
#   是同一块 1.9T 内置卷），MacBook 侧通过 SMB 挂载只作文件交换。编译一律走本脚本在
#   mini 上原生执行（M4 本地盘 Gradle 单测实测 9s），不要在 MacBook 上对着 SMB 挂载跑
#   Gradle（跨网 IO 慢一个数量级，且 Gradle 文件锁在 SMB 上是知名坑）。
#
# 本机调用（单层引号，无嵌套，可直接粘贴）：
#   ssh nightpoetry@192.168.3.124 '/Volumes/UserData/Projects/LLM/手机部署小模型服务器/scripts/mini-build.sh test'
#
# task：
#   test      （默认）打包前两组自检：JVM 单测 + 插件离线自检，见 docs/打包流程.md
#   assemble  只编 debug APK（不归档不部署）
#   debug     打调试包并按版本号归档到 debug/（同版本号覆盖）
#   release   打正式包（需先在 build.gradle.kts 升 versionName/versionNote，否则拒绝执行）
#   deploy    手机插在 mini 上时：装 debug 包 + 推模型（等价本机 ./deploy.sh）
#   stop      停掉远端 Gradle daemon（mini 只有 16GB 内存，长不用时释放）
set -euo pipefail

# ssh 非登录 shell 的 PATH 不含 /opt/homebrew/bin，而 node（check-plugins.js 用）装在那里
if [ -d /opt/homebrew/bin ]; then
  export PATH="/opt/homebrew/bin:$PATH"
fi

source /Volumes/SystemData/gguf-toolchain/env.sh
cd "$PROJ"

TASK="${1:-test}"
case "$TASK" in
  test)
    "$GRADLE" -p android :app:testDebugUnitTest --console=plain
    node scripts/check-plugins.js
    ;;
  assemble)
    "$GRADLE" -p android assembleDebug --console=plain
    ;;
  debug)   exec ./scripts/debug.sh ;;
  release) exec ./scripts/release.sh ;;
  deploy)  exec ./deploy.sh ;;
  stop)    exec "$GRADLE" --stop ;;
  *)
    echo "用法: mini-build.sh [test|assemble|debug|release|deploy|stop]"
    exit 1
    ;;
esac
