package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSON;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.representer.Representer;

import java.io.StringReader;
import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Safe Clash YAML reader and v2rayN share-link subscription adapter. Never fetches provider URLs. */
@Service
public class ClashSubscriptionService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_NODES = 500;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_EVENTS = 100_000;
    private static final Set<String> SS_METHODS = Set.of("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "chacha20", "chacha20-ietf", "rc4-md5", "none", "plain");

    public record ClashNode(String name, String type, String server, int port, Map<String, Object> options) {}
    public record ParsedConfig(List<ClashNode> nodes, List<String> warnings) {}
    public record Subscription(String content, int converted, List<String> warnings) {}

    public List<ClashNode> parse(String content) { return parseConfig(content).nodes(); }

    public ParsedConfig parseConfig(String content) {
        if (content == null || content.isBlank()) throw invalid("Clash 配置为空");
        if (content.length() > MAX_BYTES || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Clash 配置最多支持 2 MiB");
        }
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(20);
            Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(), new DumperOptions(), options);
            // SnakeYAML 1.x has no construction depth limit. Check its streaming events before
            // recursive object construction, including compact flow sequences and mappings.
            int depth = 0, events = 0, aliases = 0;
            Deque<String> activeAnchors = new ArrayDeque<>();
            Deque<Long> expandedSizes = new ArrayDeque<>();
            Map<String, Long> anchorSizes = new HashMap<>();
            for (Event event : yaml.parse(new StringReader(content))) {
                if (++events > MAX_EVENTS || event instanceof AliasEvent && ++aliases > 20) throw invalid("Clash 配置过于复杂");
                if (event instanceof AliasEvent alias) {
                    if (activeAnchors.contains(alias.getAnchor())) throw invalid("Clash 配置包含递归引用");
                    addExpandedSize(expandedSizes, anchorSizes.getOrDefault(alias.getAnchor(), 1L));
                }
                if (event instanceof ScalarEvent scalar) {
                    if (scalar.getAnchor() != null) anchorSizes.put(scalar.getAnchor(), 1L);
                    addExpandedSize(expandedSizes, 1);
                }
                if (event instanceof CollectionStartEvent collection) {
                    if (++depth > MAX_DEPTH) throw invalid("Clash 配置嵌套过深");
                    activeAnchors.push(collection.getAnchor() == null ? "" : collection.getAnchor());
                    expandedSizes.push(1L);
                }
                if (event instanceof CollectionEndEvent) {
                    depth--;
                    String anchor = activeAnchors.pop(); long size = expandedSizes.pop();
                    if (!anchor.isEmpty()) anchorSizes.put(anchor, size);
                    addExpandedSize(expandedSizes, size);
                }
            }
            loaded = yaml.load(content);
        } catch (ResponseStatusException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid("Clash YAML 格式无效，或包含不支持的类型、重复键和递归引用"); }
        if (!(loaded instanceof Map<?, ?> document) || !(document.get("proxies") instanceof List<?> proxies)) {
            throw invalid("Clash 配置缺少 proxies 节点列表，暂不拉取外部 proxy-providers");
        }
        if (proxies.size() > MAX_NODES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Clash 配置最多支持 500 个节点");
        List<ClashNode> nodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int[] copyBudget = { MAX_EVENTS };
        for (int index = 0; index < proxies.size(); index++) {
            String label = "第 " + (index + 1) + " 个节点";
            try {
                if (!(proxies.get(index) instanceof Map<?, ?> mapping)) throw new IllegalArgumentException("不是有效的节点配置");
                Map<String, Object> values = copyMap(mapping, new IdentityHashMap<>(), 0, copyBudget);
                String name = optional(values, "name", label);
                if (name.length() > 256) name = name.substring(0, 256);
                label = "节点「" + name + "」";
                String type = required(values, "type").toLowerCase(Locale.ROOT);
                String server = normalizeHost(required(values, "server"));
                int port = port(required(values, "port"));
                nodes.add(new ClashNode(name, type, server, port, values));
            } catch (IllegalArgumentException failure) { warnings.add(label + "已跳过：" + failure.getMessage()); }
        }
        return new ParsedConfig(List.copyOf(nodes), List.copyOf(warnings));
    }

    /** Newline-delimited standard share URIs wrapped in the Base64 subscription format. */
    public Subscription convert(String content) {
        ParsedConfig parsed = parseConfig(content);
        List<String> links = new ArrayList<>();
        List<String> warnings = new ArrayList<>(parsed.warnings());
        for (ClashNode node : parsed.nodes()) {
            try { links.add(toUri(node)); }
            catch (IllegalArgumentException failure) { warnings.add("节点「" + node.name() + "」未导出：" + failure.getMessage()); }
        }
        if (links.isEmpty()) throw invalid("配置中没有可转换为 v2rayN 订阅的节点");
        String encoded = Base64.getEncoder().encodeToString((String.join("\n", links) + "\n").getBytes(StandardCharsets.UTF_8));
        return new Subscription(encoded, links.size(), List.copyOf(warnings));
    }

    public String toUri(ClashNode node) {
        Map<String, Object> values = node.options();
        rejectPresent(values, "dialer-proxy", "shadow-tls-opts", "restls-opts", "jls-opts", "ech-opts", "certificate", "private-key");
        return switch (node.type()) {
            case "vless" -> vless(node);
            case "vmess" -> vmess(node);
            case "trojan" -> trojan(node);
            case "ss", "shadowsocks" -> shadowsocks(node);
            case "hysteria2", "hy2" -> hysteria2(node);
            case "tuic" -> tuic(node);
            default -> throw unsupported("协议 " + node.type());
        };
    }

    private String vless(ClashNode node) {
        Map<String, Object> values = node.options();
        // v2rayN's VLESS standard-URI reader does not import allowInsecure. Do not
        // silently turn a self-signed node into a different certificate policy.
        if (truth(values.get("tls")) && map(values, "reality-opts").isEmpty() && truth(values.get("skip-cert-verify"))) throw unsupported("VLESS skip-cert-verify（标准分享链接无法保留）");
        String id = required(values, "uuid");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("encryption", optional(values, "encryption", "none"));
        String flow = optional(values, "flow", "");
        if (!flow.isEmpty() && !flow.equals("xtls-rprx-vision")) throw unsupported("VLESS flow");
        put(query, "flow", flow);
        addSecurity(values, query, false);
        addTransport(values, query, false);
        if (!flow.isEmpty() && (!query.get("type").equals("tcp") || query.get("security").equals("none"))) throw unsupported("Vision 需要 TCP 和 TLS/Reality");
        return uri("vless", node, encode(id), query);
    }

    private String trojan(ClashNode node) {
        Map<String, String> query = new LinkedHashMap<>();
        addSecurity(node.options(), query, true);
        addTransport(node.options(), query, false);
        if (query.get("security").equals("none")) throw unsupported("未启用 TLS 的 Trojan");
        if (truth(node.options().get("skip-cert-verify"))) query.put("allowInsecure", "1");
        return uri("trojan", node, encode(required(node.options(), "password")), query);
    }

    private String vmess(ClashNode node) {
        Map<String, Object> values = node.options();
        if (values.containsKey("reality-opts")) throw unsupported("VMess Reality");
        Map<String, String> transport = new LinkedHashMap<>();
        addSecurity(values, transport, false);
        addTransport(values, transport, true);
        String network = transport.get("type");
        if (network.equals("xhttp") || network.equals("httpupgrade")) throw unsupported("VMess " + network);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", "2"); payload.put("ps", node.name()); payload.put("add", node.server()); payload.put("port", node.port());
        payload.put("id", required(values, "uuid")); payload.put("aid", integer(values, "alterId", 0));
        payload.put("scy", optional(values, "cipher", "auto")); payload.put("net", network);
        payload.put("type", transport.getOrDefault("headerType", network.equals("grpc") ? "gun" : "none"));
        payload.put("host", transport.getOrDefault("host", transport.getOrDefault("authority", "")));
        payload.put("path", transport.getOrDefault("path", transport.getOrDefault("serviceName", "")));
        payload.put("tls", transport.get("security").equals("tls") ? "tls" : "");
        for (String key : List.of("sni", "alpn", "fp", "vcn", "pcs")) payload.put(key, transport.getOrDefault(key, ""));
        payload.put("insecure", truth(values.get("skip-cert-verify")) ? "1" : "0");
        return "vmess://" + Base64.getEncoder().encodeToString(JSON.toJSONBytes(payload));
    }

    private String shadowsocks(ClashNode node) {
        Map<String, Object> values = node.options();
        String method = required(values, "cipher").toLowerCase(Locale.ROOT);
        if (!SS_METHODS.contains(method)) throw unsupported("Shadowsocks cipher " + method);
        String password = required(values, "password");
        String credentials = method.startsWith("2022-") ? encode(method) + ":" + encode(password)
                : Base64.getUrlEncoder().withoutPadding().encodeToString((method + ":" + password).getBytes(StandardCharsets.UTF_8));
        Map<String, String> query = new LinkedHashMap<>();
        String plugin = optional(values, "plugin", "");
        if (!plugin.isEmpty()) {
            Map<String, Object> options = map(values, "plugin-opts");
            StringBuilder option = new StringBuilder();
            if (plugin.equals("obfs")) {
                ensureKeys(options, Set.of("mode", "host"));
                if (!required(options, "mode").equals("http")) throw unsupported("v2rayN 订阅中的 obfs 非 HTTP 模式");
                option.append("obfs-local;obfs=http;obfs-host=").append(pluginEscape(required(options, "host")));
            } else if (plugin.equals("v2ray-plugin")) {
                ensureKeys(options, Set.of("mode", "host", "path", "tls", "mux", "skip-cert-verify"));
                if (!optional(options, "mode", "websocket").equals("websocket") || truth(options.get("skip-cert-verify")) || truth(options.get("mux"))) throw unsupported("v2ray-plugin 选项");
                option.append("v2ray-plugin;mode=websocket");
                for (String key : List.of("host", "path")) if (options.containsKey(key)) option.append(';').append(key).append('=').append(pluginEscape(required(options, key)));
                if (truth(options.get("tls"))) option.append(";tls");
                option.append(";mux=").append(truth(options.get("mux")) ? "1" : "0");
            } else throw unsupported("Shadowsocks plugin " + plugin);
            query.put("plugin", option.toString());
        }
        if (truth(values.get("tls")) || !optional(values, "network", "tcp").equals("tcp")) throw unsupported("Shadowsocks 额外传输选项");
        return uri("ss", node, credentials, query);
    }

    private String hysteria2(ClashNode node) {
        Map<String, Object> values = node.options();
        rejectPresent(values, "realm", "realm-opts");
        Map<String, String> query = liteTls(values, "insecure");
        String obfs = optional(values, "obfs", "");
        if (!obfs.isEmpty()) {
            if (!obfs.equals("salamander")) throw unsupported("Hysteria2 混淆类型 " + obfs);
            query.put("obfs", obfs); query.put("obfs-password", required(values, "obfs-password"));
        }
        put(query, "pinSHA256", optional(values, "fingerprint", ""));
        put(query, "mport", optional(values, "ports", "").replace(':', '-'));
        return uri("hysteria2", node, encode(required(values, "password")), query);
    }

    private String tuic(ClashNode node) {
        Map<String, Object> values = node.options();
        if (values.containsKey("token") || truth(values.get("disable-sni")) || truth(values.get("udp-over-stream"))
                || !optional(values, "udp-relay-mode", "native").equals("native")) throw unsupported("TUIC v4 或额外传输选项");
        Map<String, String> query = liteTls(values, "allow_insecure");
        query.put("congestion_control", optional(values, "congestion-controller", "cubic"));
        return uri("tuic", node, encode(required(values, "uuid")) + ":" + encode(required(values, "password")), query);
    }

    private Map<String, String> liteTls(Map<String, Object> values, String insecureKey) {
        Map<String, String> query = new LinkedHashMap<>();
        put(query, "sni", optional(values, "sni", optional(values, "servername", "")));
        put(query, "alpn", csv(values.get("alpn")));
        put(query, "vcn", optional(values, "name-cert-verify", ""));
        put(query, "pcs", optional(values, "fingerprint", ""));
        if (truth(values.get("skip-cert-verify"))) query.put(insecureKey, "1");
        return query;
    }

    private void addSecurity(Map<String, Object> values, Map<String, String> query, boolean tlsDefault) {
        boolean tls = values.containsKey("tls") ? truth(values.get("tls")) : tlsDefault;
        Map<String, Object> reality = map(values, "reality-opts");
        if (!reality.isEmpty()) {
            if (!tls) throw unsupported("Reality 未开启 TLS");
            ensureKeys(reality, Set.of("public-key", "short-id", "spider-x", "mldsa65-verify"));
            query.put("security", "reality"); query.put("pbk", required(reality, "public-key"));
            put(query, "sid", optional(reality, "short-id", ""));
            put(query, "spx", optional(reality, "spider-x", ""));
            put(query, "pqv", optional(reality, "mldsa65-verify", ""));
        } else query.put("security", tls ? "tls" : "none");
        if (tls) {
            put(query, "sni", optional(values, "servername", optional(values, "sni", "")));
            put(query, "alpn", csv(values.get("alpn")));
            put(query, "fp", optional(values, "client-fingerprint", ""));
            put(query, "vcn", optional(values, "name-cert-verify", ""));
            put(query, "pcs", optional(values, "fingerprint", ""));
            if (truth(values.get("skip-cert-verify"))) query.put("allowInsecure", "1");
        }
    }

    private void addTransport(Map<String, Object> values, Map<String, String> query, boolean vmess) {
        String network = optional(values, "network", "tcp").toLowerCase(Locale.ROOT);
        if (network.equals("ws")) {
            Map<String, Object> options = map(values, "ws-opts");
            ensureKeys(options, Set.of("path", "headers", "max-early-data", "early-data-header-name", "v2ray-http-upgrade", "v2ray-http-upgrade-fast-open"));
            if (integer(options, "max-early-data", 0) != 0 || truth(options.get("v2ray-http-upgrade-fast-open"))) throw unsupported("WebSocket Early Data");
            if (truth(options.get("v2ray-http-upgrade"))) network = "httpupgrade";
            Map<String, Object> headers = options.containsKey("headers") ? map(options, "headers") : map(values, "ws-headers");
            for (String key : headers.keySet()) if (!key.equalsIgnoreCase("Host")) throw unsupported("WebSocket 自定义请求头");
            put(query, "host", headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("Host")).map(entry -> scalar(entry.getValue())).findFirst().orElse(""));
            query.put("path", optional(options, "path", optional(values, "ws-path", "/")));
        } else if (network.equals("grpc")) {
            Map<String, Object> options = map(values, "grpc-opts");
            ensureKeys(options, Set.of("grpc-service-name", "grpc-authority", "grpc-user-agent", "ping-interval", "max-connections", "min-streams", "max-streams"));
            if (options.containsKey("grpc-user-agent")) throw unsupported("gRPC 自定义 User-Agent");
            put(query, "serviceName", optional(options, "grpc-service-name", ""));
            put(query, "authority", optional(options, "grpc-authority", ""));
            query.put("mode", "gun");
        } else if (network.equals("http")) {
            Map<String, Object> options = map(values, "http-opts");
            ensureKeys(options, Set.of("method", "path", "headers"));
            if (!optional(options, "method", "GET").equals("GET")) throw unsupported("HTTP 伪装请求方法");
            Map<String, Object> headers = map(options, "headers");
            for (String key : headers.keySet()) if (!key.equalsIgnoreCase("Host")) throw unsupported("HTTP 伪装自定义请求头");
            headers.forEach((key, value) -> put(query, "host", csv(value)));
            put(query, "path", csv(options.get("path")));
            query.put("headerType", "http"); network = "tcp";
        } else if (network.equals("xhttp") && !vmess) {
            Map<String, Object> options = map(values, "xhttp-opts");
            ensureKeys(options, Set.of("host", "path", "mode", "headers"));
            put(query, "host", optional(options, "host", "")); query.put("path", optional(options, "path", "/"));
            String mode = optional(options, "mode", "auto");
            if (!Set.of("auto", "stream-one", "stream-up", "packet-up").contains(mode)) throw unsupported("XHTTP mode");
            query.put("mode", mode);
            if (options.containsKey("headers")) query.put("extra", JSON.toJSONString(Map.of("headers", map(options, "headers"))));
        } else if (!network.equals("tcp")) throw unsupported("传输层 " + network);
        query.put("type", network);
    }

    private static String uri(String scheme, ClashNode node, String credentials, Map<String, String> query) {
        String host = node.server().contains(":") ? "[" + node.server() + "]" : node.server();
        String parameters = query.isEmpty() ? "" : "?" + String.join("&", query.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).toList());
        // SIP002 requires a slash when a plugin query is present.
        String slash = scheme.equals("ss") && !query.isEmpty() ? "/" : "";
        return scheme + "://" + credentials + "@" + host + ":" + node.port() + slash + parameters + "#" + encode(node.name());
    }

    private static String normalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.length() > 253 || host.isBlank() || host.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character))) throw new IllegalArgumentException("服务器地址无效");
        if (host.contains(":")) {
            try { if (host.contains("%") || URI.create("http://[" + host + "]:443").getHost() == null) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("IPv6 地址无效"); }
            return host;
        }
        try { return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("域名或 IP 地址无效"); }
    }

    private static int port(String value) {
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("端口无效");
        int port = Integer.parseInt(value); if (port < 1 || port > 65535) throw new IllegalArgumentException("端口超出范围"); return port;
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        String value = optional(values, key, String.valueOf(fallback));
        try { int number = Integer.parseInt(value); if (number < 0) throw new NumberFormatException(); return number; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " 不是有效的非负整数"); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"); }
    private static String pluginEscape(String value) {
        // v2rayN splits SIP002 options on semicolons before unescaping, and accepts
        // only equals/comma/backslash escapes for v2ray-plugin paths.
        if (value.contains(";")) throw unsupported("包含分号的插件参数");
        return value.replace("\\", "\\\\").replace("=", "\\=").replace(",", "\\,");
    }
    private static void put(Map<String, String> values, String key, String value) { if (value != null && !value.isBlank()) values.put(key, value); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value != null && Set.of("true", "1").contains(value.toString().toLowerCase(Locale.ROOT)); }
    private static String required(Map<String, Object> values, String key) { String value = optional(values, key, ""); if (value.isBlank()) throw new IllegalArgumentException("缺少 " + key); return value; }
    private static String optional(Map<String, Object> values, String key, String fallback) { Object value = values.get(key); if (value == null) return fallback; String scalar = scalar(value); return scalar.isEmpty() ? fallback : scalar; }
    private static String scalar(Object value) { if (value == null) return ""; if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString(); throw new IllegalArgumentException("字段值必须是文本或数字"); }
    private static String csv(Object value) { if (value instanceof List<?> list) return String.join(",", list.stream().map(ClashSubscriptionService::scalar).toList()); return scalar(value); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> values, String key) { Object value = values.get(key); if (value == null) return Map.of(); if (value instanceof Map<?, ?>) return (Map<String, Object>)value; throw new IllegalArgumentException(key + " 不是有效的配置对象"); }
    private static void ensureKeys(Map<String, Object> values, Set<String> supported) { for (String key : values.keySet()) if (!supported.contains(key)) throw unsupported("选项 " + key); }
    private static void rejectPresent(Map<String, Object> values, String... keys) { for (String key : keys) if (values.containsKey(key) && values.get(key) != null && !values.get(key).equals("") && !values.get(key).equals(Map.of())) throw unsupported("选项 " + key); }
    private static IllegalArgumentException unsupported(String feature) { return new IllegalArgumentException("暂不支持转换" + feature); }
    private static ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason); }

    private static Map<String, Object> copyMap(Map<?, ?> mapping, IdentityHashMap<Object, Boolean> visiting, int depth, int[] budget) {
        spendCopyBudget(budget);
        if (depth > MAX_DEPTH || visiting.put(mapping, true) != null) throw new IllegalArgumentException("递归或嵌套过深的配置");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("配置键必须为文本");
            result.put(key, copy(entry.getValue(), visiting, depth + 1, budget));
        }
        visiting.remove(mapping);
        return Collections.unmodifiableMap(result);
    }

    private static Object copy(Object value, IdentityHashMap<Object, Boolean> visiting, int depth, int[] budget) {
        spendCopyBudget(budget);
        if (value instanceof Map<?, ?> map) return copyMap(map, visiting, depth, budget);
        if (value instanceof List<?> list) {
            if (depth > MAX_DEPTH || visiting.put(list, true) != null) throw new IllegalArgumentException("递归或嵌套过深的配置");
            List<Object> copied = new ArrayList<>(); for (Object element : list) copied.add(copy(element, visiting, depth + 1, budget));
            visiting.remove(list); return Collections.unmodifiableList(copied);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("不支持的 YAML 字段类型");
    }

    private static void spendCopyBudget(int[] budget) {
        if (--budget[0] < 0) throw invalid("Clash 配置展开后过于复杂");
    }

    private static void addExpandedSize(Deque<Long> sizes, long amount) {
        if (!sizes.isEmpty()) {
            long total = sizes.pop() + amount;
            if (total > MAX_EVENTS) throw invalid("Clash 配置展开后过于复杂");
            sizes.push(total);
        }
    }
}
