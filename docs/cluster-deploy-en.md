# Cluster Deployment

If you are familiar with Etcd & Docker Compose or are an experienced developer, you can deploy based on the architecture on your own and skip this chapter.

This chapter provides a reference cluster startup configuration.

## Deployment

Based on Tailscale + Etcd Discovery (Recommended)

Since etcd involves some security considerations, I recommend using [Tailscale](https://tailscale.com/) to build the network.

Given that we expect to deploy 2 nodes, visit [Click here to request a discovery token with size 2 from discovery.etcd.io](https://discovery.etcd.io/new?size=2) to obtain a discovery token.

For example: `https://discovery.etcd.io/a24d8b8054c829a6a4406ba3901cdce6`

Let's take Node A (100.105.106.92) as an example.

Node A's `docker-compose.yml` is almost identical to the default standalone compose file.

We need a centralized MeiliSearch instance.

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

Node B (100.87.61.11) and other nodes do not need to deploy their own MeiliSearch instance.

Instead, in Node B's `.env` file, we should point the relevant configuration to Node A's MeiliSearch instance.

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
