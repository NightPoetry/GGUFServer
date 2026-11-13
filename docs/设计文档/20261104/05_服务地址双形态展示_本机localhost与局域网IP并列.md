# 05 · 服务地址双形态展示——本机 localhost 与局域网 IP 并列

> 版本：v1.0.0 初版（2026-11-04）｜关联：04（模型市场，同版本交付）
> 一句话：设置页「服务」组的 API 地址卡按调用方拆成两行同时展示——「本机调用（localhost）」= `http://localhost:<port>/v1`、「局域网其他设备（IP）」= `http://<LAN-IP>:<port>/v1`，两行都是 CopyableField 各自可复制；复制 curl 仍走 IP 形态。

## §0 设计原话（逐字，20261104）

> 「然后有用户表示，如果要本地调用的话，他想能直接用localhost来调用，而不是使用IP来调用，这个也满足他，就是如果是局域网内其他调用的话会使用IP，所以它显示IP。如果是局域网内调用的话，它会显示localhost。你可以在消息展示的时候为我们不是为了方便复制，有消息展示嘛，你可以在展示的时候同时显示这两种」

## §1 语义提炼

1. 端侧用户在本机调 API 时不愿抄 IP，想要 localhost——语义诉求是「按调用方给对形态」，不是二选一。
2. 落法 = 展示层并列双形态（原话「同时显示这两种」），不做智能切换（那需要判断调用方来源，App 侧做不到也不必要）：本机一行、局域网一行，用户按场景各取所需，各自可复制。
3. curl 示例保持 IP 形态：IP 从同一台手机自身访问也能通（回环路由），覆盖两种场景；localhost 行只为易读易抄。

## §2 算法 / 数据 / 接口

- `ServiceGroup`（SettingsScreen.kt）内：
  - 「本机调用（localhost）」行：`http://localhost:<port>/v1`（端口拓扑见 §工具插件规范 6.8——Gateway 公开端口，llama-server 内部端口对本行透明）
  - 「局域网其他设备（IP）」行：`http://<ip>:<port>/v1`，`ip = NetworkUtils.localIpAddress()`（既有函数；WiFi 关闭等拿不到时显示「手机IP」占位，与旧行为一致）
  - 两行均为 `CopyableField`，点按各自复制
  - 「复制地址」按钮复制局域网形态（多数场景），「复制 curl」不变（IP 形态）
- 无网络层改动：llama-server 恒绑 127.0.0.1 内部端口、Gateway 恒绑 0.0.0.0，两种形态本就都通，纯展示层增量。

## §3 关键决策 Why

1. **并列展示而非自动二选一**——App 无法感知调用方是谁；并列 + 各自复制把选择权给用户，实现零风险。别改成「检测本机 App 内展示 localhost、其他展示 IP」——入口是外部 app 的用户抄过去的，展示形态决定不了调用方。
2. **curl 维持 IP 形态**——curl 是「贴到别处用」的场景，IP 通吃（本机用 IP 亦可）；若贴 localhost 反而只能本机用。别动它。
3. **嵌入实例（8101）不在本卡展示范围**——该卡是主服务卡；嵌入服务有自己的 curl 卡（含 CopyableField），如需同款双形态另行按本档扩。

## §4 验证

- JVM 单测 61 项全绿（本改动为纯展示层字符串拼接，无独立测试；编译经 assembleDebug RC=0）。
- 真机验证待回填：运行态两行地址的实际显示与各自复制；无 WiFi 时 IP 行的占位显示。

## §5 文件索引

- `android/app/src/main/java/com/ggufserver/app/ui/SettingsScreen.kt`（ServiceGroup）
- `android/app/src/main/java/com/ggufserver/app/util/NetworkUtils.kt`（localIpAddress，既有）

## §6 版本履历

- v1.1.19（versionCode 30，2026-11-04）：初版（与模型市场转正同包交付，见设计文档04）。

## §7 重建清单

- 语义：地址卡双行（localhost 行 + IP 行）各自可复制；curl=IP 形态。
- 接口：无新增网络接口，纯展示。
- 界面：服务组内「API 地址（OpenAI 兼容）」标题下两行小标题+两行 CopyableField。
- 验证：真机运行态目验（待回填）。
- 刻意不做：不自动判断调用方来源；不给嵌入实例卡扩双形态（有需求另立档）。
