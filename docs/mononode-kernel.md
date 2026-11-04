# BootFS 本地内核

先了解设计思路与核心概念，可阅读[去中心化设计](decentralization-zh-CN.md)（[English](decentralization.md)）。本文说明实际存储、发布与迁移边界。

BootFS 使用 Java 17、RocksDB 和 Lucene；不需要外部 etcd 或 MeiliSearch。新内核中，每个节点独立管理自己的完整文件、权威元数据和索引，组网不增加本机读写的多数派依赖。历史 `EtcdService`、`MeiliSearchService` 和管理 URL 为兼容门面，当前不代表外部服务。

## 运行

```sh
mvn test
mvn package -DskipTests
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --spring.profiles.active=dev \
  --tfs.root-path=/absolute/path/to/bootfs \
  --tfs.web-entrypoint=http://localhost:8181
```

新安装在所有 profile 默认关闭 SSO，普通业务配置在 `/settings` 管理；已有数据库配置不会被 profile 或环境变量覆盖。媒体处理另需 FFmpeg。见 [节点设置](node-settings.md)。

```sh
cp .env.example .env
# 填写其他节点可达的站点地址、SSO；需要 S3 时显式设置访问凭据。
docker compose up --build -d
```

默认 `BOOTFS_METADATA_MODE=auto`。空目录及原本的本地 embedded 目录启用同一套本地内核和动态组网，无需复制文件或切换共识日志。`dynamic`、`embedded` 显式值也采用本地内核。旧静态 Raft 配置仍可兼容运行，旧全局动态 Raft 目录需先[备份恢复](mononode-backup.md)到新本地节点；不会原地丢弃原共识身份。

## 权威边界

整文件保存在 `{root}/data`，对应的清单、原始文档、资源路由、S3 key、版本及删除记录都在本机 `{root}/kernel/metadata`。RocksDB 使用同步 WAL 和原子条件批次；文件字节与数据库之间通过持久发布意图恢复，不宣称跨文件系统/数据库的原子事务。

`{root}/kernel/search` 仅为本机资源建立可重建的 Lucene 索引。作者、描述、标签、漫画顺序等用户元数据保存在本机源文档，删除索引后仍可重建。`uuid` 是稳定节点身份；`kernel/cluster` 保存成员关系和通信授权，`kernel/security` 保存自动生成的身份凭据。

网络故障、成员协调节点离线或退出集群，不阻止本机 KV、文件和本地索引继续读写。集群级统一事务和全局单一 S3 key 空间不属于本模型。

## 资源生命周期

普通上传、漫画/图包和 S3 PUT 共用 `FileService` 发布流程：流式写暂存文件、校验、fsync、记录本机库存/路由/源文档、建立协议绑定，并恢复未确认的发布意图。

- 普通顶层资源可通过 UUID 作为默认 S3 key，原文件名作为名称。
- S3 PUT 的 key 属于当前节点，资源进入本节点列表和检索。
- 修改名称不改变 key；覆盖写创建新的不可变内容，再 CAS 切换本机 key。
- 删除写入本机墓碑，再收敛本机绑定、子资源、索引和字节；清理失败保留重试记录。
- 来自其他节点的资源修改转发到持有节点，不在本机代写其权威元数据。
- 元数据响应不确定时保留已落盘内容，避免错误回滚造成数据丢失。

公开画廊由本机 canonical 资源的公开状态生成；UUID 文件链接的可分享性和画廊收录是不同职责。TV 管理需要登录，公开播放入口保持已有行为。

## 副本策略

- 设置页写入策略 `single-local`：默认。本机完整文件和本机元数据耐久后发布。设置页副本份数 可驱动后台整文件副本制作。
- 设置页写入策略 `replicated`：发布前需 k 个不同节点确认（包括本机）。同步复制所需份数 k，0 时沿用 设置页副本份数，至少 2。
- 设置页准备超时（默认 30000ms） 控制每轮远端准备预算；本机 fsync 另有自身耗时。

副本保存整文件、完整必要元数据和来源快照。源节点提交发布后显式通知接收端建立本机清单和检索源；通知持久重试。准备状态不等于公开发布，不自动回收响应不确定的内容。

副本不足返回 503 和 `X-BootFS-Upload-Id`，保留原发布意图和耐久策略。离开集群不会自动降低 k；选择单机可立即发布时应使用默认 single-local 策略。

副本有自己的本机资源身份，并保存来源 ID。同组可达时跟随源元数据/删除；断网或退群后保留最后完整快照。本机编辑副本元数据会先建立独立分支，防止随后源同步覆盖本机修改。原 S3 绑定存入来源快照，不自动占用接收节点同名 key。派生缩略图、HLS 可从完整原始文件重新生成。

## 查询与重试

`/queryPageOffset` 和全文标签查询联合访问可达成员，不把远端结果写入本机元数据和索引。响应的 `partial`、`unavailableNodes` 标识结果不完整；资源列表会提示。`meta:local` 只查询本机，不等待其他节点。原索引管理 API 操作本机索引。

上传响应的 `X-BootFS-Revision` 是本节点的可见性边界，可用于本地搜索：

```text
GET /api/meilisearch/indexes/full-text/search?q=漫画&offset=0&limit=20&minRevision=123&timeoutMs=1000
```

普通上传、漫画和 S3 PUT 可携带 `X-BootFS-Request-Id`。同节点、同作用域、同内容会恢复原上传，不重复创建资源；不同内容冲突。请求身份不跨节点自动共享。

```text
GET /api/file/{uuid}/publication
```

返回 `PENDING/READY/DELETED/UNKNOWN` 及解析后的上传 ID。未找到已确认发布时不能把超时声称为成功。

## 组网与数据恢复

[动态组网](mononode-dynamic-cluster.md)描述验证码连接、退出后独立运行、加入其他集群、成员通知和授权撤销。新组网不需要 Raft 端口，也不把文件元数据提交到全局 Raft。

[离线备份恢复](mononode-backup.md)支持本地单节点数据，以及旧 embedded/共享 Raft 的逻辑恢复。新组网逐节点备份即可，不要求全组相同 revision。旧集群的整组恢复仍需提供全部库存所属根目录；文件字节缺失时不能凭元数据产生文件。

本机数据不因组网自动迁移或再均衡；节点失联后能否读取其文件，取决于可达节点是否保有完整副本。大量文件和节点的联合查询、硬件掉电和长期压力仍需独立环境验证；不宣称已完成这些生产验证。
