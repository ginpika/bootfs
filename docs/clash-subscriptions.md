# Clash 配置与 v2rayN 订阅

原文与转换订阅按设置页的认证及公开读取开关处理。新安装关闭 SSO、开启公开读取，持有链接可读取其中的密码等凭证。启用 SSO 且关闭公开读取后需要认证；见 [节点设置](node-settings.md) 和 [安全审查](security-audit.md)。

为 `.yaml` / `.yml` 文件添加 `clash` 标签后，列表使用 Clash 小猫图标；详情显示 `proxies` 中各节点的名称、服务器域名或 IP、端口及当前 TCP 延迟。标签和扩展名不区分大小写。

打开详情会自动检测，也可以点击「重新检测」。数值是持有文件的 BootFS 服务器到节点端口的 TCP 连接耗时，不代表浏览器所在网络的延迟或代理隧道性能。Hysteria2、TUIC 等 UDP 节点的 TCP 检测结果也不代表 UDP 可用性。相同地址和端口合并检测，结果缓存 5 秒；超时、DNS 失败、无法连接或服务繁忙会分别显示。

详情面板提供可复制的订阅地址：

```text
https://your-bootfs.example/p/<uuid>?p=vless
```

将此地址添加为 v2rayN 等客户端的订阅即可。虽然参数名为 `vless`，它会导出配置中**所有可转换类型**：VLESS、VMess、Trojan、Shadowsocks、Hysteria2 和 TUIC。响应为 UTF-8 分享链接列表的 Base64 编码。未传 `p` 时，`/p/<uuid>` 返回 YAML 原文；转换只改变响应，不修改文件。通过编辑器保存时，UUID、下载地址及此订阅地址保持不变，后续订阅读取更新后的内容。

支持常见 TCP、WebSocket、gRPC、HTTP 伪装及基础 XHTTP 传输，并保留可表达的 TLS、Reality、SNI、指纹、ALPN 等选项。无法由目标客户端分享链接准确表达的协议、传输或安全选项会跳过。没有可导出的节点时返回 HTTP 422；非 Clash 标签的 YAML 返回 HTTP 415。`X-BootFS-Converted-Nodes` 响应头表示导出节点数，`X-BootFS-Conversion-Warnings` 表示跳过或转换提示的数量。

配置支持 UTF-8 / GBK，按原文件字节计算最大 2 MiB，最多 500 个节点。默认优先严格识别 UTF-8，无法解码时尝试 GBK；编辑器可手动选择读取编码并保存，后续 Clash 解析也会使用已保存的编码。保存保留该编码，不能用 GBK 表示的字符会明确报错，不会替换为问号。订阅输出始终为 UTF-8 分享链接列表的 Base64 编码，YAML 原文保持原始字节。

读取内嵌的 `proxies` 列表，不会下载 `proxy-providers` 的远端内容。详情和检测接口沿用文件管理登录权限；订阅地址沿用 `/p` 的公开文件读取方式，跨节点访问会跳转到持有文件的节点并保留 `p=vless`。

格式依据：[v2rayN 订阅说明](https://github.com/2dust/v2rayN/wiki/Description-of-subscription)、[VMess 分享链接](https://github.com/2dust/v2rayN/wiki/Description-of-VMess-share-link)、[Shadowsocks SIP002](https://shadowsocks.org/doc/sip002.html)、[Mihomo 传输配置](https://wiki.metacubex.one/en/config/proxies/transport/)。
