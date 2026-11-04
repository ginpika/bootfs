<div align="center">

<img src="./docs/logo.webp" alt="BootFS" width="96" height="96">

# BootFS

**Self-hosted file management and storage for your media.**

Browse, organize and share your images, videos, audio and creative work. Run one node independently, or connect it to other BootFS nodes when you need to.

[![Build and verify](https://github.com/ginpika/bootfs/actions/workflows/docker-build.yml/badge.svg)](https://github.com/ginpika/bootfs/actions/workflows/docker-build.yml)
[![Docker: AMD64 / ARM64](https://img.shields.io/badge/Docker-AMD64%20%2F%20ARM64-2496ED?logo=docker&logoColor=white)](#quick-start)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](./LICENSE)

**English** · [简体中文](./README-zh-CN.md)

[Preview](#preview) · [Why BootFS](#why-bootfs) · [Networking](#nodes) · [Features](#features) · [Quick start](#quick-start) · [Documentation](#documentation)

</div>

<a id="preview"></a>

![BootFS media grid with photos, illustrations, video covers and tags](./docs/preview2.png)

<details>
<summary>More screenshots</summary>

**List view.**

![BootFS file list](./docs/preview1.png)

**Dual-pane upload.**

![BootFS resource list alongside a local Studio imports folder](./docs/preview3.png)

</details>

<a id="why-bootfs"></a>

## Why do you need BootFS?

The answer may be one of the following, and I happen to satisfy all of them personally.

- You're tired of ugly UIs in traditional distributed file systems and want a modern, interactive file storage service that also supports S3 protocol.
- You're a cyber hoarder, passionate about collecting various internet resources like images, videos, audio, models, etc., and enjoy appreciating these treasures alone in front of your computer.
- You just finished watching The Melancholy of Haruhi Suzumiya and suddenly want to build a site to endlessly loop the Endless Eight.
- You're an AIGC believer, hanging around various AI creation platforms. As you constantly improve your prompt engineering skills for "winning the lottery", you gaze at your massive collection of outputs and fall into deep thought.
- You want to try a more modern image hosting service, like one based on BootFS, or a video hosting service.
- For some reason, you don't want to use cloud storage from big public cloud providers and prefer a self-hosted solution.
- You need to find a more comprehensive digital asset management software.

<a id="nodes"></a>

## Independent nodes and dynamic networking

Each BootFS node keeps its own complete files, metadata and search index. One application container and one data volume are enough to run it, without external etcd or MeiliSearch services.

- **Your data stays on your node.** The node holding a resource manages its files, tags, descriptions and index. Joining a group does not merge resource databases or rewrite existing resource UUIDs.
- **Connect with a verification code.** Generate a code in the system dashboard, then enter the node URL and code on the other end. No manual key exchange is needed.
- **Search together, access as needed.** Federated queries search reachable members; changes are forwarded to the node holding the resource. The list indicates incomplete results when some nodes are offline.
- **Keep working after leaving.** Local files, metadata and indexes remain readable and writable after leaving a group. The node stays independent across restarts and can join another group later.

Uploads default to `single-local`: a resource can be published once it is durable locally. You can also configure complete replicas, or choose `replicated(k)` to require acknowledgements from multiple nodes before publication. Joining a group does not automatically move every file. Reading a resource after its source node goes offline depends on whether a reachable node holds a complete replica. See [dynamic networking](./docs/mononode-dynamic-cluster.md) and the [kernel guide](./docs/mononode-kernel.md).

<a id="features"></a>

## What you can do with BootFS

| Use | Features |
| --- | --- |
| **Browse and upload** | List, grid and dual-pane layouts; browse local directories and select files to upload, with image, audio and video previews. |
| **Organize collections** | Tags, names, descriptions and full-text search, folder browsing, and management of comics and image archives. |
| **Keep AI creations** | Read generation metadata embedded in images, including ComfyUI prompts, cfg, sampler and steps. |
| **Edit text** | A built-in editor for YAML, TXT, TOML, INI, JSON and other text files, with line numbers, indentation, keyboard saving and UTF-8 / GBK support, up to 2 MiB. Saving preserves UUIDs, links and tags. |
| **View and stream** | A random waterfall gallery, personal HLS broadcasting and fragmented HLS uploads; FFmpeg generates media derivatives and handles WebP / HLS. |
| **Manage Clash configurations** | Inspect proxy details in YAML files tagged `clash`, check server-side TCP latency, and convert configurations into subscriptions for v2rayN and other clients. |
| **Connect S3 clients** | A node-level S3-compatible interface for object listing, upload, download, deletion, copying and prefix folders, using SigV4 and path-style addressing. |

Original Clash files and converted subscriptions follow the resource's read permissions. Set access permissions accordingly for configurations containing credentials. See [Clash subscriptions](./docs/clash-subscriptions.md); S3 client setup is covered under [Sign-in and S3](#integrations).

<a id="quick-start"></a>

## Quick start

You need **Git, Docker and Docker Compose**. The commands below build from source and start a new node. The image includes Java 17 and FFmpeg, so no additional media tools are needed on the host.

```sh
git clone https://github.com/ginpika/bootfs.git
cd bootfs
docker compose up --build -d
```

Open **[http://localhost:8181](http://localhost:8181)**, or replace `localhost` with your server address. Use **Settings** in the top-right corner (`/settings`) to configure the current node:

1. Set the node's public URL. For access from other machines or nodes, use an address reachable by browsers and peers. Follow the page's restart instructions after changing it.
2. Configure sign-in and public reads for your intended audience. New installations have **SSO disabled and public reads enabled**. To require sign-in, configure and test your authentication service, then enable SSO and disable public reads.
3. Enable S3, adjust replica policies or connect other nodes as needed.

The default port is **8181**. Persistent data lives at **`/data`** in the container, backed by the Compose `data` volume. New installations also have no role restrictions, with direct loopback access and private-network latency probes enabled. When SSO is disabled, shared management operations do not verify user identity either.

Ordinary settings are stored in the current node's database; no `.env` file is required. Optional startup modes are documented in [`.env.example`](./.env.example), with `BOOTFS_METADATA_MODE=auto` as the default. Change initialized settings through the UI. Before upgrading an existing deployment, read [settings migration](./docs/node-settings.md#首次升级与优先级) and the [kernel migration guide](./docs/mononode-kernel.md).

<details>
<summary>Use a published AMD64 / ARM64 image</summary>

Run from the repository root. Get the `BOOTFS_IMAGE` for this revision from the GitHub Actions publication job summary, and replace the placeholder below with the actual full commit SHA:

```sh
export BOOTFS_IMAGE='ghcr.io/ginpika/bootfs:sha-<full commit SHA>'
docker compose -f docker-compose.ghcr.yml up -d
```

This Compose file requires an explicit image. GitHub Actions runs the JDK 17 tests, then verifies AMD64 / ARM64 images for startup, FFmpeg, upload/download, search and persistence after container recreation before publishing to GHCR. The current default-branch commit updates `latest`, version tags publish their versions, and each publication includes a `sha-<full commit SHA>` tag.

</details>

<a id="integrations"></a>

## Sign-in and S3

| Connection | How to use it |
| --- | --- |
| **HostID SSO** | Enter a compatible authentication service URL, such as [HostID](https://github.com/ginpika/hostid), in Settings. Test the connection before enabling SSO; optionally restrict allowed roles. |
| **Trusted loopback access** | With SSO enabled, you can retain sign-in-free access for direct loopback connections. Docker gateways, private-network addresses and requests forwarded by a proxy do not qualify. See [local access](./docs/local-access.md). |
| **S3 clients** | Create and save access credentials in Settings, then enable S3. The endpoint is `https://<node-domain>/s3`, the default bucket is `bootfs`, and authentication uses separate SigV4 credentials. |

S3 bucket/key mappings belong to the node receiving the request. Connecting nodes does not create a global S3 namespace. Multipart uploads, object versioning and other parts of the full S3 API are not currently available. Cyberduck users can import the [BootFS connection profile](./docs/clients/BootFS.cyberduckprofile). See [node settings](./docs/node-settings.md#s3-客户端连接) for connection steps and reverse-proxy requirements.

## Backups and upgrades

**Back up the complete data root, including the settings database, encryption key and node identity.** To make a backup for restoring the same node, stop the application first, then back up all of `/data`, including files, metadata, `kernel/settings`, `kernel/security`, `kernel/cluster` and the root-level `uuid`. Keep the settings database together with its corresponding `kernel/security/settings.key`.

For migration between nodes and recovery of legacy Raft data, use the [offline backup, verification and restore tools](./docs/mononode-backup.md). Portable backups exclude deployment state such as S3 credentials and the node's security identity; configure these again after restoring. A file inventory alone cannot restore complete metadata.

For a source-built Compose deployment, back up first, then update from the original deployment directory:

```sh
git pull --ff-only
docker compose up --build -d
```

For published images, update `BOOTFS_IMAGE`, then run:

```sh
docker compose -f docker-compose.ghcr.yml pull
docker compose -f docker-compose.ghcr.yml up -d
```

Keep the same Compose project and data volume. **Do not use `docker compose down -v` to upgrade: it deletes the data volume.** For legacy deployments using external middleware or shared Raft, confirm the migration path in the [kernel guide](./docs/mononode-kernel.md) first.

## Tech stack and local development

**Spring Boot + Java 17**, with **RocksDB** for local metadata, **Lucene** for local search, **FFmpeg** for media processing, and **Thymeleaf + Tailwind CSS** for the frontend. Apache Ratis remains for legacy Raft mode compatibility; the default networking model does not depend on global Raft consensus.

<details>
<summary>Develop from source and run checks</summary>

Install **JDK 17, system Maven and FFmpeg**, then run from the repository root:

```sh
./dev.sh
# Forward additional Maven options, such as a different port:
./dev.sh -Dspring-boot.run.arguments=--server.port=8282
```

The script selects the same JDK 17 for Maven and the application, and enables the `dev` profile. When running Maven directly, set `JAVA_HOME` to JDK 17 and confirm with `mvn -version`. Java 8 cannot run this application; newer JDKs may be incompatible with the pinned Lombok annotation processor.

```sh
mvn test
mvn package -DskipTests
java -jar target/bootfs-v1.0.0-beta.1.jar --spring.profiles.active=dev
```

See the [kernel guide](./docs/mononode-kernel.md) for the local development data root, media dependencies and configuration.

</details>

<a id="documentation"></a>

## Documentation

Most linked guides are currently in Simplified Chinese.

| What you're doing | Where to start |
| --- | --- |
| Configuring a node, sign-in, public reads or S3 | [Node settings and upgrades](./docs/node-settings.md) |
| Understanding local data, upload policies and current limits | [Local kernel](./docs/mononode-kernel.md) |
| Deploying multiple nodes | [Cluster deployment](./docs/cluster-deploy-en.md) |
| Pairing, leaving a group or querying remote resources | [Autonomous nodes and dynamic networking](./docs/mononode-dynamic-cluster.md) |
| Backing up, verifying, restoring or migrating legacy data | [Backup and restore](./docs/mononode-backup.md) |
| Configuring the dashboard and reverse proxy | [System dashboard](./docs/dashboard.md) |
| Using loopback access and command-line writes | [Local access](./docs/local-access.md) |
| Managing Clash configurations and subscriptions | [Clash subscriptions](./docs/clash-subscriptions.md) |
| Understanding authentication, sharing and API access boundaries | [Security audit](./docs/security-audit.md) |
| Working on the interface and interactions | [Design system](./docs/design-system.md) |

BootFS is licensed under [GNU AGPL v3](./LICENSE).
