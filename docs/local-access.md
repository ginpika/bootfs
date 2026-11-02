# 本机直连免 SSO

默认关闭。需要在保留远程 SSO 的同时允许本机直连时，设置环境变量后重启应用：

```sh
export SSO_ENABLED=true
export SSO_LOCAL_BYPASS_ENABLED=true
java -jar target/bootfs-v1.0.0-beta.1.jar
```

也可通过 Spring 属性 `sso.local-bypass.enabled=true` 配置。`dev` 默认关闭 SSO；要验证本特性，应同时设置 `SSO_ENABLED=true`。直接启动 Java 不会自动读取 `.env`。

Docker Compose 用户在 `.env` 设置 `SSO_LOCAL_BYPASS_ENABLED=true`，两个 Compose 文件都会传入该变量；使用包含此特性的新构建或镜像重建容器后生效。该开关不会把 Docker 网关视为可信本机，因此宿主机通过发布端口访问容器时，仍可能需要 SSO。

## 放行条件

所有条件均需满足：

- `SSO_ENABLED=true` 且 `SSO_LOCAL_BYPASS_ENABLED=true`。
- Tomcat 在解析代理头之前记录的原始来源地址和目标地址，均为 `127.0.0.1` 或 IPv6 回环 `::1`（含展开形式）。不做 DNS 解析，不信任其他私网或网关地址。
- 原始 `Host` 是 `localhost`、`127.0.0.1` 或 `[::1]`（含 IPv6 展开形式），端口与实际监听端口一致。
- 原始请求没有 `Forwarded`、任何 `X-Forwarded-*`、`X-Real-IP`、`Via`、`CF-Connecting-IP`、`True-Client-IP`，也没有 Tomcat 配置的自定义代理头。头值即使为空也不放行。

缺少原始连接信息时不放行。该信息由内嵌 Tomcat 的 Engine Valve 在 Spring Boot 的 `RemoteIpValve` 之前采集，适用于 `none`、`native` 和 `framework` 代理解析策略。改为其他 Web 容器或外置 WAR 部署时默认继续走 SSO。

例如公网 → 宿主机 Caddy → Docker 的连接，即使代理头把客户端 IP 写为 `127.0.0.1`，也不会获得本地身份。现有远程 SSO、公开媒体读取、集群签名/令牌和配对校验、S3 SigV4 规则保持原有行为；本地身份仅替代需要 SSO 的用户访问。

## 浏览器与命令行

免登录访问显示“本地访问”，不创建 Cookie 或 SSO token，不显示 SSO 信息及退出登录入口。直接调用 `/api/logout` 返回成功，不触发远程登出或跳转。

本地免登录请求中的 `Origin`、`Referer` 必须与原始本地入口同源；拒绝跨站 Fetch Metadata、`Origin: null` 和跨端口来源。这些检查同样保护读取接口，防止现有 CORS 声明将本地数据提供给外站。

写请求还必须提供同源 `Origin` / `Referer`、`Sec-Fetch-Site: same-origin`，或者显式的 `X-BootFS-Local-Request: 1`。普通浏览器同源操作自动携带来源信息。命令行调用上传等写接口时加上该请求头：

```sh
curl -X PUT -H 'X-BootFS-Local-Request: 1' \
  -F 'file=@example.txt' http://127.0.0.1:8181/f
```

该头不能覆盖非本地来源、代理标记或不安全的浏览器来源；跨站预检即使被现有 MVC CORS 配置接受，实际受保护请求仍会被拒绝。

## 部署边界

开启后信任能连接本机回环端口的其他用户和进程，也包括它们可能提供的转发或 SSRF 能力。多用户宿主机应保留关闭状态。

同机反向代理必须保留或添加代理转发头。**如果一个本机代理删掉全部转发标记，并将 Host 改为本地地址，后端无法仅凭 HTTP 和 TCP 信息区分它与本机直连。** 这类部署应保持关闭，或把本地访问与代理流量隔离到不同实例/入口，并仅在本地实例开启。将 Docker 发布端口绑定到宿主机 `127.0.0.1` 并不等于容器内接收到回环直连。

本地身份不会签发可供其他节点或远程客户端复用的凭据；本次也不改变公开媒体和其他已有公开接口的访问范围。

验证（JDK 17）：

```sh
mvn -Dtest=LocalAccessPolicyTest,LocalAccessIntegrationTest,AuthenticationIntegrationTest,ForwardedClientIpIntegrationTest test
```
