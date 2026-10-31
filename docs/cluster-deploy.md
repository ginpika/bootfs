# 内置 mononode 集群部署

当前版本每个节点运行同一个 BootFS 程序，内置 Raft、KV 和完整本地索引。无需运行 etcd 或 MeiliSearch。历史外置部署参考保留在 [cluster-deploy-legacy.md](cluster-deploy-legacy.md)，旧数据迁移见 [mononode-kernel.md](mononode-kernel.md)。

## 动态组网（新增）

需要独立节点相互发现、带数据合并、任意时期加入和安全下线时，使用 `bootfs.metadata.mode=dynamic`。不需要预配置 group-id/peers；相同配对密钥的节点默认进行局域网签名发现，也可在 `/cluster.html` 输入任一成员地址一键连接。冲突 key 保留递增版本，加入/退出不搬迁已有对象；失去多数时暂停权威读写。完整配置和实际边界见 [动态组网](mononode-dynamic-cluster.md)。

既有 embedded/静态 Raft 目录请先 [备份并恢复到新 seed](mononode-backup.md)，不能就地改 mode。下面保留显式静态集群方式，便于既有部署及固定拓扑验证。

## 本机三个节点

先构建 JDK17 jar：`mvn package -DskipTests`。在三个终端分别运行以下命令，将 n0/8181/目录替换为 n1/8182、n2/8183。所有节点使用相同 group-id 和 peers，各自使用不同空目录。

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --spring.profiles.active=dev \
  --server.port=8181 \
  --tfs.root-path=/absolute/path/to/cluster/n0 \
  --tfs.unique-id=n0 \
  --tfs.web-entrypoint=http://127.0.0.1:8181 \
  --bootfs.metadata.mode=raft \
  --bootfs.raft.node-id=n0 \
  --bootfs.raft.group-id=2cc57355-f9b4-4a1b-a060-66bdc7dac31b \
  --bootfs.raft.peers=n0=127.0.0.1:9191,n1=127.0.0.1:9192,n2=127.0.0.1:9193 \
  --bootfs.raft.bootstrap=true
```

首次初始化显式使用 bootstrap=true，重启时移除此参数。身份和成员列表持久保存；不要更换节点目录或 group-id 后冒充旧节点。成员间需要多数派才能启动业务读写，首次启动三个进程应接近同时进行。

默认 `single-local` 策略下，`tfs.copies=2` 继续异步复制。需要上传成功前已有两份耐久内容时，设置 `--bootfs.objects.write-policy=replicated --bootfs.objects.required-copies=2`；确认不足返回 503，保留待恢复发布。未复制内容的节点通过位置记录和代理/重定向访问拥有者，复制状态不能从 Raft 元数据的存在直接推断。

## 跨机部署边界

替换 peers 为各机私网地址，同时显式设置 `--bootfs.raft.allow-insecure-network=true`；HTTP 入口也要能互相访问。当前 Raft 传输未提供应用级 mTLS，须通过受保护的网络承载，不应直接暴露到公网。

启用 SSO 时，各节点还要配置相同的 `BOOTFS_CLUSTER_TOKEN` 以认证 `/t` 文件副本传输。Web 登录与 Raft 共识身份是不同职责。

静态模式固定 peers 必须保持一致，对现有磁盘随意改配置会被拒绝；动态加入/移除使用上面的独立 dynamic 模式。索引分片及数据自动再平衡尚未实现。
