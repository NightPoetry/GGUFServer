# 发现004 · rjob的setsid包装器不兼容macOS_nohup变体替代

**一句话结论**：rjob 的 run 子命令在远端用 setsid 做脱离包装，而 macOS 没有 setsid——对 mac 主机挂后台任务会秒退（`setsid: command not found`）；macOS 端改用 nohup 变体（无 pkill 场景时无自杀风险），接口约定不变（日志文件 + 完成标记行）。

**日期**：2026-11-03

**环境**：远端 Mac mini（macOS，无 coreutils 的 setsid）；rjob 1.x（其包装器面向 Linux 设计）。

**症状**：`rjob run nightpoetry@mac-host /tmp/dl.sh --tag x --log /tmp/x.log` 后 poll 显示 `sh: line 12: setsid: command not found` + RJOB-STATUS=EXITED，任务根本没跑。

**根因**：setsid 是 Linux util-linux 组件，macOS 不随附；rjob 包装器未做平台分支。

**解法（可复现）**：
```bash
# macOS 远端的后台任务模板（保持 rjob 的接口约定：日志 + RC/完成标记行）
ssh host 'nohup bash /tmp/task.sh > /tmp/task.log 2>&1 & echo "BG-PID=$!"'
# 轮询：tail 日志 + grep 完成标记（如 TASK-RC=）+ pgrep 探活
```

**验证**：2026-11-03 在 mini 上以 nohup 模板先后挂起 GGUF 下载（1.6GB 完成）、llama.cpp 交叉编译等多任务，日志与完成标记全部按约定落盘。

**边界与坑**：
- nohup 模板只该用在**无 pkill 参与**的任务上；若任务链里有 pkill 自匹配风险，仍须遵守 rjob 的 stdin 通道纪律（模式串不进 cmdline）；
- macOS 上等价脱离可用 `nohup ... &` + `disown`，ssh 断开不影响已挂任务；
- rjob 若日后要支持 macOS，可在包装器里探测 setsid 缺失时退化为 nohup 路径。

**关联**：rjob SKILL.md「刻意不做」节。
