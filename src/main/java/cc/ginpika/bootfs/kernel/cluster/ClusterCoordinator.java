package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClusterCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClusterCoordinator.class);
    private final MetadataStore metadata;
    private final Context context;
    private final Map<String,JSONObject> discovered = new ConcurrentHashMap<>();
    private final LanDiscovery discovery;
    public ClusterCoordinator(MetadataStore metadata, Context context, @Value("${bootfs.cluster.discovery.enabled:true}") boolean enabled,
                              @Value("${bootfs.cluster.token:}") String token, @Value("${bootfs.cluster.discovery.port:55929}") int port) throws IOException {
        this.metadata = metadata; this.context = context;
        if (metadata instanceof DynamicMetadataStore) ((DynamicMetadataStore)metadata).onAttached(() -> {
            try { context.reload(); } catch (IOException error) { throw new IllegalStateException("Cannot refresh joined inventory", error); }
        });
        LanDiscovery opened = null;
        if (enabled && metadata instanceof DynamicMetadataStore) {
            try { opened = new LanDiscovery(this,token,port); }
            catch (IOException unavailable) { log.warn("Automatic discovery unavailable; manual connect remains available: {}",unavailable.getMessage()); }
        }
        discovery = opened;
    }
    public DynamicMetadataStore dynamic() {
        if (!(metadata instanceof DynamicMetadataStore)) throw new IllegalStateException("Start with bootfs.metadata.mode=dynamic to use dynamic networking");
        return (DynamicMetadataStore)metadata;
    }
    public JSONObject status() {
        JSONObject result = metadata instanceof DynamicMetadataStore ? dynamic().status() : new JSONObject(Map.of("phase", "STATIC"));
        result.put("discovered", new ArrayList<>(discovered.values())); result.put("discoveryAvailable",discovery != null); return result;
    }
    void discovered(JSONObject peer) { peer.put("lastSeen", System.currentTimeMillis()); discovered.put(peer.getString("nodeId"), peer); }
    @Scheduled(fixedDelayString="${bootfs.cluster.transition-interval-ms:2000}")
    public void reconcile() {
        if (!(metadata instanceof DynamicMetadataStore)) return;
        try {
            dynamic().resumeLeave();
            dynamic().advance();
            discovered.values().removeIf(peer -> peer.getLongValue("lastSeen") < System.currentTimeMillis() - 15000);
            if (discovery != null) {
                JSONObject own = dynamic().status(); discovery.announce(own);
                if (dynamic().available() && own.getBooleanValue("independent") && own.getJSONObject("peers").size() == 1) {
                    for (JSONObject peer : discovered.values()) {
                        if ("ACTIVE".equals(peer.getString("phase")) && !peer.getString("groupId").equals(own.getString("groupId"))
                                && (!peer.getBooleanValue("independent") || peer.getString("groupId").compareTo(own.getString("groupId")) < 0)) {
                            dynamic().connect(peer.getString("endpoint")); break;
                        }
                    }
                }
            }
        } catch (Exception failure) { log.warn("Dynamic cluster transition will retry: {}", failure.getMessage()); }
    }
    @PreDestroy public void close() { if (discovery != null) discovery.close(); }
}
