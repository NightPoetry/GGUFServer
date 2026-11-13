# 发现003 · GitHub拉源码四象限_codeload假Range恒全量_ghproxy真206_gzip-t终审

**一句话结论**：当日网络下 GitHub 源码获取呈四象限——github.com 主站被墙、codeload.github.com 可连但**不支持 Range（永远 200 全量）**、gh-proxy.com 支持**真 206** 可分段并发、hf-mirror 高速（实测 11MB/s）但只有 HF 内容；tarball 完整性的终审是 `gzip -t`（CRC32 全量校验），字节数对账不足为凭。

**日期**：2026-11-03

**环境**：MacBook（M 系，国内网络）与 Mac mini 同网；目标 llama.cpp v0.8.0 tag tarball（38,680,037 字节）。

**症状**：对 codeload 发 `curl -r S-E` 分段下载，各段字节数凑齐、总长精确匹配，`gzip -t` 却报 data stream error；重试次数越多坏得越花样。

**根因**：codeload 对 Range 请求**静默忽略、恒回 200 + 全量正文**。每一段实际收到的是同一股流的开头若干字节（或中途被 max-time/连接重置截断的前缀），拼接即错。gh-proxy.com 是对缓存体真做切片的（真 206），其字节可信。

**解法（可复现）**：
```bash
# 分段并发 + 各段断点续传（只信真 206 源）
UA="https://gh-proxy.com/https://github.com/<owner>/<repo>/archive/refs/tags/<tag>.tar.gz"
# 每段循环：HAVE=已收字节; curl -r $((S+HAVE))-$E -w '%{http_code}' -o tmp; 仅当 code=206 才 cat tmp >> part-N
# 合并后终审：
gzip -t all.tar.gz && echo OK
# 反面教材（三条都别犯）：
# 1) curl -C - 与 -r 并用：-C - 按文件大小续传、-r 从头拉，重试即重复追加，分段被撑爆
# 2) 对 codeload 直连做任何 Range/续传：恒 200 全量，拼出来必坏
# 3) 只对字节数不看 CRC：数对内容照样错
```

**验证**：llama.cpp v0.8.0 tarball 按「gh-proxy 8 分段 + 各段 206 守卫续传」重组，38,680,037 字节精确，`gzip -t` 通过（gzip CRC32 校验整流内容），解包构建全程无源码级异常。

**边界与坑**：
- gh-proxy 单连接速度波动大（99KB/s → 6KB/s），并发 6-8 段聚合 200-500KB/s 实用；
- 非.segment 源（jsdelivr 文件级、gitee/gitcode 镜像、hf-mirror 搜源码）当日均不可用或软 404；
- 单流下载（一次连接拉完）永远是最稳路径，分段是速度与可靠的折中；代理缓存若与源不一致，`gzip -t` 兜底检出。

**关联**：发现002（同日国际管线拥堵的另一面）、GGUF-Server 设计文档03。
