package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.DurableFiles;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Local credentials never enter the shared metadata, backups, status responses or browser storage. */
@Component
public final class ClusterCredentials {
    private final Path file;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile String token;
    private java.security.PrivateKey signingKey;
    private String publicKey;
    private String code, redeemedBy;
    private long expiresAt;
    private int attempts;

    @org.springframework.beans.factory.annotation.Autowired
    public ClusterCredentials(TfsConfig config, @Value("${bootfs.cluster.token:}") String configured) throws IOException {
        this(Path.of(config.getRootPath(), "kernel", "security", "cluster.json"), configured, Clock.systemUTC());
    }
    ClusterCredentials(Path file, String configured, Clock clock) throws IOException {
        this.file = file; this.clock = clock;
        if (file != null && Files.exists(file)) {
            JSONObject saved = JSON.parseObject(Files.readString(file));
            token = saved.getString("token");
            if (token == null || token.isBlank()) throw new IOException("Invalid persisted cluster credential");
            if (saved.getString("signingPrivate")!=null) {
                try {
                    var factory=java.security.KeyFactory.getInstance("Ed25519");
                    signingKey=factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(saved.getString("signingPrivate"))));
                    publicKey=saved.getString("signingPublic");
                    factory.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
                } catch(GeneralSecurityException|IllegalArgumentException invalid) { throw new IOException("Invalid node signing identity",invalid); }
            }
        } else {
            if (file != null && file.getParent().getParent() != null
                    && Files.exists(file.getParent().getParent().resolve("cluster/node.json"))
                    && (configured == null || configured.isBlank())) {
                throw new IOException("Node owner credential is missing; restore kernel/security/cluster.json from the node backup");
            }
            token = configured == null || configured.isBlank() ? newToken() : configured;
        }
        if (signingKey==null) {
            try { var pair=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); signingKey=pair.getPrivate(); publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
            catch(GeneralSecurityException failure) { throw new IOException("Cannot initialize node identity",failure); }
            persist(token);
        }
    }
    public String token() { return token; }
    public boolean accepts(String supplied) { return supplied != null && equal(token, supplied); }
    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private String newToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void persist(String value) throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Path stage = Files.createTempFile(file.getParent(), ".credential-", ".tmp");
        try {
            Files.setPosixFilePermissions(stage, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(stage, new JSONObject(Map.of("token", value,"signingPrivate",Base64.getEncoder().encodeToString(signingKey.getEncoded()),"signingPublic",publicKey)).toJSONString(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.syncDirectory(file.getParent());
        } finally { Files.deleteIfExists(stage); }
    }
    public synchronized JSONObject issueCode() {
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        expiresAt = clock.millis() + 300_000; attempts = 0; redeemedBy = null;
        return new JSONObject(Map.of("code", code, "expiresAt", expiresAt));
    }
    public synchronized String redeem(String supplied, String requestId) throws IOException {
        if (code == null || clock.millis() >= expiresAt || attempts >= 5) throw new IOException("验证码已失效，请在目标节点重新生成");
        if (requestId == null || !requestId.matches("[a-f0-9]{64}")) throw new IOException("Invalid pairing request identity");
        if (supplied == null || !equal(code, supplied)) { attempts++; throw new IOException("验证码错误"); }
        if (redeemedBy != null && !equal(redeemedBy, requestId)) throw new IOException("验证码已使用，请重新生成");
        redeemedBy = requestId;
        // A code grants a peer capability; the node's owner credential is never exported.
        return requestId("grant:" + requestId, supplied);
    }
    public String requestId(String endpoint, String code) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((endpoint + "\n" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    public String publicKey() { return publicKey; }
    public String sign(String value) throws IOException {
        try { var signature=java.security.Signature.getInstance("Ed25519"); signature.initSign(signingKey); signature.update(value.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(signature.sign()); }
        catch(GeneralSecurityException failure) { throw new IOException("Cannot sign node request",failure); }
    }
    public static boolean verify(String publicKey,String value,String supplied) {
        try {
            var key=java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            var signature=java.security.Signature.getInstance("Ed25519"); signature.initVerify(key); signature.update(value.getBytes(StandardCharsets.UTF_8)); return signature.verify(Base64.getDecoder().decode(supplied));
        } catch(GeneralSecurityException|IllegalArgumentException|NullPointerException invalid) { return false; }
    }
}
