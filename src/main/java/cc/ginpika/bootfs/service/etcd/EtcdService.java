package cc.ginpika.bootfs.service.etcd;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import java.util.*;

/** Compatibility facade for existing routes; storage is now the embedded metadata kernel. */
@Service
public class EtcdService {
    private final MetadataStore store;
    private final TfsConfig config;
    private final Context context;

    public EtcdService(MetadataStore store, TfsConfig config, Context context) {
        this.store = store;
        this.config = config;
        this.context = context;
    }
    @EventListener @Order(10)
    public void onRefresh(ContextRefreshedEvent event) { registry(); }
    public boolean writable() { return !(store instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore) || ((cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore)store).available(); }
    public void registry() { if (!writable()) return; put("/cluster/node/" + context.uuid, config.getWebEntrypoint()); }
    public void put(String key, String value) { store.put(key, value); }
    public String getOne(String key) { return store.get(key); }
    public NavigableMap<String,String> scan(String prefix) { return store.scan(prefix); }
    public void writeBatch(Map<String,String> puts, Set<String> deletes) { store.writeBatch(puts, deletes); }
    public List<String> getWithPrefix(String prefix) { return new ArrayList<>(scan(prefix).values()); }
    public void deleteWithPrefix(String prefix) { store.deletePrefix(prefix); }
    public boolean delete(String key) { return store.delete(key); }
    public boolean compareAndSet(String key, String expected, String replacement) { return store.compareAndSet(key,expected,replacement); }
    public boolean compareAndWrite(Map<String,String> expected,Map<String,String> puts,Set<String> deletes) { return store.compareAndWrite(expected,puts,deletes); }
    public void ping() { store.revision(); }
    public List<String> getAllNodes() { return new ArrayList<>(getNodesWithKeys().values()); }
    public Map<String,String> getNodesWithKeys() {
        if (store instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore dynamic) {
            Map<String,String> nodes=new TreeMap<>(dynamic.peerEndpoints()); nodes.put(dynamic.nodeId(),dynamic.endpoint()); return nodes;
        }
        Map<String,String> result = new LinkedHashMap<>();
        scan("/cluster/node/").forEach((k,v) -> result.put(k.substring("/cluster/node/".length()),v));
        return result;
    }
    public void putFile(String id, String url, String meta) {
        store.writeBatch(Map.of("/files/"+id,url,"/files/"+id+"/meta",meta),Set.of());
    }
    public void putFileReplica(String origin, String id, String url, String meta) {
        store.writeBatch(Map.of("/files/"+origin+"/replicas/"+id,url,
            "/files/"+origin+"/replicas/"+id+"/meta",meta,
            "/files/"+id,url,"/files/"+id+"/meta",meta),Set.of());
    }
    public void delFileAndReplicas(String id) {
        Set<String> keys = new HashSet<>(scan("/files/"+id+"/").keySet());
        keys.add("/files/"+id);
        // Remove reverse references without deleting neighboring IDs with a common prefix.
        scan("/files/").keySet().stream()
            .filter(k -> k.endsWith("/replicas/"+id) || k.endsWith("/replicas/"+id+"/meta"))
            .forEach(keys::add);
        store.writeBatch(Map.of(),keys);
    }
    public int getMainResourceCount() {
        String value = getOne("/properties/main-resources-count/"+context.uuid);
        return value == null ? 0 : Integer.parseInt(value);
    }
    public void updateMainResourceCount(int count) { put("/properties/main-resources-count/"+context.uuid,Integer.toString(count)); }
    public void online() { registry(); }
    public void offline() { delete("/cluster/node/"+context.uuid); }
}
