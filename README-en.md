# BootFS

[中文](./README.md)

<details>
  <summary>UI Preview</summary>
  <img src="./docs/preview1.png" alt="列表界面" width="600">
  <img src="./docs/preview2.png" alt="媒体瀑布流网格界面" width="600">
</details>

A lightweight file management system focused on multimedia files, built with Spring Boot & FFmpeg.

## Why do you need BootFS?

The answer may be one of the following, and I happen to satisfy all of them personally.

- You're tired of ugly UIs in traditional distributed file systems and want a modern, interactive file storage service that also supports S3 protocol.
- You're a cyber hoarder, passionate about collecting various internet resources like images, videos, audio, models, etc., and enjoy appreciating these treasures alone in front of your computer.
- You just finished watching The Melancholy of Haruhi Suzumiya and suddenly want to build a site to endlessly loop the Endless Eight.
- You're an AIGC believer, hanging around various AI creation platforms. As you constantly improve your prompt engineering skills for "winning the lottery", you gaze at your massive collection of outputs and fall into deep thought.
- You want to try a more modern image hosting service, like one based on BootFS, or a video hosting service.
- For some reason, you don't want to use cloud storage from big public cloud providers and prefer a self-hosted solution.
- You need to find a more comprehensive digital asset management software.

## Architecture

![BootFS Cluster Architecture](./Architecture.png)

- Decentralized Peer-to-Peer Edge Storage Architecture

## Features

- Excellent audio/video preview in both list and grid modes, supporting preview of multiple formats with detailed file descriptions.
- Classic dual-panel layout for easier selection and upload of image files from local paths.
- Tag-based classification management for organizing your resources.
- Image metadata support for ComfyUI format, including prompt, cfg, sample, steps, etc.
- Meticulously crafted UI/UX with Tailwind CSS, featuring excellent preview capabilities.
- Built-in HLS streaming personal broadcasting platform with fragmented HLS file uploads for sharing movies with friends online.
- Built-in stunning waterfall random gallery.
- WebP encoding/decoding and HLS transcoding capabilities via FFmpeg.
- Small yet refined lightweight architecture based on etcd + Meilisearch with built-in metadata management, peer-to-peer distributed structure (maybe in the near future).

Built with: spring-boot + etcd + meilisearch

## Deployment

HostID is a lightweight account foundation based on email, serving as a personal mailbox while managing small-scale sites like personal blogs and small forums.

SSO authentication based on HostID is enabled by default. To disable it, please refer to application.properties & Dockerfile and configure .env.

**Prerequisite (recommend): [Hostid](https://github.com/ginpika/hostid)**

```Shell
# Find a place
mkdir bootfs
# Get compose.yaml
wget -O compose.yaml https://raw.githubusercontent.com/ginpika/bootfs/refs/heads/main/docker-compose.ghcr.yml
# Write .env based on application.properties & Dockerfile
cp .env.example .env
# Launch with one click
docker compose up -d
```

## Cluster Deployment

See [Cluster Deployment](./docs/cluster-deploy-en.md)