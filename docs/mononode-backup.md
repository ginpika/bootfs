# 内核备份、校验与恢复

这是一套**维护窗口内的离线逻辑备份**。它覆盖权威 KV 的同一版本边界、各节点的全部 `data` 文件清单/长度/SHA-256、对象内容以及原始检索文档。Lucene、旧 Raft 日志、旧请求会话和共识身份不作为恢复输入，索引由权威文档重建。

## 导出

先停止所有写入者和 BootFS 进程；Raft 节点应在停机前追上同一状态。需要全部库存所属节点的根目录。工具会锁住各 RocksDB，要求各节点的用户元数据和 revision 完全相同，并拒绝未完成的发布、漫画组装及旧副本接收任务。`--writes-stopped` 是操作员对停写的显式声明，不能把在线 follower 的文件复制当成一致备份。

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --bootfs-maintenance=backup --writes-stopped \
  --source=/backup-copy/n0 --source=/backup-copy/n1 --source=/backup-copy/n2 \
  --output=/backups/bootfs-2026-10-31
```

单机只提供一个 `--source`。embedded、静态 Raft 和 dynamic 的活动数据代都支持导出。工具在 Spring 启动前运行，不启动 HTTP、清理、媒体处理或迁移任务；不会对源数据进行逻辑修改。目的目录必须不存在且位于所有源根目录外。先写临时目录并同步，完成后原子发布目的目录；失败不会留下一个冒充成功的备份目录。

导出会核对每份存活库存中的对象长度和已有的权威 SHA-256；缺失对象、S3/全文文档缺少库存、库存所属节点未提供、节点版本不一致或符号链接都会阻止导出。已有虚拟 HLS 资源允许没有原始零字节对象，但必须有播放列表。原来没有权威 SHA-256 的历史对象也会在备份中计算摘要；这能检查此后的备份损坏，不能证明历史对象从未损坏。

所有 `data` 文件都保留，包括缩略图、HLS、TV 配置和未注册的旧内容。内容按 SHA-256 去重，清单保留原节点和相对路径。未知目录不会被追踪到根目录之外。`db.json`、旧共识目录和部署凭据不在这套逻辑恢复数据中；需要保存原始运行环境时，另保留每个节点完整根目录的离线副本。

## 校验

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --bootfs-maintenance=verify --backup=/backups/bootfs-2026-10-31
```

校验清单摘要、元数据摘要/版本边界、全部 blob 的长度和 SHA-256、路径安全，以及库存到 S3/原始文档的引用。摘要用于发现损坏，不是带独立信任根的数字签名。

## 恢复为一个新 seed

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --bootfs-maintenance=restore --backup=/backups/bootfs-2026-10-31 \
  --output=/new/seed --node-id=seed --endpoint=http://127.0.0.1:8181
```

恢复只接受不存在的新目录。先验证完整备份，再写文件和 `restore-plan.json`，最后原子发布新根目录。所有活跃库存归入新 seed，自定义 KV、UUID、父子/副本关系、标签来源、漫画顺序、S3 key、冲突版本、墓碑和自定义文档字段保留；节点路由及已知的媒体 URL 重写为新入口。本节点目前只有一份内容，因此重建后的路由不继承旧的 k 副本确认。

不再属于活跃库存的文件保留到 `data/.recovered/{原节点}/{原路径}`，不会因冲突 UUID 对应旧孤儿文件而覆盖当前内容。多个节点同一路径的活跃内容必须一致，否则恢复拒绝发布。不同节点的 TV 播放列表若冲突，也会拒绝自动合并，原备份中的各份配置仍完整可取。

恢复目录在第一次启动时必须显式导入计划：

```sh
java -jar target/bootfs-v1.0.0-beta.1.jar \
  --spring.profiles.active=dev --tfs.root-path=/new/seed \
  --tfs.web-entrypoint=http://127.0.0.1:8181 \
  --bootfs.restore.plan=/new/seed/restore-plan.json
```

此例恢复为 embedded。计划只允许导入空权威状态，使用持久认领和原子批次；丢失提交响应可用同一计划重试。完成标记已存在时不会再次覆盖后续业务写入。不要删除标记来强制重放，也不要将旧目录就地切换 metadata mode。

## 单机转新 Raft 集群

两条通路都不复制旧 Raft 身份：

- **动态集群**：以 `--bootfs.metadata.mode=dynamic` 和共享 `BOOTFS_CLUSTER_TOKEN` 启动上述恢复 seed，并显式传入计划。随后让新的空 dynamic 节点发现/连接 seed；它们通过新集群的快照和日志追赶。对象内容留在 seed，之后的新上传按配置满足同步副本策略。见 [动态组网](mononode-dynamic-cluster.md)。
- **静态集群**：创建新的 group-id 和空 peers 根目录。所有成员第一次启动时均传入**同一份** `bootfs.restore.plan`（可复制计划文件，内容必须完全相同），让每个进程在导入完成前不暴露业务存储。只有恢复 seed 带有对象字节，其他成员从 Raft 同步元数据。不要混入未携带计划、已经对外接受写入的节点。

导入后核对资源数量、内容、S3、作者/描述、标签、漫画顺序和检索，再开放写入。删除 `kernel/search` 后重启可重建索引；在生产操作前先在恢复副本演练。

## 当前边界

还不提供在线快照/写入保留屏障、增量备份、缺失节点的降级恢复或外部对象仓库。导出完整扫描并在内存中持有元数据/清单；大库需要后续的流式分页协议。整组停机时不一致的 follower 必须先恢复追赶，再重新停写导出。Raft 恢复/合并目前使用单次条件批次，受内核 RPC 报文大小限制；超出限制需要后续分阶段导入协议，不能用部分导入冒充完整恢复。原生产目录没有在本次开发中被迁移或恢复。
