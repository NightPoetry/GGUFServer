# 发现006 · BSD文本工具链在二进制流上的假零_单超长行与locale陷阱

**一句话结论**：在 macOS（BSD 工具链）上对二进制流做「管道 grep/strings/tr」检索会得到**假零**（内容明明存在却计数为 0）——三个独立陷阱叠加：①BSD grep 面对无换行的 44MB 单行静默返回 0；②`strings -` 在 macOS 上不读 stdin；③`tr` 在非 C locale 下对二进制报 `Illegal byte sequence` 后中止管线。结论：**APK/dex 内容校验一律用 python zipfile 读字节再 `.count()`，不要在 shell 管道里跟二进制较劲**。

**日期**：2026-11-04

**环境**：Mac mini（macOS，BSD grep/strings/tr），目标：验证 APK 的 dex 里是否包含某符号（ModelHubScreen）。

**症状**：`unzip -p x.apk classes.dex | grep -ac "ModelHubScreen"` 与逐 dex 扫描全部返回 0；`strings -` 计数为 0；加 `tr -c '[:print:]' '\n'` 后报 `Illegal byte sequence`。同一 APK 拉到 MacBook 用 python zipfile 读字节，`ModelHub` 命中 232+50 次。假零险些误判「打包漏了新代码」。

**根因**：①dex 几乎不含换行符，整个文件对 grep 是一根 44MB 的单行，BSD grep 超长行处理失败且不报错；②BSD strings 无 `-` 读 stdin 的约定（GNU 有）；③macOS 默认 UTF-8 locale 下 tr 处理非法字节序列即中止（GNU 下多数宽松忽略）。

**解法（可复现）**：
```bash
# 错误示范（三个都会假零）：
unzip -p a.apk classes.dex | grep -ac "SYM"
unzip -p a.apk classes.dex | strings - | grep -c "SYM"
unzip -p a.apk classes.dex | tr -c '[:print:]' '\n' | grep -c "SYM"
# 正确（本地或远端均可）：
python3 - <<'PY'
import zipfile
z = zipfile.ZipFile('a.apk')
d = z.read('classes.dex')
print(d.count(b'SYM'))
PY
```

**验证**：同一下载的 dex，shell 管道全 0；python `zipfile.ZipFile.read().count(b'ModelHub')` = 232（classes3.dex）+50（classes9.dex），与源码事实一致。

**边界与坑**：
- 假零的可怕在于「零」看起来像合法结果——先拿一个必然存在的东西做对照（如 dex 里搜 `androidx`），对照也为零即说明是工具链问题而非内容问题；
- GNU 工具链（Linux）多数场景不踩这三个坑，但跨 mac 环境别依赖这个宽限；
- dex 之间内容分布不均（项目类集中在小 dex，主 dex 是稳定的库合并产物），逐 dex 全扫才可靠。

**关联**：GGUF-Server 设计文档04（APK 内容验证环节）、发现003（同为「验证手段可信度」主题）。
