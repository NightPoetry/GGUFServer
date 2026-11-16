# 发现008 · GitHub Actions ubuntu-latest 迁移 26.04 连环挂_Android Build 五轮修复全记录

**一句话结论**：2026-10-19 ubuntu-latest 迁移 Ubuntu 26.04 镜像后，仓库 Android Build workflow 连败五轮，根因链=①android-actions/setup-android@v3 在新镜像失败（上游 issue#546）→删它后②裸 sdkmanager 不在 PATH（exit 127）→③绝对路径写法踩 YAML 语法（run 值以双引号开头后拖参数=解析器断）→④⑤workflow 顶层 env 的 `${{ env.ANDROID_HOME }}` 展开为空（顶层 env 不支持 env 上下文引用，旧成功靠 setup-android 注入 GITHUB_ENV）→终解=NDK/licenses 全走字面量路径 `/usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager` + 镜像文档化常量路径，run#10 全绿。
**日期**：2026-11-13/16
**环境**：NightPoetry/GGUFServer 仓库 .github/workflows/android-build.yml（ubuntu-latest→24.04，actions/checkout@v5，setup-java@v5，NDK 26.3.11579264）
**症状**：push 后 19-47 秒失败（workflow file issue / step4 failure）；CMake 报 `Could not find toolchain file: /ndk/26.3.11579264/...`（前缀丢失=变量空）；`sdkmanager: command not found`。
**根因**：见结论链；外加 runner 镜像滚动（钉 ubuntu-24.04 后其滚动镜像仍可能变，setup-android@v3 在 24.04 也开始挂）。
**解法（可复现）**：
```yaml
runs-on: ubuntu-24.04
steps:
  - uses: actions/checkout@v5
  - uses: actions/setup-java@v5   # v4 有 Node20 deprecation
  # 不要 android-actions/setup-android——失败源
  - name: Accept SDK licenses
    run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
  - name: Install pinned NDK
    run: "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;26.3.11579264"
# 注意 run 值不能用双引号开头（YAML 断行）；workflow 顶层 env 不支持 env 上下文引用
```
**验证**：run#10（5072bb4）九步全绿（licenses/NDK/llama.cpp 编译/assembleDebug/artifact）。
**边界与坑**：
- 诊断通道：公开仓库匿名 API 拿不到 step 日志（需 admin token），但 **gh CLI（本机已认证 NightPoetry）`gh run view --log-failed` 可以**——先拉日志再改，不靠 push 试错（CI 免费额度有限，用户令：尽可能分析出正确原因后再实验）。
- run: 值含双引号开头+尾参数的 YAML 陷阱；`${{ env.X }}` 在 workflow 顶层 env 展开为空是文档化行为。
**关联**：android-actions/setup-android#546；runner-images#14748（ubuntu 26 迁移）。
