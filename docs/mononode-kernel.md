# 内置 mononode 内核：运行、迁移与当前边界

本次实现保留 Java 17 和现有 Web/S3 入口，将运行时 etcd/MeiliSearch 依赖替换为内嵌 RocksDB、Apache Ratis 3.2.0（传输 bundle 固定为 ratis-thirdparty-misc 1.0.11）和 Lucene 9.12.3。Lucene 9.x 避免将 Java 21/框架升级与数据迁移混在一次变更中。`EtcdService`、`MeiliSearchService` 和旧管理 URL 暂时保留为兼容门面，实际数据由内置内核处理。旧 etcd 与 MeiliSearch 客户端 SDK 也已移除。

## 快速运行

需要 JDK 17 和 Maven；Maven wrapper 原仓库缺少 `.mvn` 配置，因此使用系统 Maven。媒体处理另需 FFmpeg。

```sh
mvn test
mvn package -DskipTests
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --spring.profiles.active=dev \
  --tfs.root-path=/absolute/path/to/new-bootfs \
  --tfs.web-entrypoint=http://localhost:8181
```

`dev` 默认关闭 SSO，适合可信本地环境。生产默认启用 SSO；Hostid 配置通过 `SSO_SERVER_URL`、`SSO_INFO_URL` 注入。新目录可以直接启动；旧目录有迁移检查，见下文。

容器本地构建：

```sh
cp .env.example .env
# 设置站点入口、SSO；需要 S3 时填写显式凭据并启用 S3_ENABLED。
docker compose up --build -d
```

只有一个 BootFS 服务和一个数据卷，挂载 `/data`。JVM 参数使用 `JAVA_TOOL_OPTIONS`。已有 `/app` 旧卷不能直接当成一个新的 `/data` 空目录：先备份和迁移，或显式设置卷及 `TFS_ROOT_PATH` 指向同一份数据。

## 数据职责

- `{root}/data`：对象内容及已有媒体衍生文件。
- `{root}/uuid`：持久节点身份；不能随意更换。
- `{root}/kernel/metadata`：权威 KV、原始资源文档、版本和删除状态。
- `{root}/kernel/raft`、Raft 身份文件：启用 Raft 后的共识日志和快照。
- `{root}/kernel/search`：可重建的 Lucene 投影，实际子目录由索引引擎管理。
- `db.json`：旧本地清单导入源，导入后不继续写入，也不删除。

节点本地库存按 `/nodes/{nodeId}/catalog/` 命名。文件路径按本节点根目录重建，不采用另一台机器的绝对路径。全局文件路由使用 `/files/`，原始索引文档使用 `/documents/{index}/{uuid}`，S3 映射使用 `/s3/{bucket}/{key}`。索引中的作者、描述、漫画顺序和其他原始字段也持久保存。

## 统一的资源生命周期

普通上传、漫画/图包和 S3 PUT 共用 `FileService` 发布流程：流式写暂存文件、校验、持久化、发布库存/路由/原始文档、触发缩略图及复制。

- 普通顶层资源在启用 S3 时，以 UUID 作为默认 bucket 中的 key；原始文件名作为资源名称。
- S3 PUT 使用请求的 key，资源立即出现在普通列表/搜索中。
- 修改资源名称不改变 S3 key；S3 key 是独立的访问绑定。
- S3 覆盖先写不可变新内容，再 CAS 切换绑定；冲突返回 409，失败输入保留旧对象。
- 从任一入口删除，统一记录墓碑并清理其他入口、索引、文件及缩略图。副本/子资源所属节点通过墓碑收敛；物理清理失败留下重试意图。
- 元数据提交响应不确定时保留已落盘内容及待恢复发布记录，避免误删可能已经发布的内容。

画廊以 `full-text` 资源文档为权威来源，`image-host` 是公开资源的兼容视图，名称、标签与公开状态随资源更新。旧画廊独有字段合并入资源文档，冲突原文保存在 `legacyImageHost` 中。`POST /publicAccess/{uuid}` 默认收录画廊，`?enabled=false` 撤销收录；该标记控制画廊成员资格，**不构成对象访问 ACL**。已有 `/f/{uuid}`、`/p/{uuid}` 等 UUID 分享链接继续可用，撤销画廊收录不会撤销这些链接。

启用 SSO 时，TV 管理页面、视频清单及播放列表修改/刷新需要登录；TV 播放页、状态、播放列表流和 TS 分片的 GET/HEAD 继续公开。

对象内容的写入策略与 Raft 元数据多数派独立：

- `BOOTFS_OBJECT_WRITE_POLICY=single-local`（默认）：本地内容 fsync 后发布；保留旧 `TFS_COPIES` 期望副本数的异步修复行为，不将它自动升级为同步保证。
- `BOOTFS_OBJECT_WRITE_POLICY=replicated`：发布前须得到 k 个不同持久节点的确认，**包括本地节点**。`BOOTFS_OBJECT_REQUIRED_COPIES=k` 显式指定；0 时沿用 `TFS_COPIES`，必须至少为 2。按持久 nodeId 去重，校验确认中的对象身份、长度、SHA-256；URL 别名不算新节点。各 nodeId 必须代表真实独立存储，当前尚无物理故障域发现。
- `BOOTFS_OBJECT_PREPARE_TIMEOUT_MS=30000` 控制每轮远程准备的总传输预算。元数据 RPC 和本地 fsync 另有自身等待，不承诺整个请求的硬实时上界。

接收节点先校验、fsync 并持久记录准备状态，不立即建立公开库存/检索/S3 路由。发送节点收齐确认后才发布普通库存、路由、源文档和 S3 绑定，并在对象元数据保存已满足的策略与确认。接收节点随后根据原对象发布状态登记可读副本。准备副本没有基于超时的自动回收；确认不确定时保留字节，墓碑可清理它们。确认记录是某次持久写入的证据，不是持续健康证明；后台长期巡检和副本补齐仍需后续实现。

确认不足返回 HTTP 503 和 `X-BootFS-Upload-Id`，不发布新普通资源/新 S3 绑定，已有 S3 代仍可读取。持久意图保存原 k 策略，重启后继续；降低运行配置不会降低已开始上传的要求。元数据回复不确定同样不表示成功，若意图已提交则后台恢复。可经 `GET /api/file/{uuid}/publication` 查询 `PENDING/READY/DELETED/UNKNOWN`；`UNKNOWN` 表示未找到已提交意图或发布，不能声称上传完成。

普通上传、漫画与 S3 PUT 可携带 `X-BootFS-Request-Id`（1–128 位字母/数字/下划线/连字符）重试。该 token 按接收 nodeId 和操作范围生成稳定对象身份；组网发生 UUID 冲突时保存本节点上传别名，状态查询返回 resolvedUploadId；同节点同 token、相同内容会继续原发布或返回原结果，不重复创建资源，不同内容冲突。普通上传和漫画分别有作用域，S3 按 bucket/key 作用域。无 token 的新请求仍可创建新资源；跨节点重试尚不共享请求身份，已显式删除/退役的 token 不可重新用于新对象。漫画页面采用稳定身份，原归档和每页都经过相同耐久检查。

当前没有跨对象内容和元数据的原子事务，也不声称已完成真实机器掉电验证。节点报告 fsync 成功是否对应硬件持久介质，仍取决于文件系统和存储设备。

## 检索与可见性

每个节点使用完整 Lucene 索引。对源文档更新使用 CAS；删除墓碑防止旧任务复活资源，查询也排除已删除的主资源、子资源和副本。提供中文分析、全文、标签、分页、大小/时间/名称排序及 UUID 稳定次序。

索引刷新按元数据 revision 比较和源文档快照差异执行，后台默认 500 ms，也会在查询前追赶。当前每次变更后的投影协调需要扫描完整源文档集合；这不是最终的增量 changefeed 实现。

普通上传响应头 `X-BootFS-Revision` 可用作随后搜索的可见性下限：

```text
GET /api/meilisearch/indexes/full-text/search?q=漫画&offset=0&limit=20&minRevision=123&timeoutMs=1000
```

搜索可选 `sort`、`descending`；等待超时返回 `pending=true`，不会声称已看到所要求的版本。版本屏障代表至少包含此前变更，不代表历史快照查询。

## 旧部署迁移

**请先备份每个节点的完整根目录、etcd 和 MeiliSearch 数据，并停止所有写入者。首次验证在备份副本上进行。** 工具不连接、修改或删除当前部署；必须显式运行导出与导入。

```sh
export MEILISEARCH_MASTER_KEY='your-key'
python3 tools/export-legacy.py \
  --etcd http://localhost:2379 \
  --meili http://localhost:7700 \
  --writes-stopped \
  --output /absolute/path/to/legacy-export.json
```

该脚本使用 Python 标准库，通过 etcd HTTP v3 gateway 和 MeiliSearch 原始文档 API 导出。需要 endpoint 可访问；有 etcd 用户认证/mTLS 的旧部署需先通过受保护的访问入口导出，当前脚本没有实现其认证协议。

导出包含 `/files/`、`/s3/`、`/properties/` 以及所有索引的完整文档，不导入旧在线节点列表。原始 `db.json` 留在各节点根目录。

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --spring.profiles.active=dev \
  --tfs.root-path=/absolute/path/to/backed-up-old-root \
  --bootfs.migration.import-file=/absolute/path/to/legacy-export.json
```

导入先检查版本和已有键冲突，支持相同导出恢复重试。首次导入后根据节点清单补齐缺少的文档和路由；已导入的丰富文档优先。节点身份、UUID 和 S3 key 保留。

只有 `db.json`、没有外部服务备份时，必须显式选择 `--bootfs.migration.allow-catalog-only=true`。这种恢复不能找回搜索服务独有的作者、描述、漫画编排或旧 S3 别名；系统不会默认把它当成完整迁移。

新后端开始接收写入后，不能直接切回旧后端数据。回退需要停写并重新导出、核对和导入；当前没有自动反向迁移工具。

`purge-user-data.sh` 仍是旧部署清理工具，检测到内置 kernel 目录后会拒绝执行，避免只删除内容却遗留共识和索引状态。

## Raft 集群

见 [集群部署](cluster-deploy.md)。第一版支持显式静态成员初始化、重启、leader 故障切换、快照压缩和落后节点安装快照，所有权威读取经过线性一致性读屏障。失去多数派时权威读写失败。

默认 `bootfs.metadata.mode=embedded` 用于单机。启用 `raft` 使用相同的业务接口。已经存在本地非 Raft 数据的内核目录不能直接改为 Raft；请通过 [内核备份恢复](mononode-backup.md) 导出一致逻辑备份，恢复到新根目录/新集群。旧外部服务迁移脚本仍单独保留。

`bootfs.metadata.mode=dynamic` 新增 [动态组网](mononode-dynamic-cluster.md)：独立节点发现、带版本合并、新节点在线加入、主动退出及重启返回。组网不重分布已有对象。静态 `raft` 模式继续保护固定 peer 配置，不能直接改 peers。索引分片、多 Raft 组和自动副本再平衡仍未实现。默认仅允许 loopback 通信；跨机须显式 `bootfs.raft.allow-insecure-network=true`，并使用受保护的私网/隧道，当前未提供 Raft mTLS。

HTTP 文件副本传输使用 `BOOTFS_CLUSTER_TOKEN`；启用 SSO 时需要各节点配置一致的非空 token。token 不能替代传输加密。

## 验证范围与扩展边界

自动化测试覆盖真实 RocksDB 恢复/CAS/批次/快照、三节点提交/故障切换/重启/快照追赶/少数派拒写、Lucene 重建/中文/分页/标签/并发源更新/墓碑，以及普通和 S3 双向生命周期。原有应用级生命周期 MockMvc 测试关闭 servlet filters 以聚焦存储互通；鉴权另测，另有启用完整 servlet filters 的真实 SigV4/S3 字节校验集成测试。S3 的 MIME 类型只描述对象内容，表单过滤器不会提前消费 S3 请求体。

尚需独立环境验证：真实掉电、长期网络隔离、长时间压力、容器多架构运行及原生产数据迁移。Raft 全前缀扫描先建立一致性屏障再扫描本地完整副本；元数据规模扩大前仍需要分页扫描和增量投影，随后再推进索引分片与多 Raft 组。Raft 请求结果缓存当前保留在持久状态中，后续需要安全的客户端会话确认与回收策略来控制其增长。

## 传输兼容性修复

新增恢复/合并测试复现了 Ratis 原传输 bundle 的 gRPC HTTP/2 帧损坏：版本检测可误读 Spring 所带的旧 Netty 资源，选择不适用于 shaded Netty 的累积器路径。已显式固定 `ratis-thirdparty-misc=1.0.11`（gRPC 1.77.1、Netty 4.1.130），避免依靠类路径版本猜测。恢复与动态整组重启用例覆盖了原先可重复失败的报文。参见 [gRPC 修复讨论](https://github.com/apache/spark/pull/55081)；实际依赖版本以 pom 和构建依赖树为准。
