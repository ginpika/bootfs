# 自治节点组网部署

每个 BootFS 节点保存完整文件、本机权威元数据和本地索引。没有运行时 etcd/MeiliSearch 依赖，新组网也不需要 Raft 多数派来完成本机读写。

1. 每台机器配置独立的 `TFS_ROOT_PATH`，启动后在 `/settings` 设置对等节点可访问的节点地址，保存并重启后再配对。
2. 启动相同的 BootFS 程序或容器，只需开放对应 HTTP/HTTPS 入口。
3. 在目标节点的系统仪表盘生成验证码，在另一端填写 URL 和验证码连接。
4. 要主动退出，在系统仪表盘选中当前节点，点击“退出集群”并确认。本机继续独立服务，之后可加入另一个集群。

无需预设 group-id、peers、共享 token 或手工密钥。默认 auto 模式可直接使用已有本地 embedded 数据。旧全局 Raft 模式保留日志和保护检查，需按[备份恢复](mononode-backup.md)迁移到新本地节点。

完整行为、成员协调节点可用性、离线通知、副本与 S3 范围见[动态组网](mononode-dynamic-cluster.md)和[本地内核](mononode-kernel.md)。历史外置 etcd 部署仅作为迁移参考，见[旧部署](cluster-deploy-legacy.md)。

## 跨节点详情/编辑出现 JSON 乱码

若本机文件正常、其他节点的详情或文本编辑报 `Unexpected token ... is not valid JSON`，需检查跨节点 HTTP 响应压缩。浏览器可能声明支持 `br/zstd`，而集群代理使用的 HttpClient 默认解码 gzip/deflate。旧代理曾透传浏览器的 `Accept-Encoding`，并无条件移除响应的 `Content-Encoding`，导致压缩字节作为普通 JSON 返回。

修复后的代理独立协商上游压缩格式，并让 HttpClient 解压；若上游仍返回未解码的压缩格式，则保留编码标记供下游正确处理。Linux、Windows 节点使用相同逻辑。更新接收浏览器请求并执行转发的节点（例如从 B 操作 A 文件时更新 B），重启后重新打开详情/编辑器即可；建议集群节点统一更新。无需转换原文件编码或关闭上游正常的响应压缩。
