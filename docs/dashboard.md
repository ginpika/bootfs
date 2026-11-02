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

`/api/dashboard/node-stats` 仅构建本机快照；`/api/dashboard/stats` 使用已登记成员地址和现有集群签名认证获取各节点快照，不递归调用集群聚合接口。旧节点缺少新增字段时，页面会提示升级。

验证命令（JDK 17）：

```sh
mvn -Dtest='SystemStatsServiceTest,TrafficStatsFilterTest,FederationIntegrationTest#dashboardFromEitherNodeContainsIndependentPeerStatsAndHandlesAnOfflinePeer' test
node --test src/test/js/dashboard.test.cjs
```
