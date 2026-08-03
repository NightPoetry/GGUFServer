#!/usr/bin/env bash
#
# 一键把 App 装到已用 USB 连接、并开启「USB 调试」的安卓手机上，
# 并把模型推到手机 Download 目录，方便在 App 里选中。
#
# 用法：
#   ./deploy.sh                          # 用默认模型
#   ./deploy.sh /path/to/your-model.gguf # 指定别的 GGUF
#
set -euo pipefail

ADB="/Volumes/SystemData/android-sdk/platform-tools/adb"
PROJ="/Volumes/UserData/Projects/LLM/手机部署小模型服务器"
# 装当前版本号对应的调试包（debug/ 里按版本号留档，见 docs/打包流程.md）
VER=$(grep -o 'versionName = "[^"]*"' "$PROJ/android/app/build.gradle.kts" | cut -d'"' -f2)
APK="$PROJ/debug/GGUF-Server-debug-$VER.apk"
DEFAULT_MODEL="/Volumes/UserData/Projects/LLM/小模型轮椅/build_model/Qwen3.5-2B-Q8_0.gguf"
MODEL="${1:-$DEFAULT_MODEL}"

[ -x "$ADB" ] || { echo "❌ 找不到 adb：$ADB（先跑完 build 步骤）"; exit 1; }
[ -f "$APK" ] || { echo "❌ 找不到 APK：$APK（先跑 ./scripts/debug.sh 或 ./rebuild.sh 构建当前版本调试包）"; exit 1; }
[ -f "$MODEL" ] || { echo "❌ 找不到模型：$MODEL"; exit 1; }

echo ">> 等待手机（请确认：数据线已连、已开启开发者选项→USB 调试、手机上点了「允许」）..."
"$ADB" wait-for-device
STATE="$("$ADB" get-state 2>/dev/null || echo unknown)"
echo ">> 设备状态：$STATE"
"$ADB" devices

echo ">> 安装 APK ..."
"$ADB" install -r "$APK"

echo ">> 推送模型到手机 /sdcard/Download/ （$(basename "$MODEL")，约 $(( $(stat -f '%z' "$MODEL")/1024/1024 )) MB，稍等）..."
"$ADB" push "$MODEL" "/sdcard/Download/$(basename "$MODEL")"

# 手机局域网 IP（供参考）
IP="$("$ADB" shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -1 || true)"

echo ""
echo "✅ 完成！接下来在手机上："
echo "   1) 打开「GGUF 服务器」App，允许通知权限"
echo "   2) 点「上传 GGUF」→ 选 Download 里的 $(basename "$MODEL")"
echo "   3) 点「启动服务」"
if [ -n "${IP:-}" ]; then
  echo "   手机局域网地址约为： http://$IP:8080/v1  （以 App 内显示为准）"
fi
echo ""
echo "在电脑上测试（同一 WiFi）："
echo "   curl http://<手机IP>:8080/v1/chat/completions -H 'Content-Type: application/json' \\"
echo "     -d '{\"model\":\"local\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}'"
