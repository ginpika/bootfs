# 系统仪表盘

在任意节点打开 `/dashboard`，点击节点卡片即可切换到该节点的系统资源、请求、流量和运行时长。选中的节点记录在 URL 的 `node` 参数中；每 15 秒自动刷新，刷新时保留选择。节点不可达时显示不可用状态，不显示其他节点的数据。

每个节点提供以下信息：

- JVM 进程驻留内存（RSS）及其占宿主机物理内存的百分比。
- JVM 堆已用、已提交、上限和非堆已用内存。RSS 包含堆、非堆及本地内存等，不能直接用堆内存代替。
- CPU 型号、架构、宿主机逻辑 CPU 数、JVM 可用 CPU 数；宿主机内存总量、已用和可用量；cgroup 内存用量及限额（如可采集）。cgroup 用量可能包含同组其他进程和缓存，并非 JVM RSS。
- Docker、systemd、其他容器或直接运行环境，以及操作系统、Java、虚拟机和 PID。运行环境按容器标记、cgroup 和进程环境变量识别。
- 最近 50 条请求的请求者 IP，支持 IPv4 和 IPv6。

Linux 上通过 `/proc` 采集 RSS、宿主机内存及 CPU 信息，通过标准 `/sys/fs/cgroup` 挂载采集 cgroup v1/v2 内存资源。宿主机占比的分母使用 `/proc/meminfo` 中的物理内存总量，容器限额单独展示。无法读取的指标显示 `--`；非 Linux 平台提供 JVM 和操作系统支持的指标，不将堆内存或虚拟内存当成 RSS。

请求 IP 使用 Servlet 的 `getRemoteAddr()`，不直接信任客户端传入的 `X-Forwarded-For`。反向代理部署时，应在服务端配置可信代理，例如使用 `server.forward-headers-strategy=native` 和适合部署网络的 `server.tomcat.remoteip.internal-proxies`，由容器解析代理链后提供客户端地址。

## Caddy → Docker 的真实客户端 IP

Caddy 默认设置 `X-Forwarded-For`、`X-Forwarded-Proto` 和 `X-Forwarded-Host`，并忽略不可信客户端传入的这些头，通常不需要手写 `header_up`。[Caddy 官方文档](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults)

BootFS 默认关闭代理头解析。若容器看到 Caddy 的连接来源为 `172.18.0.1`，在当前节点 `/settings` 的「节点与反向代理」中选择 `native`，可信代理填写 `172[.]18[.]0[.]1`，保存后重启。数据库设置在下次启动绑定 Tomcat 之前加载，Compose 不再注入这些环境变量。

地址正则必须匹配 BootFS 实际看到的代理来源。更换 Docker 网络后地址可能变化；限制后端直连，不要将所有来源设为可信。本机免登录使用解析前的原始连接，代理解析不会使网关成为本机。

宿主机上的 Caddy 可使用普通反代配置：

```caddyfile
files.example.com {
    reverse_proxy 127.0.0.1:8181
}
```

这种部署应把 Compose 端口映射设为 `127.0.0.1:8181:8181`，或通过防火墙仅允许可信代理访问后端。Docker 可能把绕过 Caddy 的连接也转换为网关地址，因此仅匹配 `172.18.0.1` 不能代替后端端口访问控制。若 Caddy 同样运行在容器中，使用专用 Docker 网络和 `reverse_proxy app:8181`，并将可信代理地址改为 Caddy 容器的固定地址。集群成员需要的后端访问应通过受控网络保留。

如果 Caddy 前面还有 CDN/其他代理，需要在 Caddy 侧配置该上游的 `trusted_proxies`；否则只能还原到连接 Caddy 的上一跳。[Caddy 可信代理文档](https://caddyserver.com/docs/caddyfile/options#trusted-proxies)

从外部访问一次服务，再刷新最近请求，确认新增条目的 IP 已变化。直接在宿主机或容器内访问，显示本机/内网 IP 属于正常情况。

`/api/dashboard/node-stats` 仅构建本机快照；`/api/dashboard/stats` 使用已登记成员地址和现有集群签名认证获取各节点快照，不递归调用集群聚合接口。旧节点缺少新增字段时，页面会提示升级。

验证命令（JDK 17）：

```sh
mvn -Dtest='SystemStatsServiceTest,TrafficStatsFilterTest,ForwardedClientIpIntegrationTest,FederationIntegrationTest#dashboardFromEitherNodeContainsIndependentPeerStatsAndHandlesAnOfflinePeer' test
node --test src/test/js/dashboard.test.cjs
```
