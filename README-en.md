# BootFS

A lightweight file management system focused on image, audio, and video storage, built with Spring Boot.

It’s an image hosting service, and much more.

## Features

- All-in-one Lightweight Architecture: Small yet powerful, designed specifically for personal users.
- Peer-to-Peer Distributed Structure: Designed with peer node architecture for scalability.
- Elegant UI/UX: Resource management interface meticulously crafted with TailwindCSS.
- Excellent Media Preview: High-performance audio and video previewing in both list and grid modes.
- Manga ZIP Processing: Automatic handling and optimization of ZIP-packaged manga.
- Built-in HLS Management: A personal broadcasting platform based on HLS (HTTP Live Streaming) slicing.
- Creative Details: Some magical and interesting little design touches...

Built with: Spring Boot + etcd + Meilisearch

## Deployment

**Prerequisite (Optional): [Hostid](https://github.com/ginpika/hostid)**

Implements Single Sign-On (SSO) authentication based on Hostid.

```Shell
# Create a directory
mkdir bootfs
# Download compose.yaml
wget -O compose.yaml https://raw.githubusercontent.com/ginpika/bootfs/refs/heads/main/docker-compose.ghcr.yml
# Set up .env
cp .env.example .env
# Launch with one click
docker compose up -d
```

## Roadmap

1. Finer-grained authorization/permission control. 
2. Improved peer-to-peer expansion capabilities. 
3. Optimization for software-based HLS transcoding.
4. Image .webp convert