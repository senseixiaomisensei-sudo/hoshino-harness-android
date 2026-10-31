# 国内网络：不开代理时「请求未完成（TRANSPORT）：Connection error.」

## 根因
内核跑在 proot/Alpine（musl）里，**不走 Android 的系统解析器**，只读 proot 绑定的 `/etc/resolv.conf`。
旧代码在内核启动时把 `activeNetwork` 的 DNS 列表原样写入一次，且：

- 列表为空时（开机/更新后预热时网络未就绪、后台数据受限、activeNetwork 为 null）文件里没有任何 nameserver，musl 回落到 `127.0.0.1:53`，所有域名解析失败 → OpenAI SDK 报 `Connection error.`；
- 列表里只有不可用项（带 `%wlan0` 的链路本地 IPv6、开 VPN 时写进去、关 VPN 后已不可达的私有 DNS）时同样全部失败；
- 内核常驻，Wi-Fi ↔ 移动数据 ↔ VPN 切换后文件从不更新。

而设置页的模型列表/联网搜索探测走 Android 侧 OkHttp（系统 DNS），所以「探测正常、对话失败」；开 VPN 时 VPN 的 DNS 恰好在列表里，所以「开代理就好」。国内可直连的中转站也一样失败，因为失败发生在解析阶段，与服务商无关。

仓库里没有写死 8.8.8.8 / 1.1.1.1（只有 `tools/diag/boot.sh` 这个 CI 诊断脚本拷贝宿主机的 resolv.conf）。

## 修复（fix/m2.9-cn-network）
- `KernelNetwork.resolvConf`：系统 DNS 优先（IPv4 在前，去重，去掉回环/链路本地/scope id），不足 3 个用国内公共 DNS 补齐：223.5.5.5、119.29.29.29、180.76.76.76（musl 最多 3 个、并行查询，取最快答复）；`options timeout:3 attempts:2`。
- `registerDefaultNetworkCallback` 在网络切换/LinkProperties 变化时原地重写 resolv.conf（proot 是路径绑定，正在运行的内核下次解析即生效）。
- Node 启动参数 `--dns-result-order=ipv4first --network-family-autoselection-attempt-timeout=500`：很多国内网络有 AAAA 记录但 IPv6 路由不通。
- 设置 › 网络与代理：应用内 HTTP / SOCKS5 代理 + 「继承系统代理」（默认开）。HTTP 代理注入 `HTTPS_PROXY/HTTP_PROXY/ALL_PROXY` 和 `NODE_USE_ENV_PROXY=1`（Node 24 的 fetch 据此走代理）；SOCKS5 Node fetch 不支持，只给 shell 工具（`ALL_PROXY`）。`NO_PROXY=127.0.0.1,localhost,::1`，回环桥与本地 Ollama 不走代理。重启内核后生效。
- 传输错误提示：区分 DNS 失败 / 连接超时 / TLS / 被拒绝 / 被重置，并提示「该服务商在国内可能需要代理，可换国内可直连的中转站，或在设置里填代理」。

## 辅助请求排查
内核主链路上没有隐藏的境外请求：`web.ts` 搜索源（18s 超时）、`market.ts` 插件市场（30s）、`approval.ts` 审批模型（≤60s）都带超时且只在对应工具/功能被调用时发起；GitHub 更新与 ChatGPT 登录走 Android 侧，不阻塞对话；dsh 已设 `DSH_TELEMETRY_DISABLED=1`。

## 未验证
未上真机（国内不开代理）复现与验收。
