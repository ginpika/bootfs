package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.core.io.DurableFiles;
import com.alibaba.fastjson2.*;
import org.springframework.core.env.Environment;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Separate node-local DB: never part of the resource KV, federation or generic metadata exports. */
public final class SettingsDatabase implements AutoCloseable {
    private final RocksMetadataStore store;
    private final byte[] key;
    public SettingsDatabase(Path root) throws IOException {
        Path keyFile = root.resolve("kernel/security/settings.key");
        boolean initialized = Files.exists(root.resolve("kernel/settings/CURRENT"));
        if (!Files.exists(keyFile)) {
            if (initialized) throw new IOException("Settings protection key is missing; restore kernel/security/settings.key with kernel/settings");
            Files.createDirectories(keyFile.getParent());
            byte[] generated = new byte[32]; new SecureRandom().nextBytes(generated);
            Path stage = Files.createTempFile(keyFile.getParent(), ".settings-", ".key");
            try {
                Files.setPosixFilePermissions(stage, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.write(stage, generated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
                Files.move(stage, keyFile, StandardCopyOption.ATOMIC_MOVE);
                DurableFiles.syncDirectory(keyFile.getParent());
            } finally { Files.deleteIfExists(stage); }
        }
        key = Files.readAllBytes(keyFile);
        if (key.length != 32) throw new IOException("Invalid settings protection key");
        store = new RocksMetadataStore(root.resolve("kernel/settings"));
    }
    public JSONObject read() throws IOException {
        String raw = store.get("settings");
        if (raw == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(bytes,12)));
            return JSON.parseObject(new String(c.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IOException("Cannot decrypt node settings; restore the matching database and key", e); }
    }
    public void write(JSONObject state) throws IOException {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = c.doFinal(state.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] all = Arrays.copyOf(nonce, nonce.length+encrypted.length); System.arraycopy(encrypted,0,all,nonce.length,encrypted.length);
            store.put("settings", Base64.getEncoder().encodeToString(all));
        } catch (Exception e) { throw new IOException("Cannot persist node settings", e); }
    }
    public JSONObject initialize(Environment env, Path root) throws IOException {
        JSONObject existing = read(); if (existing != null) return existing;
        Map<String,Object> values = new LinkedHashMap<>();
        Path portable = root.resolve("settings-portable.json");
        if (Files.exists(portable)) values.putAll(JSON.parseObject(Files.readString(portable)));
        for (var entry : SettingsValues.FIELDS.entrySet()) {
            var field = entry.getValue();
            String supplied = env.getProperty(field.legacy());
            if (supplied == null) supplied = env.getProperty(field.property());
            if (supplied == null) continue;
            Object value = supplied;
            if (field.initial() instanceof Boolean) {
                if (!Set.of("true", "false").contains(supplied.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Invalid legacy boolean: "+field.legacy());
                value = Boolean.parseBoolean(supplied);
            } else if (field.initial() instanceof Integer) value = Integer.parseInt(supplied);
            values.put(entry.getKey(),value);
        }
        // Existing node identity is authoritative during first upgrade, including restore seeds.
        Path manifest = root.resolve("kernel/cluster/node.json");
        if (Files.exists(manifest)) values.put("webEntrypoint", JSON.parseObject(Files.readString(manifest)).getString("endpoint"));
        else if (!values.containsKey("webEntrypoint") && Files.exists(root.resolve("restore-plan.json"))) values.put("webEntrypoint", JSON.parseObject(Files.readString(root.resolve("restore-plan.json"))).getString("endpoint"));
        boolean incompleteS3 = Boolean.TRUE.equals(values.get("s3Enabled"))
                && (Objects.toString(values.get("s3AccessKey"), "").isBlank() || Objects.toString(values.get("s3SecretKey"), "").isBlank());
        if (incompleteS3) values.put("s3Enabled",false);
        SettingsValues validated = new SettingsValues(values);
        JSONObject state = new JSONObject(); state.put("revision",1L); state.put("values",validated.map());
        if (incompleteS3) state.put("migrationWarning","旧 S3 配置缺少完整凭证，服务保持关闭；请填写凭证后在设置页开启");
        write(state); return state;
    }
    @Override public void close() { store.close(); Arrays.fill(key,(byte)0); }
}
