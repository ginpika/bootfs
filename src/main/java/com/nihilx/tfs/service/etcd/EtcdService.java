package com.nihilx.tfs.service.etcd;

import com.nihilx.tfs.config.EtcdConfig;
import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.core.IdGenerator;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EtcdService {
    public Client client = null;
    @Autowired
    private EtcdConfig etcdConfig;
    @Autowired
    private TfsConfig tfsConfig;
    @Autowired
    Context context;

    private final List<String> nodes = new ArrayList<>();

    @EventListener
    public void onRefresh(ContextRefreshedEvent event) {
        client = Client.builder().endpoints(etcdConfig.getEndpoints().toArray(new String[0])).build();
        log.info("[EtcdService] etcd client is ready");
        try {
            registry();
        } catch (IOException e) {
            e.printStackTrace();
            log.info("配置中的 etcd 服务不可用，启用环境内的 etcd");
            client = Client.builder().endpoints("http://127.0.0.1:2379").build();
        }
        log.info("[EtcdService] etcd client registry success");
        log.info("node : {}", context.uuid);
        ping();
        log.info("current cluster nodes count: {}", this.nodes.size());
    }

    @EventListener
    public void onClosed(ContextRefreshedEvent event) {}

    public void put(String k, String v) {
        ByteSequence key = ByteSequence.from(k.getBytes());
        ByteSequence value = ByteSequence.from(v.getBytes());
        KV kv = client.getKVClient();
        kv.put(key, value).join();
        log.info("写入 etcd {} -> {}", key, value);
        kv.close();
    }

    public String getOne(String key) {
        KV kv = client.getKVClient();
        Optional<KeyValue> optionalKeyValue = kv.get(ByteSequence.from(key.getBytes())).join().getKvs().stream().findFirst();
        return optionalKeyValue.map(keyValue -> keyValue.getValue().toString()).orElse(null);
    }

    public List<String> getWithPrefix(String prefix) {
        return client.getKVClient()
                .get(ByteSequence.from(prefix.getBytes()), GetOption.builder()
                        .withPrefix(ByteSequence.from(prefix.getBytes())).build())
                .join()
                .getKvs().stream().map(KeyValue::getValue).map(ByteSequence::toString).collect(Collectors.toList());
    }

    public void deleteWithPrefix(String prefix) {
        log.info("deleteWithPrefix: {}", prefix);
        client.getKVClient()
                .delete(ByteSequence.from(prefix.getBytes()), DeleteOption.builder()
                        .withPrefix(ByteSequence.from(prefix.getBytes())).build())
                .join();
    }

    public boolean delete(String key) {
        long deleted = client.getKVClient().delete(ByteSequence.from(key.getBytes())).join().getDeleted();
        return deleted > 0;
    }

    private List<String> queryAllNodes() {
        return getWithPrefix("/cluster/node/");
    }

    public void registry() throws IOException {
        context.checkDataDir();
        if (tfsConfig.getUniqueId() == null) {
            String normalized = tfsConfig.getConfig().replace("\\", "/");
            String path = normalized.substring(0, normalized.lastIndexOf('/') + 1);
            log.info("尝试读取 : {}", path + "uuid");
            File uuidFile = new File(path + "uuid");
            if (!uuidFile.exists()) {
                String uuid = IdGenerator.getUniqueId();
                try (FileOutputStream fos = new FileOutputStream(uuidFile)) {
                    fos.write(uuid.getBytes());
                }
                context.uuid = uuid;
            } else {
                try (FileInputStream fis = new FileInputStream(uuidFile)) {
                    context.uuid = new String(fis.readAllBytes());
                }
            }
            this.put("/cluster/node/" + context.uuid, getServiceEndpoint());
        } else {
            this.put("/cluster/node/" + tfsConfig.getUniqueId(), getServiceEndpoint());
            context.uuid = tfsConfig.getUniqueId();
        }
    }

    private String getServiceEndpoint() {
        return tfsConfig.getWebEntrypoint();
    }

    public void ping() {
        client.getKVClient().get(ByteSequence.from("greeting".getBytes())).join().getKvs().stream()
                .map(KeyValue::getValue).map(ByteSequence::toString).forEach(System.out::println);
        nodes.addAll(queryAllNodes());
    }

    public List<String> getAllNodes() {
        return this.nodes;
    }

    public void putFile(String fileId, String localNodeUrl,  String metaJson) {
        this.put("/files/" + fileId, localNodeUrl);
        this.put("/files/" + fileId + "/meta", metaJson);
    }

    public void putFileReplica(String OriginFileId,String fileId, String localNodeUrl, String metaJson) {
        this.put("/files/" + OriginFileId + "/replicas/" + fileId, localNodeUrl);
        this.put("/files/" + OriginFileId + "/replicas/" + fileId + "/meta", metaJson);
        this.put("/files/" + fileId, localNodeUrl);
        this.put("/files/" + fileId + "/meta", metaJson);
    }

    public void delFileAndReplicas(String fileId) {
        deleteWithPrefix("/files/" + fileId);
    }

    public int getMainResourceCount() {
        String val = this.getOne("/properties/main-resources-count/" + this.context.uuid);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return 0;
        }
    }

    public void updateMainResourceCount(int count) {
        this.put("/properties/main-resources-count/" + this.context.uuid, String.valueOf(count));
    }

    // TODO 合并 registry 逻辑，作为生命周期对 Context 负责
    public void online() throws IOException {
        registry();
    }

    public void offline() {

    }
}
