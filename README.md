# toyohime-file-system

一个专为图片和音视频实现的轻量化分布式存储

设计初衷旨在开箱即用、易维护、并实现以下特性

技术栈：spring-boot + etcd + meilisearch

## 特性

- 类似 MinIO 的节点对等集群设计
- 全面、丰富的内存索引
- 简化配置

## 应用场景

- 个人博客
- 轻量级音视频站点、图床、门户站点、论坛等
- 数字资产管理


## 工程路线规划

- （√）简单易懂：基于 Spring Boot + Java I/O 的文件存储
- （√）可视化：Bootstrap 5 实现内置 Web UI，提供单点的下载、上传、删除以及批量操作
- （√）持久化：基于 JSON 日志提供基本的持久化
- （√）数据一致性：通过 etcd 的 Raft 实现保障集群元数据的一致性
- （√）去中心化：任意节点都可对集群内指定的资源进行细颗粒度的反向代理
- （√）数据冗余：多副本分发
- 基于 keys 的资源授权实现
- 集群级别的自愈：被动维护副本数，剔除不可用节点元数据
- 多媒体增强
  - （√）流媒体：基于 ffmpeg 对 mp4 文件自动切片、码率压制、转换到 hls 协议支持，内置 video.js
  - （√）自动解析 zip 图片包，实现类似相册的图片分组有序索引，内置图片列表预览器
  - 支持内容审查的图床接口
  - mp3 / mp4 的专辑封面，视频封面自动截图
  - 图片加密：在访问层对图片自动添加可视化水印 or 不可见的数字签名
  - 支持高精度模型的预览
- （√）基于 MeiliSearch 实现面向客户端的索引、Tag 分组、有序预览
- 运维成本：基于访问层的访问、引用统计、冷资源识别策略

## 部署

### 方式一：使用预构建镜像（推荐）

直接从 GitHub Container Registry 拉取镜像：

```shell
docker pull ghcr.io/nihilx/toyohime-file-service:latest
```

可用的镜像标签：
- `latest` - 最新稳定版本
- `v*.*.*` - 特定版本（如 `v1.0.0`）
- `main` / `master` - 主分支最新构建

快速部署（使用预构建镜像）：

```shell
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env 文件，修改必要的配置

# 2. 使用预构建镜像启动
docker-compose -f docker-compose.ghcr.yml up -d
```

### 方式二：docker-compose（开发环境、单点部署）

1. 配置 .env

```shell
cp .env.example .env
```

2. 使用 maven 构建 jar 或使用预编译的 jar

3. 构建镜像(见build.sh)

```shell
docker build -t tfs:latest .
```

4. 通过 docker-compose 启动

```shell
docker-compose up -d
```

## 扩展

- 查询性能优化方向：
  - ElasticSearch 调优
  - 节点内存缓存策略
- re-balance 读写性能优化方向：
  - 集群自愈、冗余时采用 gRPC + 消息队列
- 运维方向：
  - Arthas、Skywalking、Prometheus

## Core Requirement

- Java 17
- Spring Boot 2.7.18
- etcd 3.6.2
- MeiliSearch 1.17.1

## TODO

- ~~在 etcd 实现分布式文件元数据索引~~
- ~~对小文件实现 3 副本设计实现数据冗余~~
- ~~（已废弃）对大文件实现分块存储 + 纠删码（4+2）~~
- ~~（已废弃）集群资源平衡~~
- online 生命周期标准化
- 实现集群级别的系统自愈
- （50%）分别对图片、视频流、压缩文件实现功能增强和存储增强
- 引入 Elastic Search 作为读缓存以及主要的增强查询索引，代替 etcd 的命中
- 测试以 gRPC 的方式代替 http transfer，对比性能差异
- 随机选择节点，存在缺陷，尝试引入一致性Hash或其他设计
- ~~最终读写，I/O 性能测试~~ 
- 兼容 AWS S3 接口
- 目前没有很好的优化副本策略，如利用副本支持可读、分布式读、动态维护
- ~~优化 json 写以后，批量删除时 json 文件维护开销较大~~
- 使用 FileChannel 代替 RandomAccessFile 重写 JSON 尾插
- ~~配置文件外置化~~ 考虑到TFS目前未计划提供预编译的程序，配置文件不是那么重要
- 用 Java 重写 etcd 实现内置 raft（有生之年）
- 更好的权限管理
- html 静态资源本地化

### ~~为什么废弃文件分块存储的设计？~~

分块存储（File-Chunk）设计，原生不支持音视频在 Web 服务的 Range Request，这与系统设计初衷不符；

分块存储本身是大数据时代下，对硬件条件落后环境因素的妥协，不再适用于常规应用领域；

- 流媒体服务（被HLS/DASH取代）
- 高频更新系统（被LSM-tree架构取代）
- 低延迟访问需求（被计算存储融合架构取代）

文件副本已经提供了可恢复性

~~考虑到跨网络分区进行超大文件传输的场景时，文件分块可以更好地支持客户端上传~~

## 集群自愈

### 节点不可用时

（被动策略）

当用户访问时，etcd 存在元数据但是目标节点不可用，从 replicas 找到副本存储，恢复并刷新主副本

经过多次被动策略后，可能产生节点之间的存储能力不平衡

场景差异：

- 海量小资源，如图片；多读，高频小写入
- 大资源：如单副本高清视频：磁盘 I/O 压力，使用集群 hls 分片

### 以存储空间为准，平衡两边的存储资源是否需要？

不需要。

集群运行时是动态的，预期去对集群 re-balance 远不如在上载时，根据资源空间指定上载节点。

## TODO
- ~~（√）bootstrap-table 本地化~~

