# BootFS

[English](./README-en.md)

一个轻量级，专注于图片、音视频存储的文件管理系统，基于 Spring Boot。

这是一个图床，或者不仅仅是图床。

## 特性

- all-in-one 小而精的轻量化架构，面向个人用户友好的
- 基于对等节点设计的可分布式结构
- 基于 TailwindCSS 精心设计 UI/UX 的资源管理界面
- 优秀的列表 / 网格模式下的音视频预览能力
- 漫画 ZIP 自动处理
- 内置 hls 切片管理，基于 hls 流的个人放送平台
- 一些神奇有趣的小设计...

Build with ：spring-boot + etcd + meilisearch

## 部署

**前置(optional): [Hostid](https://github.com/ginpika/hostid)**

基于 hostid 的 sso 鉴权实现单点登录。

```shell
# 找个地方
mkdir bootfs
# 获取 compose.yaml
wget -O compose.yaml https://raw.githubusercontent.com/ginpika/bootfs/refs/heads/main/docker-compose.ghcr.yml
# 部署 .env
cp .env.example .env
# 一键启动
docker compose up -d
```

## Roadmap

1. 颗粒度更细的鉴权
2. 图片的 webp 转码能力
3. 更好的对等拓展能力
4. 软件 hls 转码优化
