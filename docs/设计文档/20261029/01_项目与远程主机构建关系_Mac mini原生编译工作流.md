# 设计文档 1：项目与远程主机构建关系_Mac mini原生编译工作流（v1.0.0）

> 20261029 · 立档。一句话：**项目与工具链留在 Mac mini 本机盘上不动，MacBook 侧通过 SSH 一键驱动 mini 原生编译（单测 9s），终结「SMB 挂载 + 本机编译」的跨网慢速与远端掉线即瘫痪**。

## §0 设计原话（逐字，20261029）

> 「你看看我们能不能像FasFox网站那样本地再哪里，然后正在编译的内容在我们的本地」（"本地再哪里"=本地放在哪里）
>
> 「不是网站，是项目与远程主机的关系」
>
> 「那些基本上不用动的可以在远端，或者你直接用SSH控制远端也行，因为用远端的但是在本机编译会很慢，你看看」

## §1 语义提炼

1. **项目存放关系定版**：GGUF-Server 项目本体（`/Volumes/UserData/Projects/LLM/手机部署小模型服务器`）与编译工具链（`/Volumes/SystemData/gguf-toolchain`）留在 Mac mini（192.168.3.124，M4/16GB）**不动**——这两条路径是 mini 同一块 1.9T 内置卷（disk7）上的两个卷，对 mini 是纯本地盘。基本上不用动的东西（git 历史、release/debug 归档 APK、keystore、模型）全部原位保留。
2. **编译发生在 mini**：所有 Gradle/NDK 构建通过 SSH 在 mini 上原生执行；MacBook 侧**禁止**对着 SMB 挂载跑 Gradle（跨网 IO 慢一个数量级 + Gradle 文件锁在 SMB 上是知名坑）。
3. **一键入口**：新增 `scripts/mini-build.sh`（在 mini 上执行），MacBook 侧单条 ssh 命令驱动，任务参数化：test（默认，两组打包前自检）/ assemble / debug / release / deploy / stop。
4. **与 FasFox 模式同构**：干活机器原生访问工作副本，另一台机器只做文件交换/存档——区别仅在 GGUF-Server 的「干活机器」是 mini 而非 MacBook（FasFox 是本盘开发、外部盘裸仓库存档；本项目反向，因为工具链与项目已经在 mini 上且 mini 空闲算力更强）。
5. **MacBook 侧本机编译路线否决**：本机磁盘 97% 满（14Gi 可用），工具链+缓存需 10GB+，且装好后仍要跨 SMB 取项目文件——不走。

## §2 算法 / 数据 / 接口

### 接口（唯一入口）

```
ssh nightpoetry@192.168.3.124 '/Volumes/UserData/Projects/LLM/手机部署小模型服务器/scripts/mini-build.sh [<task>]'
```

| task | 行为 | 内部实现 |
| --- | --- | --- |
| `test`（默认） | 打包前两组自检 | `source env.sh` → `$GRADLE -p android :app:testDebugUnitTest` → `node scripts/check-plugins.js` |
| `assemble` | 只编 debug APK，不归档不部署 | `$GRADLE -p android assembleDebug` |
| `debug` | 打调试包并按版本号归档到 debug/（同版本号覆盖） | `exec ./scripts/debug.sh` |
| `release` | 打正式包（versionName/versionNote 未升会被 release.sh 拒绝） | `exec ./scripts/release.sh` |
| `deploy` | 手机插在 mini 上时：装 debug 包 + 推模型 | `exec ./deploy.sh` |
| `stop` | 停远端 Gradle daemon（释放 mini 16GB 内存） | `exec "$GRADLE" --stop` |

### mini-build.sh 环境要点

```
非登录 ssh shell PATH 不含 /opt/homebrew/bin（node 所在地）→ 脚本内 if [ -d ] 补 PATH
source /Volumes/SystemData/gguf-toolchain/env.sh   # JAVA_HOME/GRADLE/ANDROID_NDK/PROJ 全在其中
cd "$PROJ"                                          # env.sh 里 PROJ 已指向本项目
debug.sh / release.sh / deploy.sh 均自 source env.sh 或路径自包含 → 纯 exec 委托
```

### 数据/文件流

- 构建读写全部发生在 mini 本机盘：源码（UserData 卷）→ Gradle 缓存（`/Volumes/SystemData/.gradle-gguf`，1.7G 热缓存）→ 产物归档（`release/`、`debug/`，同在 UserData 卷）。
- MacBook 侧通过 SMB 挂载**只读**查阅源码/文档、**写**文档类小文件；不走构建流量。
- git origin = GitHub（`NightPoetry/GGUFServer`），与 mini 无依赖。

## §3 关键决策 Why

- **Why 项目不迁到 MacBook**：真源码虽小（173 个 tracked 文件），但本机磁盘 97% 满装不下工具链；且迁移要动 keystore、归档、路径约定（deploy.sh/release.sh 内硬编码 PROJ 路径）三处，收益为零——mini 上编译本来就是历史事实（Gradle 输出全 UP-TO-DATE 证明 7 月的版本就是在 mini 编的）。
- **Why 用 SSH 而不是继续 SMB 挂载跑本机 Gradle**：实测对照是本设计的立项依据——mini 原生 Gradle 单测 9 秒；SMB 上 Gradle 慢一个数量级且文件锁出过知名坑。别改这里：**不要试图在 MacBook 上对着 /Volumes/UserData 挂载跑 assembleDebug**，会重新打开性能与锁问题。
- **Why mini-build.sh 自己补 /opt/homebrew/bin**：ssh 非登录 shell 的 PATH 只有 `/usr/bin:/bin:/usr/sbin:/sbin`，`node`（check-plugins.js 依赖）在 homebrew 路径下，不补则 test 任务必挂。
- **Why 任务委托用 exec**：debug.sh/release.sh/deploy.sh 自含 env 加载与错误处理，exec 让退出码直通 ssh，MacBook 侧 `$?` 即真实构建结果。
- **Why deploy 任务保留在 mini 侧**：deploy.sh 的 adb 路径写死 /Volumes/SystemData，手机插 mini 时天然可用；手机插 MacBook 时在 MacBook 上跑 ./deploy.sh（需两条 SMB 卷挂载着，仅传输 APK/模型，无构建流量，可接受）。

## §4 验证

- [x] **SSH 免密通路**：`ssh -o BatchMode=yes nightpoetry@192.168.3.124 'echo OK'` → OK（20261029 实测）
- [x] **远端环境自检**：M4 / 16GB RAM；SystemData+UserData 同属 disk7（1.9T 内置卷，32Gi 可用）；Java 17.0.19 / node v25.6.0 / adb 1.0.41 / Gradle 缓存 1.7G 热缓存（20261029 实测）
- [x] **原生编译速度**：mini 上 `:app:testDebugUnitTest` **BUILD SUCCESSFUL in 9s**，22 tasks 全 UP-TO-DATE（20261029 实测；SMB+本机路线无对照数字，按跨网 IO 定性判慢，未实测对照——刻意不做对照，避免在 SMB 上再跑一次构建）
- [x] **一键入口全链路**：`ssh mini '.../scripts/mini-build.sh test'` → JVM 单测通过 + 插件离线自检「✓ 全部通过」（20261029 实测）
- [ ] **debug/release 任务实弹**：未验证（下次真发版时走 mini-build.sh debug/release，产物落 release//debug/ 归档后回填）
- [ ] **deploy 任务实弹**：未验证（需手机插 mini）

## §5 文件索引

| 文件 | 动作 | 内容 |
| --- | --- | --- |
| scripts/mini-build.sh | 新增 | 远端编译唯一入口（本表 §2 接口），自补 homebrew PATH + source env.sh + 任务分发 |
| docs/打包流程.md | 修改 | 新增「远程编译（SSH 到 Mac mini）」节：一句话工作关系 + 一条命令 + 任务表 |
| docs/设计文档/20261029/01_本档 | 新增 | 本档 |
| docs/设计文档/README.md | 新增 | 立档体系编写规范（init 自动写入，本项目首次建体系） |
| scripts/git-hooks/pre-commit | 新增 | 三门禁（文档门禁/原话门禁/冲突追溯），core.hooksPath 已指向 |

不改动：`scripts/debug.sh`、`scripts/release.sh`、`deploy.sh`、`rebuild.sh`、`/Volumes/SystemData/gguf-toolchain/env.sh`（mini 侧现状即接口，全部原样复用）。

## §6 版本履历

- v1.0.0（20261029）：立档——定版「项目与工具链留 mini、MacBook SSH 驱动原生编译」的关系；新增 scripts/mini-build.sh；打包流程文档同步；test/assemble 路径实测通过。

## §7 重建清单（照本篇重塑所需的最小信息集）

1. **语义**：项目+工具链住在 mini（192.168.3.124），编译只在 mini 原生跑，MacBook 是遥控器+文档编辑台；归档/历史/keystore 原位不动。
2. **数据**：无新数据结构；文件流=mini 本机盘闭环（源码→.gradle-gguf 缓存→release//debug/ 归档）。
3. **规则**：MacBook 禁跑 SMB 构建；mini-build.sh 补 homebrew PATH；任务经 exec 委托保退出码；debug 同版本覆盖、release 必须升版本号（沿用打包流程既有规矩）。
4. **接口**：§2 那一条 ssh 命令 + 六个 task。
5. **界面**：无 UI。
6. **验证**：§4 前四项已实测；debug/release/deploy 三项留待下次真发版回填。
7. **刻意不做**：①不迁移项目到 MacBook（磁盘装不下+迁移面大收益零）②不做 SMB 对照实测（没必要再喂一次慢路径）③不改 env.sh/debug.sh/release.sh/deploy.sh（自含，委托即可）④不搭 CI 远端触发/Webhook（单人多机场景 ssh 一条命令已够，机械性重复出现再升级成工具）。
