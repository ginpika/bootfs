# 集群部署

如果你具备 Etcd & docker compose 的相关经验，那么你完全可以根据架构自行部署并跳过本章节。

本章节提供了一个参考性的集群启动配置。

## 部署

基于 TailScale + Etcd Discovery （Recommended）

由于 etcd 涉及到一些安全问题，所以我推荐使用 [Tailscale](https://tailscale.com/) 来构建网络。

加上我们预期部署的节点为 2，访问 https://discovery.etcd.io/new?size=2，获取到一个 discovery token。

例如：https://discovery.etcd.io/a24d8b8054c829a6a4406ba3901cdce6

我们以节点 A （100.105.106.92） 为例。

A 节点的 docker-compose.yml 和默认的单机 compose 几乎完全一致。

我们需要一个中心化的 MeiliSearch 实例。

```yaml
services:
  etcd:
    image: quay.io/coreos/etcd:v3.6.7
    command: >
      /usr/local/bin/etcd
      --name etcd-node-a
      --data-dir /etcd-data
      --listen-client-urls http://0.0.0.0:2379
      --advertise-client-urls http://100.105.106.92:2379
      --listen-peer-urls http://0.0.0.0:2380
      --initial-advertise-peer-urls http://100.105.106.92:2380
      --discovery https://discovery.etcd.io/a24d8b8054c829a6a4406ba3901cdce6
      --enable-pprof
    networks:
      - net
    volumes:
      - etcd_data:/etcd-data
    ports:
      - 127.0.0.1:2379:2379
      - 127.0.0.1:2380:2380
      - 100.105.106.92:2379:2379
      - 100.105.106.92:2380:2380

  meilisearch:
    image: getmeili/meilisearch:v1.34.1
    networks:
      - net
    volumes:
      - meilisearch_data:/meili_data
    ports:
      - "7700:7700"
    command: >
      /meilisearch
      --master-key ${MEILISEARCH_MASTER_KEY}

  app:
    image: ghcr.io/ginpika/bootfs:latest
    env_file:
      - .env
    networks:
      - net
    volumes:
      - data:/app
    ports:
      - "8181:8181"
    environment:
      - TFS_COPIES=${TFS_COPIES}
      - TFS_WEB_ENTRYPOINT=${TFS_WEB_ENTRYPOINT}
      - MEILISEARCH_MASTER_KEY=${MEILISEARCH_MASTER_KEY}
      - MEILISEARCH_WEB_UI=${MEILISEARCH_WEB_UI}
      - MEILISEARCH_URL=http://meilisearch:7700
    depends_on:
      - etcd
      - meilisearch

networks:
  net:

volumes:
  etcd_data:
  meilisearch_data:
  data:
```

节点 B (100.87.61.11) 以及其他节点，不需要再部署自己的 MeiliSearch 实例。

相对的，在节点 B 的 .env 文件中，我们应该将相关配置指向节点 A 的 MeiliSearch 实例。


```yaml
services:
  etcd:
    image: quay.io/coreos/etcd:v3.6.7
    command: >
      /usr/local/bin/etcd
      --name etcd-node-a
      --data-dir /etcd-data
      --listen-client-urls http://0.0.0.0:2379
      --advertise-client-urls http://100.87.61.11:2379
      --listen-peer-urls http://0.0.0.0:2380
      --initial-advertise-peer-urls http://100.87.61.11:2380
      --discovery https://discovery.etcd.io/a24d8b8054c829a6a4406ba3901cdce6
      --enable-pprof
    networks:
      - net
    volumes:
      - etcd_data:/etcd-data
    ports:
      - 127.0.0.1:2379:2379
      - 127.0.0.1:2380:2380
      - 100.87.61.11:2379:2379
      - 100.87.61.11:2380:2380

  app:
    image: ghcr.io/ginpika/bootfs:latest
    env_file:
      - .env
    networks:
      - net
    volumes:
      - data:/app
    ports:
      - "8181:8181"
    environment:
      - TFS_COPIES=${TFS_COPIES}
      - TFS_WEB_ENTRYPOINT=${TFS_WEB_ENTRYPOINT}
      - MEILISEARCH_MASTER_KEY=${MEILISEARCH_MASTER_KEY}
      - MEILISEARCH_WEB_UI=${MEILISEARCH_WEB_UI}
      - MEILISEARCH_URL=http://100.105.106.92:7700
    depends_on:
      - etcd

networks:
  net:

volumes:
  etcd_data:
  data:
```