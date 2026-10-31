# 自治节点组网部署

每个 BootFS 节点保存完整文件、本机权威元数据和本地索引。没有运行时 etcd/MeiliSearch 依赖，新组网也不需要 Raft 多数派来完成本机读写。

1. 每台机器配置独立的 `TFS_ROOT_PATH`，并设置对等节点可访问的 `TFS_WEB_ENTRYPOINT`。
2. 启动相同的 BootFS 程序或容器，只需开放对应 HTTP/HTTPS 入口。
3. 在目标节点的系统仪表盘生成验证码，在另一端填写 URL 和验证码连接。
4. 随时通过系统仪表盘的“连接 BootFS 节点”对话框退出；本机继续独立服务，之后可加入另一个集群。

无需预设 group-id、peers、共享 token 或手工密钥。默认 auto 模式可直接使用已有本地 embedded 数据。旧全局 Raft 模式保留日志和保护检查，需按[备份恢复](mononode-backup.md)迁移到新本地节点。

完整行为、成员协调节点可用性、离线通知、副本与 S3 范围见[动态组网](mononode-dynamic-cluster.md)和[本地内核](mononode-kernel.md)。历史外置 etcd 部署仅作为迁移参考，见[旧部署](cluster-deploy-legacy.md)。
