package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClusterCoordinator {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ClusterCoordinator.class);
    private final MetadataStore metadata;
    private final ClusterCredentials credentials;
    private final Map<String,JSONObject> discovered=new ConcurrentHashMap<>();
    private volatile String invitationGroup;
    private final java.util.concurrent.ExecutorService notifications=java.util.concurrent.Executors.newSingleThreadExecutor(task -> { Thread thread=new Thread(task,"bootfs-membership-notifications"); thread.setDaemon(true); return thread; });
    private final java.util.concurrent.atomic.AtomicBoolean delivering=new java.util.concurrent.atomic.AtomicBoolean();
    public ClusterCoordinator(MetadataStore metadata, Context context, @Value("${bootfs.cluster.discovery.enabled:false}") boolean unusedDiscovery,
                              ClusterCredentials credentials, @Value("${bootfs.cluster.discovery.port:55929}") int unusedPort) {
        this.metadata=metadata; this.credentials=credentials;
    }
    public DynamicMetadataStore dynamic() {
        if (!(metadata instanceof DynamicMetadataStore)) throw new IllegalStateException("Legacy shared-Raft storage must be restored to a local node before federation");
        return (DynamicMetadataStore)metadata;
    }
    public JSONObject status() {
        JSONObject result=metadata instanceof DynamicMetadataStore ? dynamic().status() : new JSONObject(Map.of("phase","STATIC","metadataMode","legacy-raft"));
        result.put("discovered",new ArrayList<>(discovered.values())); result.put("discoveryAvailable",false); return result;
    }
    public JSONObject issuePairingCode() { invitationGroup=dynamic().status().getString("groupId"); return credentials.issueCode(); }
    public JSONObject redeemPairingCode(JSONObject request) throws IOException {
        if (!Objects.equals(invitationGroup,dynamic().status().getString("groupId"))) throw new IOException("验证码已失效，请重新生成");
        String incoming=credentials.redeem(request.getString("code"),request.getString("requestId"));
        dynamic().paired(request.getString("nodeId"), request.getString("url"), incoming, request.getString("returnToken"),request.getString("publicKey"));
        return new JSONObject(Map.of("token",incoming,"nodeId",dynamic().nodeId(),"url",dynamic().endpoint(),"publicKey",credentials.publicKey()));
    }
    public JSONObject link(String url) throws IOException { return link(url,null); }
    public synchronized JSONObject link(String url,String code) throws IOException {
        String target=DynamicMetadataStore.normalizeEndpoint(url);
        if (target.equals(dynamic().endpoint())) throw new IOException("请输入其他 BootFS 节点的地址");
        boolean paired=false;
        try { dynamic().tokenFor(target); paired=true; } catch(IOException unknown) { /* New peer needs a code. */ }
        if (!paired) {
            if (code==null || !code.matches("[0-9]{8}")) throw new IOException("请输入目标节点生成的 8 位验证码");
            String requestId=credentials.requestId(target,code), returning=credentials.requestId("return:"+target,code);
            JSONObject request=new JSONObject(Map.of("code",code,"requestId",requestId,"nodeId",dynamic().nodeId(),"url",dynamic().endpoint(),"returnToken",returning,"publicKey",credentials.publicKey()));
            JSONObject result=ClusterHttp.call(target,"pairing/redeem",request,null,5000);
            if (!target.equals(DynamicMetadataStore.normalizeEndpoint(result.getString("url")))) throw new IOException("配对地址与目标节点声明的地址不一致");
            dynamic().paired(result.getString("nodeId"),target,returning,result.getString("token"),result.getString("publicKey"));
        }
        JSONObject own=dynamic().ready(), remote=ClusterHttp.call(target,"ready",null,dynamic().tokenFor(target),5000,credentials);
        if (own.getString("nodeId").equals(remote.getString("nodeId"))) throw new IOException("请输入其他 BootFS 节点的地址");
        if (!own.getString("groupId").equals(remote.getString("groupId"))) {
            if (own.getBooleanValue("independent")) dynamic().connect(target);
            else if (remote.getBooleanValue("independent")) ClusterHttp.call(target,"connect",new JSONObject(Map.of("url",dynamic().endpoint())),dynamic().tokenFor(target),10000,credentials);
            else throw new IOException("请先退出当前集群，再加入另一个集群");
        }
        return new JSONObject(Map.of("accepted",true));
    }
    public JSONObject linkStatus(String url) throws IOException {
        String target=DynamicMetadataStore.normalizeEndpoint(url);
        JSONObject own=dynamic().status(), remote=ClusterHttp.call(target,"status",null,dynamic().tokenFor(target),3000,credentials);
        boolean connected=own.getString("groupId").equals(remote.getString("groupId"))
                && own.getJSONObject("peers").containsKey(remote.getString("nodeId")) && remote.getJSONObject("peers").containsKey(own.getString("nodeId"));
        return new JSONObject(Map.of("local",own,"remote",remote,"connected",connected,"pending",!connected && own.getString("groupId").equals(remote.getString("groupId"))));
    }
    void discovered(JSONObject peer) { discovered.put(peer.getString("nodeId"),peer); }
    @Scheduled(fixedDelayString="${bootfs.cluster.transition-interval-ms:2000}") public void reconcile() {
        if (!(metadata instanceof DynamicMetadataStore) || !delivering.compareAndSet(false,true)) return;
        try { notifications.submit(() -> {
            try { dynamic().advance(); } catch(IOException failure) { log.warn("Federation notification will retry: {}",failure.getMessage()); }
            finally { delivering.set(false); }
        }); } catch(java.util.concurrent.RejectedExecutionException closing) { delivering.set(false); }
    }
    @javax.annotation.PreDestroy public void close() { notifications.shutdownNow(); }
}
