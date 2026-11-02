# 接口安全审查（2026-11-02）

范围：当前仓库全部业务 HTTP 控制器、SSO/本机访问、S3、节点间签名、文件与媒体处理、前端渲染及部署配置。代码审查和本地回归测试不等同于线上渗透测试或安全认证。未对生产环境发起请求。

## 已落实的修复

- 统一拦截所有控制器。以前 `/f/{uuid}`、`/p/{uuid}`、`/thumb/{uuid}`、批量 ZIP 及部分查看页面未经过统一鉴权；现在按数据库中的认证和公开读取设置放行。S3 只能通过自身签名认证，静态资源保留匿名读取。
- 该应用使用全局共享数据模型，没有逐用户所有权。新安装默认关闭 SSO；启用后默认不限角色。设置角色列表时才要求精确命中，留空明确允许全部已认证角色。节点签名凭证不能移除集群成员。本机 owner token 是高权限凭证，应按管理员密码保管。
- 移除控制器通配 CORS；Cookie 写操作需同源 Origin/Referer/Fetch Metadata，缺少浏览器来源的脚本需 `X-BootFS-Request: 1`，此头不能覆盖恶意来源。正常登录后的顶层页面跳转可用；本机免登录仍使用原始连接与更严格来源校验。
- 私有响应强制 `Cache-Control: no-store`，全局设置 `nosniff`、`no-referrer`、`SAMEORIGIN`；不向客户端返回常规服务器异常细节。SSO 返回地址使用配置的应用入口，避免信任用户提供的 Host。
- 不再采信 `Accept-Type` 决定文件内容类型。内联内容使用固定安全类型，本地预览直接读已授权的文件；跨节点预览发出签名请求，不转发浏览器 Cookie。
- 文件名、标签、缩略图链接、TV 列表和元数据管理页面的危险 HTML 插值已转义；拒绝 `javascript:` 等媒体链接。
- HLS 重排拒绝路径、换行、指令和重复文件名；TV 切片使用 HLS 的目录边界、符号链接与删除状态检查。批量 ZIP 下载限制标识且要求鉴权；批量操作上限 1,000 个资源，导出上限 4 GiB，ZIP 条目去除路径，防止接收端解压穿越。
- PNG 文本元数据限制单块/总量 2 MiB、块数与解压输出；漫画解包限制 10,000 项、单项 128 MiB、总量 1 GiB，跳过项也计入预算。
- 媒体进程禁用网络协议与播放列表输入格式，限制捕获输出、并发和运行时间；缩略图队列有上限并合并重复任务，HLS 转换不再排队等待 CPU 锁。
- Clash 延迟检测按数据库设置决定是否允许内网，新安装默认允许；关闭后仅连接已解析并通过校验的公网 IP，连接使用已验证地址，不二次解析。
- `@RequestBody` 在认证后、对象构造前限制 16 MiB、JSON 深度 64、250,000 token 并拒绝重复键；大文件 multipart 延迟到鉴权后解析。
- S3 签名保留所有重复查询参数，防止前置未签名参数改变实际查询；要求签名包含 Host 和日期，收紧日期格式并使用常量时间比较。对象写入继续校验正文摘要。
- 节点时间戳比较避免整数溢出；节点请求不能用 `application/*+json` 或其他 MIME 绕过正文摘要检查。复制快照绑定资源所有者、ID、摘要和实际字节；签名节点不再使用旧的无快照 `/t` 入口。
- Tomcat 从 9.0.83 升到 9.0.123，SnakeYAML 从 1.30 升到 2.5；补上 Maven Dependabot 周检。

## 配置与兼容性

新安装按用户要求默认放开：SSO 关闭、角色不限、本机直连开启、公开读取开启、内网检测开启；S3 关闭且无凭证。18 项业务设置移入节点本地加密 RocksDB，由 `/settings` 管理，已有库不会被遗留环境变量覆盖。首次升级仅导入明确提供的旧配置；部署文件不再注入普通配置。完整流程见 [节点设置与升级](node-settings.md)。

启用 SSO 后，角色列表为逗号分隔的精确角色名；留空允许所有已认证用户。选择角色仍不实现多租户隔离。设置写操作要求同源证据，SSO 启用或更改前验证当前会话或保留原始本机访问，失败不应用。

公开读取开关控制 `/f`、`/p`、`/thumb`、媒体播放、随机图片和订阅等现有入口。Clash 原文/转换订阅含密码等信息，公开模式下持有链接即可读取。关闭公开读取并启用 SSO 才会要求这些入口登录。`isPublicAccess` 是随机图片/画廊标记，不是逐文件 ACL；S3 仍单独要求 SigV4。SSO 关闭时共享后台也不校验用户身份。

代理配置在下次启动、容器初始化前读取数据库；原始连接采集始终先于代理解析。本机访问不信任网关、私网或代理流量。秘密密钥只写不回显，配置保护密钥和节点凭证自动保存在本机安全状态；普通 KV、资源联合协议和便携备份不导出秘密。节点地址及桶名只在受控离线迁移成功后生效。

命令行使用 SSO Cookie 写接口时，加 `-H 'X-BootFS-Request: 1'`；本机豁免写请求使用 `X-BootFS-Local-Request: 1`。跨节点 JSON、缩略图及普通预览使用签名且不转发 Cookie；下载、HLS 和 Clash 转换仍有 302 到目标节点的路径，未宣称本次解决。

## 尚需解决与验证的风险

1. **高优先级：迁移仍受维护的 Spring Boot / Spring Framework 主版本。** 2.7.18 已于 2023 年结束开源维护；本次兼容更新 Tomcat 和 SnakeYAML 不会解决整个旧框架及全部传递依赖的维护风险。此次未做大版本 Jakarta API 迁移，也未获得持续更新的完整 SCA 数据库扫描结果。不能据此声称依赖零漏洞。
2. **生产配置未核验。** SSO 的 HTTPS、共享 Cookie 的 Secure/HttpOnly/SameSite、角色来源和会话撤销由外部 SSO 系统控制；所有节点间和客户端连接应使用 HTTPS，并仅信任实际反向代理。应用支持 HTTP 便于本机使用，不会强制把已有内网地址改为 HTTPS。生产机的网络 ACL、端口暴露、磁盘权限与备份内容不在本次本地验证范围。
3. **高权限工具与全局信任。** 原始键值/索引管理和 owner token 可改变全局状态；配对 peer 可操作联合目录中的资源。没有逐用户文件 ACL、细粒度 peer 读写授权、密钥分级或操作二次确认。不要给不可信账号开放管理角色，也不要配对不可信节点。
4. **公开分享是链接能力模型。** 开启公开开关后没有逐链接到期/撤销 token；UUID 被泄露即可读取。希望同时保留私有文件和公开分享时，应另行实现逐文件 ACL 和签名过期链接。
5. **供应链与 DoS 仍需生产治理。** 前端仍依赖外部 CDN 脚本，尚未全部自托管并启用严格 nonce CSP；媒体原生解析器仍需及时更新、容器资源限制及隔离。大文件/S3 写入仍依赖反向代理限流、磁盘配额及连接超时；本次未做容量/并发压力测试。集群连接管理允许管理员指定内网节点，这是有意保留的管理员网络能力，应结合出口策略。
6. **后续验证建议：** 在隔离的预发布环境用真实 SSO、TLS/反向代理及实际管理员角色运行浏览器安全用例，开展经过授权的动态扫描；对构建出的最终镜像做 SCA 和镜像扫描。

依据：[Spring Boot 2.7.18 官方维护声明](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/)、[Tomcat 9 官方安全公告](https://tomcat.apache.org/security-9.html)。版本公告仅用于维护判断，不代表每条 CVE 的利用前提都在本应用成立。

## 逐接口清单

下表的用户鉴权描述均以启用 SSO 为前提，角色按设置页规则；新安装 SSO 默认关闭。下表列出源码中的方法/路径组合；GET 的 HTTP HEAD 行为继承相同校验。统一鉴权回归另外从 Spring 运行时的全部映射动态枚举，避免新增接口遗漏。表中“检查”描述已审查的边界，不表示对该接口穷尽了所有攻击输入。

| 方法 | 路径 | 检查 | 来源 |
|---|---|---|---|
| GET | `/` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:52](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L52) |
| GET | `/album/**` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:105](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L105) |
| GET | `/api/album/{uuid}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:699](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L699) |
| GET | `/api/auth/me` | 登录身份回填；跨站登出受来源校验。 | [AuthController:68](../src/main/java/cc/ginpika/bootfs/controller/AuthController.java#L68) |
| POST | `/api/cluster/connect` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:41](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L41) |
| GET | `/api/cluster/data/replica-source/{id}` | 必须有节点凭证及签名；JSON 正文所有 MIME 变体参与摘要检查。 | [FederationDataController:17](../src/main/java/cc/ginpika/bootfs/controller/FederationDataController.java#L17) |
| POST | `/api/cluster/data/replica/commit` | 必须有节点凭证及签名；JSON 正文所有 MIME 变体参与摘要检查。 | [FederationDataController:22](../src/main/java/cc/ginpika/bootfs/controller/FederationDataController.java#L22) |
| GET | `/api/cluster/data/resource/{id}` | 必须有节点凭证及签名；JSON 正文所有 MIME 变体参与摘要检查。 | [FederationDataController:15](../src/main/java/cc/ginpika/bootfs/controller/FederationDataController.java#L15) |
| POST | `/api/cluster/data/search` | 必须有节点凭证及签名；JSON 正文所有 MIME 变体参与摘要检查。 | [FederationDataController:14](../src/main/java/cc/ginpika/bootfs/controller/FederationDataController.java#L14) |
| GET | `/api/cluster/data/text-owner/{id}` | 必须有节点凭证及签名；JSON 正文所有 MIME 变体参与摘要检查。 | [FederationDataController:16](../src/main/java/cc/ginpika/bootfs/controller/FederationDataController.java#L16) |
| POST | `/api/cluster/departure` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:38](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L38) |
| POST | `/api/cluster/join` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:32](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L32) |
| POST | `/api/cluster/leave` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:47](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L47) |
| POST | `/api/cluster/link` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:21](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L21) |
| GET | `/api/cluster/link/status` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:24](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L24) |
| POST | `/api/cluster/membership` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:35](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L35) |
| POST | `/api/cluster/pairing/code` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:25](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L25) |
| POST | `/api/cluster/pairing/redeem` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:26](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L26) |
| GET | `/api/cluster/ready` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:19](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L19) |
| POST | `/api/cluster/remove` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:46](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L46) |
| GET | `/api/cluster/status` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:20](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L20) |
| GET | `/api/cluster/versions` | 管理角色或本机 owner token；节点间端点另校验签名、nonce 和 actor。redeem 使用一次性短码。 | [ClusterController:48](../src/main/java/cc/ginpika/bootfs/controller/ClusterController.java#L48) |
| GET | `/api/dashboard/node-stats` | 系统、流量与客户端 IP 仅对授权管理身份；节点统计允许签名 peer。 | [DashboardApiController:81](../src/main/java/cc/ginpika/bootfs/controller/DashboardApiController.java#L81) |
| GET | `/api/dashboard/stats` | 系统、流量与客户端 IP 仅对授权管理身份；节点统计允许签名 peer。 | [DashboardApiController:64](../src/main/java/cc/ginpika/bootfs/controller/DashboardApiController.java#L64) |
| DELETE | `/api/etcd/key` | 原始键值具有全局读取和写入能力，仅授权管理身份；不提供普通用户访问。 | [EtcdApiController:107](../src/main/java/cc/ginpika/bootfs/controller/EtcdApiController.java#L107) |
| GET | `/api/etcd/key` | 原始键值具有全局读取和写入能力，仅授权管理身份；不提供普通用户访问。 | [EtcdApiController:107](../src/main/java/cc/ginpika/bootfs/controller/EtcdApiController.java#L107) |
| POST | `/api/etcd/key` | 原始键值具有全局读取和写入能力，仅授权管理身份；不提供普通用户访问。 | [EtcdApiController:107](../src/main/java/cc/ginpika/bootfs/controller/EtcdApiController.java#L107) |
| GET | `/api/etcd/keys` | 原始键值具有全局读取和写入能力，仅授权管理身份；不提供普通用户访问。 | [EtcdApiController:23](../src/main/java/cc/ginpika/bootfs/controller/EtcdApiController.java#L23) |
| DELETE | `/api/etcd/keys/prefix` | 原始键值具有全局读取和写入能力，仅授权管理身份；不提供普通用户访问。 | [EtcdApiController:122](../src/main/java/cc/ginpika/bootfs/controller/EtcdApiController.java#L122) |
| GET | `/api/file/{uuid}/ai-metadata` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:517](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L517) |
| GET | `/api/file/{uuid}/clash` | 只读非机密节点摘要；延迟探测默认拒绝内网/本机/云元数据地址。 | [ClashFileController:30](../src/main/java/cc/ginpika/bootfs/controller/ClashFileController.java#L30) |
| POST | `/api/file/{uuid}/clash/latency` | 只读非机密节点摘要；延迟探测默认拒绝内网/本机/云元数据地址。 | [ClashFileController:38](../src/main/java/cc/ginpika/bootfs/controller/ClashFileController.java#L38) |
| GET | `/api/file/{uuid}/cover` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:551](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L551) |
| GET | `/api/file/{uuid}/details` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:466](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L466) |
| GET | `/api/file/{uuid}/publication` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:263](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L263) |
| PUT | `/api/file/{uuid}/rename` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:316](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L316) |
| DELETE | `/api/file/{uuid}/tags` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:366](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L366) |
| GET | `/api/file/{uuid}/tags` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:366](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L366) |
| POST | `/api/file/{uuid}/tags` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:366](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L366) |
| PUT | `/api/file/{uuid}/tags` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:366](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L366) |
| GET | `/api/file/{uuid}/text` | 授权管理身份或签名节点；2 MiB 字节限制、编码校验与版本冲突检查。 | [TextFileController:42](../src/main/java/cc/ginpika/bootfs/controller/TextFileController.java#L42) |
| PUT | `/api/file/{uuid}/text` | 授权管理身份或签名节点；2 MiB 字节限制、编码校验与版本冲突检查。 | [TextFileController:42](../src/main/java/cc/ginpika/bootfs/controller/TextFileController.java#L42) |
| GET | `/api/logout` | 登录身份回填；跨站登出受来源校验。 | [AuthController:30](../src/main/java/cc/ginpika/bootfs/controller/AuthController.java#L30) |
| GET | `/api/meilisearch/indexes` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:21](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L21) |
| DELETE | `/api/meilisearch/indexes/{indexUid}/documents` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:72](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L72) |
| GET | `/api/meilisearch/indexes/{indexUid}/documents` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:72](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L72) |
| POST | `/api/meilisearch/indexes/{indexUid}/documents` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:72](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L72) |
| DELETE | `/api/meilisearch/indexes/{indexUid}/documents/{docId}` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:49](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L49) |
| GET | `/api/meilisearch/indexes/{indexUid}/documents/{docId}` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:49](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L49) |
| GET | `/api/meilisearch/indexes/{indexUid}/search` | 全局索引读写仅授权管理身份；搜索由内嵌 Lucene 实现，无 SQL 拼接。 | [MeiliSearchApiController:60](../src/main/java/cc/ginpika/bootfs/controller/MeiliSearchApiController.java#L60) |
| GET | `/api/tags/list` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:455](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L455) |
| GET | `/api/tags/search` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:423](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L423) |
| PUT | `/comic` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:105](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L105) |
| GET | `/dashboard` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:121](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L121) |
| POST | `/downloadFileBatch` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:210](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L210) |
| GET | `/etcd` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:132](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L132) |
| PUT | `/f` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:88](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L88) |
| GET | `/f/{uuid}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:122](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L122) |
| GET | `/file/{uuid}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:733](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L733) |
| GET | `/g/b/{uuid}` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:260](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L260) |
| GET | `/gallery` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:113](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L113) |
| POST | `/hls/convert` | 读启用认证并关闭公开读取时需登录；公开模式仅放行单文件播放，管理接口始终受保护。转换进程受协议/格式/时间限制。 | [HlsController:25](../src/main/java/cc/ginpika/bootfs/service/hls/HlsController.java#L25) |
| GET | `/hls/{folder}/{file}` | 读启用认证并关闭公开读取时需登录；公开模式仅放行单文件播放，管理接口始终受保护。转换进程受协议/格式/时间限制。 | [HlsController:50](../src/main/java/cc/ginpika/bootfs/service/hls/HlsController.java#L50) |
| POST | `/hls/{uuid}/finalize` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:418](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L418) |
| POST | `/hls/{uuid}/segments` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:347](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L347) |
| GET | `/hls/{uuid}/segments/info` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:450](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L450) |
| GET | `/hls/{uuid}/segments/list` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:502](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L502) |
| POST | `/hls/{uuid}/segments/reorder` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:574](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L574) |
| DELETE | `/hls/{uuid}/segments/{fileName}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:647](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L647) |
| GET | `/index-old` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:37](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L37) |
| GET | `/meilisearch` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:143](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L143) |
| GET | `/old-admin` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:68](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L68) |
| GET | `/p/{uuid}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:176](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L176) |
| GET | `/p/{uuid}`（p=vless） | 含代理凭证；启用认证并关闭公开读取时需登录，公开开关开启后持有链接即可导出。 | [ClashSubscriptionController:34](../src/main/java/cc/ginpika/bootfs/controller/ClashSubscriptionController.java#L34) |
| POST | `/publicAccess/{uuid}` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:279](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L279) |
| GET | `/queryPageOffset` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:83](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L83) |
| POST | `/removeFileBatch` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:199](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L199) |
| POST | `/removeFileByUuid` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:185](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L185) |
| GET | `/s3` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:58](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L58) |
| GET | `/s3/{bucket}` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:63](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L63) |
| DELETE | `/s3/{bucket}/**` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:102](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L102) |
| GET | `/s3/{bucket}/**` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:102](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L102) |
| HEAD | `/s3/{bucket}/**` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:102](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L102) |
| PUT | `/s3/{bucket}/**` | 启用时所有方法必须通过 SigV4 Filter；MVC 再检查过滤器认证标记；校验对象正文摘要。 | [S3Controller:102](../src/main/java/cc/ginpika/bootfs/s3/S3Controller.java#L102) |
| GET | `/setu` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:290](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L290) |
| GET | `/setu-url` | 全局文件管理启用认证并关闭公开读取时需登录；随机图片仅公开模式可匿名，批量 ZIP 始终需登录。 | [WebUIApiController:302](../src/main/java/cc/ginpika/bootfs/controller/WebUIApiController.java#L302) |
| PUT | `/t` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:293](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L293) |
| PUT | `/t/prepare` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:268](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L268) |
| GET | `/thumb/{uuid}` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:212](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L212) |
| GET | `/tv` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:23](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L23) |
| GET | `/tv/api/playlist` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:117](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L117) |
| POST | `/tv/api/playlist` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:117](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L117) |
| GET | `/tv/api/videos` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:98](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L98) |
| GET | `/tv/manage` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:34](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L34) |
| GET | `/tv/playlist.m3u8` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:48](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L48) |
| POST | `/tv/refresh` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:81](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L81) |
| GET | `/tv/status` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:92](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L92) |
| GET | `/tv/ts/{uuid}/{fileName}` | 启用认证并关闭公开读取时需登录；公开模式可播放，管理接口受保护；TS 读取复用目录/符号链接/删除状态检查。 | [TvController:65](../src/main/java/cc/ginpika/bootfs/service/tv/TvController.java#L65) |
| GET | `/video-player` | 页面启用认证并关闭公开读取时需登录；公开模式仅放行明确的媒体查看页面。 | [WebUIController:83](../src/main/java/cc/ginpika/bootfs/controller/WebUIController.java#L83) |
| POST | `/virtual-file` | 文件/媒体/上传链路；启用认证并关闭公开读取时需登录。复制走 owner 凭证或签名快照；HLS 文件名与重排受约束。 | [ManageController:301](../src/main/java/cc/ginpika/bootfs/controller/ManageController.java#L301) |

合计 100 个显式方法/路径组合（含条件订阅表示和 S3 GET/HEAD）。

## 验证记录

- JDK 17，全量干净构建 `mvn clean package` 成功：38 个 Java 测试类、239 项测试，失败 0、错误 0、跳过 0。包含真实本机 HTTP、转发代理、本机免登录、联合节点、复制、备份恢复、S3 协议及新增安全回归。
- `node --test src/test/js/*.test.cjs`：5 项通过，包含列表文件名/标签 XSS 和媒体链接方案校验。
- 新增安全用例覆盖所有运行时业务映射的默认认证、普通用户拒绝、跨站写入、身份回填、私有缩略图缓存、危险 JSON、HLS 指令注入、批量 ZIP 认证与路径、Host 跳转注入、S3 重复参数签名、私网探测、PNG 解压、节点 MIME/时间戳与成员移除边界、媒体超时和输出上限。
- FFmpeg 实际冒烟：无扩展名 PNG 对象可以解析和缩放；强制 HLS 输入被格式白名单拒绝。此处使用 PNG 输出验证，本机默认精简 FFmpeg 缺少 WebP 编码器；完整 FFmpeg 二进制在本机未于 10 秒内启动，WebP 编码及完整 HLS 转码未在此冒烟中验收。生产镜像需具备所需编解码器。
- 最终 JAR 已核实包含 `snakeyaml-2.5.jar` 和 Tomcat core/el/websocket `9.0.123`。没有运行旧依赖代替安全更新进行最终测试。
- `git diff --check` 通过。构建产物：`target/bootfs-v1.0.0-beta.1.jar`。未提交 Git、推送或部署，未访问线上用户数据。

## 节点设置接口

| 方法 | 路径 | 边界 |
|---|---|---|
| GET | `/settings`、`/api/settings` | 继承当前节点的认证规则，配置读取不返回秘密密钥或节点凭证，禁止缓存 |
| PUT | `/api/settings` | 同源证据、版本冲突检测、完整校验、原子持久化；地址/桶变更需确认及离线检查 |
| POST | `/api/settings/sso/test` | 同源证据，限时会话接口连接测试，不跟随跨主机重定向 |
| POST | `/api/settings/credentials/rotate` | 同源证据及明确确认，只轮换管理 token，不返回凭证，不改变配对签名身份 |
