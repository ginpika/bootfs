package cc.ginpika.bootfs.settings;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** The complete, deliberately bounded settings schema. Values are immutable after validation. */
public final class SettingsValues {
    public record Field(String property, String legacy, Object initial) {}
    public static final Map<String,Field> FIELDS;
    static {
        Map<String,Field> fields = new LinkedHashMap<>();
        fields.put("ssoEnabled", new Field("sso.enabled", "SSO_ENABLED", false));
        fields.put("allowedRoles", new Field("sso.allowed-roles", "SSO_ALLOWED_ROLES", ""));
        fields.put("localBypassEnabled", new Field("sso.local-bypass.enabled", "SSO_LOCAL_BYPASS_ENABLED", true));
        fields.put("ssoServerUrl", new Field("sso.server-url", "SSO_SERVER_URL", ""));
        fields.put("ssoInfoUrl", new Field("sso.info-url", "SSO_INFO_URL", ""));
        fields.put("publicSharingEnabled", new Field("bootfs.security.public-sharing.enabled", "BOOTFS_PUBLIC_SHARING_ENABLED", true));
        fields.put("allowPrivateProbes", new Field("bootfs.security.allow-private-probes", "BOOTFS_ALLOW_PRIVATE_PROBES", true));
        fields.put("s3Enabled", new Field("s3.enabled", "S3_ENABLED", false));
        fields.put("s3AccessKey", new Field("s3.access-key", "S3_ACCESS_KEY", ""));
        fields.put("s3SecretKey", new Field("s3.secret-key", "S3_SECRET_KEY", ""));
        fields.put("s3Bucket", new Field("s3.bucket", "S3_BUCKET", "bootfs"));
        fields.put("copies", new Field("tfs.copies", "TFS_COPIES", 1));
        fields.put("writePolicy", new Field("bootfs.objects.write-policy", "BOOTFS_OBJECT_WRITE_POLICY", "single-local"));
        fields.put("requiredCopies", new Field("bootfs.objects.required-copies", "BOOTFS_OBJECT_REQUIRED_COPIES", 0));
        fields.put("prepareTimeoutMs", new Field("bootfs.objects.prepare-timeout-ms", "BOOTFS_OBJECT_PREPARE_TIMEOUT_MS", 30000));
        fields.put("webEntrypoint", new Field("tfs.web-entrypoint", "TFS_WEB_ENTRYPOINT", "http://localhost:8181"));
        fields.put("forwardHeadersStrategy", new Field("server.forward-headers-strategy", "SERVER_FORWARD_HEADERS_STRATEGY", "none"));
        fields.put("internalProxies", new Field("server.tomcat.remoteip.internal-proxies", "SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES", "127[.]0[.]0[.]1|::1|0:0:0:0:0:0:0:1"));
        FIELDS = Collections.unmodifiableMap(fields);
    }
    private final Map<String,Object> values;
    public SettingsValues(Map<String,?> input) {
        Map<String,Object> result = new LinkedHashMap<>();
        if (!FIELDS.keySet().containsAll(input.keySet())) throw new IllegalArgumentException("包含未知设置项");
        FIELDS.forEach((key, field) -> {
            Object v = input.containsKey(key) ? input.get(key) : field.initial();
            if (v == null || field.initial() instanceof Boolean && !(v instanceof Boolean)
                    || field.initial() instanceof Integer && (!(v instanceof Number) || ((Number)v).doubleValue() != ((Number)v).intValue())
                    || field.initial() instanceof String && !(v instanceof String)) throw new IllegalArgumentException("设置类型无效：" + key);
            if (v instanceof Number n) v = n.intValue();
            if (v instanceof String s) {
                if (s.length() > 2048 || s.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("设置内容过长或包含控制字符："+key);
                v = key.equals("s3SecretKey") ? s : s.trim();
                if (key.equals("webEntrypoint") || key.equals("ssoServerUrl")) v = ((String)v).replaceAll("/+$", "");
            }
            result.put(key, v);
        });
        values = Collections.unmodifiableMap(result);
        validate();
    }
    private void validate() {
        url(string("ssoServerUrl"), true); url(string("ssoInfoUrl"), true); url(string("webEntrypoint"), false);
        if (!Set.of("single-local", "replicated").contains(string("writePolicy"))) invalid("写入策略无效");
        if (integer("copies") < 1 || integer("copies") > 100 || integer("requiredCopies") < 0 || integer("requiredCopies") > 100
                || integer("prepareTimeoutMs") < 100 || integer("prepareTimeoutMs") > 600000) invalid("副本数或准备超时时间超出范围");
        if (string("writePolicy").equals("replicated") && effectiveCopies() < 2) invalid("同步复制至少需要 2 份（包括本机）");
        if (!string("s3Bucket").matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || string("s3Bucket").contains("..")
                || string("s3Bucket").matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) invalid("桶名须为 3–63 位小写字母、数字、点或连字符");
        if (!string("s3AccessKey").isEmpty() && !string("s3AccessKey").matches("[A-Za-z0-9_-]{1,128}")) invalid("访问密钥格式无效");
        if (!string("s3SecretKey").isEmpty() && (string("s3SecretKey").length() > 256)) invalid("秘密密钥长度最多 256 位");
        if (bool("s3Enabled") && (string("s3AccessKey").isBlank() || string("s3SecretKey").isBlank())) invalid("开启 S3 前请填写完整凭证");
        if (!Set.of("none", "native", "framework").contains(string("forwardHeadersStrategy"))) invalid("代理策略无效");
        if (string("internalProxies").isEmpty() || string("internalProxies").length()>512) invalid("请填写可信代理地址正则表达式");
        try { Pattern.compile(string("internalProxies")); } catch (RuntimeException e) { invalid("可信代理正则表达式无效"); }
        if (string("allowedRoles").contains("*")) invalid("不限角色请留空，不使用星号");
    }
    public static void url(String value, boolean optional) {
        if (optional && value.isEmpty()) return;
        try {
            URI u = URI.create(value);
            if (!Set.of("http", "https").contains(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null
                    || u.getRawQuery()!=null || u.getRawFragment()!=null || u.getPort()>65535 || u.getPort()==0) throw new IllegalArgumentException();
        } catch (RuntimeException e) { invalid("请输入有效的 HTTP(S) 地址，不含账号、查询参数或片段"); }
    }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
    public String string(String key) { return (String) values.get(key); }
    public boolean bool(String key) { return (Boolean) values.get(key); }
    public int integer(String key) { return (Integer) values.get(key); }
    public int effectiveCopies() { return integer("requiredCopies") > 0 ? integer("requiredCopies") : integer("copies"); }
    public Map<String,Object> map() { return values; }
    public Map<String,Object> publicView() { Map<String,Object> v = new LinkedHashMap<>(values); v.remove("s3SecretKey"); v.remove("s3AccessKey"); v.put("s3AccessConfigured", !string("s3AccessKey").isEmpty()); v.put("s3SecretConfigured", !string("s3SecretKey").isEmpty()); return v; }
    public boolean permitsRole(String role) { return string("allowedRoles").isBlank() || role != null && Arrays.stream(string("allowedRoles").split(",")).map(String::trim).anyMatch(role::equals); }
    public Map<String,Object> portable() {
        Map<String,Object> v = new LinkedHashMap<>(values);
        for (String key : List.of("webEntrypoint", "forwardHeadersStrategy", "internalProxies", "s3AccessKey", "s3SecretKey")) v.remove(key);
        v.put("ssoEnabled", false); v.put("s3Enabled", false);
        return v;
    }
}
