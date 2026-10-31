package cc.ginpika.bootfs.kernel.cluster;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.*;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.kernel.backup.KernelBackup;
import cc.ginpika.bootfs.kernel.search.EmbeddedSearchEngine;
import cc.ginpika.bootfs.service.FederationService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamicClusterTest {
    @TempDir Path root;
    final List<Node> nodes=new ArrayList<>();
    class Node implements AutoCloseable {
        final TfsConfig config=new TfsConfig();
        final ClusterCredentials credentials;
        final java.util.concurrent.atomic.AtomicBoolean loseJoinReply=new java.util.concurrent.atomic.AtomicBoolean();
        final HttpServer http;
        final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool();
        DynamicMetadataStore store;
        EmbeddedSearchEngine index;
        Context context;
        ContextIO inventory;
        FederationService federation;
        ClusterCoordinator coordinator;
        Node(String id) throws Exception {
            http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); http.setExecutor(executor);
            config.setRootPath(root.resolve(id).toString()); config.setUniqueId(id); config.setWebEntrypoint("http://127.0.0.1:"+http.getAddress().getPort());
            credentials=new ClusterCredentials(config,""); open();
            http.createContext("/api/cluster/",exchange -> {
                try {
                    String path=exchange.getRequestURI().getPath().substring("/api/cluster/".length());
                    String token=exchange.getRequestHeaders().getFirst("X-BootFS-Cluster-Token");
                    boolean bootstrap=Set.of("ready","status","join","connect").contains(path);
                    String actor=store.actor(token,bootstrap);
                    if (!"pairing/redeem".equals(path) && actor==null) { exchange.sendResponseHeaders(401,-1); return; }
                    JSONObject request="POST".equals(exchange.getRequestMethod()) ? JSON.parseObject(exchange.getRequestBody().readAllBytes()) : null;
                    JSONObject result=new JSONObject(Map.of("accepted",true));
                    switch(path) {
                        case "pairing/redeem" -> result=coordinator.redeemPairingCode(request);
                        case "ready","status" -> result=store.status();
                        case "join" -> { result=store.join(request,actor); if(loseJoinReply.getAndSet(false)) throw new IOException("Join reply was lost after commit"); }
                        case "connect" -> store.connect(request.getString("url"));
                        case "membership" -> store.membership(request,actor);
                        case "departure" -> store.departure(request,actor);
                        case "remove" -> store.remove(request.getString("nodeId"));
                        case "data/search" -> result=federation.localSearch(request);
                        default -> {
                            if (path.startsWith("data/resource/")) result=federation.localResource(path.substring("data/resource/".length()));
                            else throw new IOException("Unknown test endpoint");
                        }
                    }
                    byte[] body=result.toJSONString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
                } catch(Exception failure) {
                    byte[] body=new JSONObject(Map.of("error",Objects.toString(failure.getMessage(),"failure"))).toJSONString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409,body.length); exchange.getResponseBody().write(body);
                } finally { exchange.close(); }
            }); http.start(); nodes.add(this);
        }
        void open() throws Exception {
            store=new DynamicMetadataStore(config); NodeIdentity identity=new NodeIdentity(config); inventory=new ContextIO(store,config,identity);
            context=new Context(config,inventory,identity); context.load(); index=new EmbeddedSearchEngine(store,root.resolve(config.getUniqueId()+"-index"));
            MeiliSearchService search=new MeiliSearchService(index); federation=new FederationService(store,search,context);
            coordinator=new ClusterCoordinator(store,context,false,credentials,55929);
        }
        void restart() throws Exception { federation.close(); index.close(); store.close(); open(); }
        void resource(String id,String body) throws Exception {
            Path path=Path.of(config.getPathPrefix(),id); Files.createDirectories(path.getParent()); Files.writeString(path,body);
            var file=new cc.ginpika.bootfs.domain.dto.FileObject(path.toString(),id,body+".txt",Files.size(path)); context.record(file,id);
            store.put("/files/"+id,config.getWebEntrypoint()+"/f/"+id);
            store.put("/files/"+id+"/meta",new JSONObject(Map.of("size",Files.size(path),"sha256",cc.ginpika.bootfs.kernel.object.ContentHash.sha256(path))).toJSONString());
            index.upsert("full-text",new JSONObject(Map.of("uuid",id,"title",body,"fileName",body+".txt","size",Files.size(path),"contextUuid",config.getUniqueId())));
        }
        @Override public void close() throws Exception { http.stop(0); executor.shutdownNow(); federation.close(); index.close(); store.close(); }
    }
    void join(Node joining,Node target) throws Exception { joining.coordinator.link(target.config.getWebEntrypoint(),target.coordinator.issuePairingCode().getString("code")); flush(); }
    void flush() throws Exception { for(int i=0;i<3;i++) for(Node n:nodes) n.store.advance(); }
    @AfterEach void close() throws Exception { for(Node node:nodes) node.close(); }

    @Test @Timeout(30) void fullLifetimeStandaloneClusterAStandaloneClusterBPreservesLocalAuthority() throws Exception {
        Node a=new Node("a"), x=new Node("x"), b=new Node("b"); x.resource("mine","private original"); a.resource("a-only","alpha"); b.resource("b-only","beta");
        Map<String,String> original=new TreeMap<>(x.store.scan("")); String owner=x.store.nodeId(); String initial=x.store.status().getString("groupId");
        join(x,a); assertEquals(a.store.status().getString("groupId"),x.store.status().getString("groupId"));
        assertEquals(original,x.store.scan("")); assertNull(x.store.get("/documents/full-text/a-only")); assertNull(a.store.get("/documents/full-text/mine"));
        assertEquals(2,x.federation.search("",List.of(),0,20,false).getIntValue("total"));
        assertEquals(1,x.index.search("full-text","",List.of(),0,20,false,null,false).total);
        String oldToken=a.store.tokenFor(x.store.endpoint());
        x.store.leave(); flush(); assertTrue(x.store.status().getBooleanValue("independent"));
        assertNotEquals(initial,x.store.status().getString("groupId")); assertNull(x.store.actor(oldToken,false)); assertFalse(a.store.peerEndpoints().containsKey(owner));
        x.store.put("custom","third-year write"); x.restart(); assertTrue(x.store.status().getBooleanValue("independent"));
        assertEquals("third-year write",x.store.get("custom")); assertEquals("private original",Files.readString(Path.of(x.config.getPathPrefix(),"mine")));
        join(x,b); assertEquals(b.store.status().getString("groupId"),x.store.status().getString("groupId"));
        assertEquals(owner,x.store.nodeId()); assertNull(x.store.get("/documents/full-text/b-only")); assertNull(b.store.get("/documents/full-text/mine"));
        assertEquals(2,x.federation.search("",List.of(),0,20,false).getIntValue("total")); assertEquals("third-year write",x.store.get("custom"));
        assertEquals(original.get("/documents/full-text/mine"),x.store.get("/documents/full-text/mine"));
        x.federation.close(); x.index.close(); x.store.close();
        Path backup=root.resolve("x-backup"); KernelBackup.export(List.of(Path.of(x.config.getRootPath())),backup,true); KernelBackup.verify(backup);
        x.open();
    }
    @Test @Timeout(30) void anyMemberCanPairNewNodesAndOfflinePeersNeverBlockLocalSearchOrWrites() throws Exception {
        Node a=new Node("a"),b=new Node("b"),c=new Node("c"); a.resource("aa","alpha"); b.resource("bb","beta"); c.resource("cc","gamma");
        join(b,a); join(c,b); // Joining through a non-coordinator installs pairwise grants for the whole group.
        assertEquals(3,c.store.status().getJSONObject("peers").size());
        assertEquals(3,b.federation.search("",List.of(),0,10,false).getIntValue("total"));
        String bOwner=b.credentials.token(); assertNull(a.store.actor(bOwner,false));
        a.http.stop(0); c.http.stop(0);
        b.store.put("offline","still writable"); b.resource("new","offline local");
        assertEquals("still writable",b.store.get("offline")); assertEquals(2,b.index.search("full-text","",List.of(),0,10,false,null,false).total);
        JSONObject result=b.federation.search("",List.of(),0,10,false); assertTrue(result.getBooleanValue("partial")); assertEquals(2,result.getIntValue("total"));
        b.store.leave(); assertTrue(b.store.status().getBooleanValue("independent")); b.restart(); assertTrue(b.store.status().getBooleanValue("independent"));
        assertEquals("still writable",b.store.get("offline")); assertFalse(b.federation.search("",List.of(),0,10,false).getBooleanValue("partial"));
    }
    @Test @Timeout(30) void coordinatorCanLeaveAndRemainingNodesCanKeepServingAndAdmitNewMembers() throws Exception {
        Node a=new Node("a"),b=new Node("b"),c=new Node("c"); join(b,a); join(c,a); a.store.leave(); flush();
        assertTrue(a.store.status().getBooleanValue("independent")); assertEquals("b",b.store.status().getString("coordinatorId"));
        Node d=new Node("d"); join(d,c); assertEquals(3,d.store.peerEndpoints().size()+1); assertFalse(d.store.peerEndpoints().containsKey("a"));
        b.store.remove("c"); flush(); assertTrue(c.store.status().getBooleanValue("independent")); c.store.put("removed","usable");
        assertEquals("usable",c.store.get("removed"));
    }
    @Test void codesDoNotExportOwnerSecretsAndWrongOrUsedCodesCannotJoinOtherNodes() throws Exception {
        Node a=new Node("a"),b=new Node("b"),c=new Node("c");
        String code=a.coordinator.issuePairingCode().getString("code");
        assertThrows(IOException.class,() -> b.coordinator.link(a.store.endpoint(),code.equals("00000000")?"00000001":"00000000"));
        b.coordinator.link(a.store.endpoint(),code); flush();
        assertNotEquals(a.credentials.token(),b.store.tokenFor(a.store.endpoint()));
        assertThrows(IOException.class,() -> c.coordinator.link(a.store.endpoint(),code));
        assertFalse(a.store.status().toJSONString().contains(a.credentials.token()));
        assertThrows(IOException.class,() -> a.coordinator.link(a.store.endpoint(),code));
    }
    @Test @Timeout(30) void lostJoinReplyCanBeRetriedWithoutImportingOrChangingLocalResources() throws Exception {
        Node a=new Node("a"),b=new Node("b"); b.resource("local","unchanged");
        Map<String,String> original=b.store.scan(""); String code=a.coordinator.issuePairingCode().getString("code");
        a.loseJoinReply.set(true);
        assertThrows(IOException.class,() -> b.coordinator.link(a.store.endpoint(),code));
        assertTrue(b.store.status().getBooleanValue("independent")); assertEquals(2,a.store.status().getJSONObject("peers").size());
        b.restart(); b.store.connect(a.store.endpoint()); flush();
        assertEquals(a.store.status().getString("groupId"),b.store.status().getString("groupId")); assertEquals(original,b.store.scan(""));
    }
    @Test void replicaSynchronizationVersionsAreResourceScopedRatherThanNodeWide() throws Exception {
        Node a=new Node("a"); a.resource("original","source");
        String version=a.store.get("/resource-revisions/original");
        a.resource("another-copy","unrelated replica snapshot"); a.store.put("/replica-sources/another-copy","{}");
        assertEquals(version,a.store.get("/resource-revisions/original"));
        String key="/documents/full-text/original",raw=a.store.get(key); JSONObject edited=JSON.parseObject(raw); edited.put("author","local edit");
        assertTrue(a.store.compareAndSet(key,raw,edited.toJSONString()));
        assertTrue(Long.parseLong(a.store.get("/resource-revisions/original"))>Long.parseLong(version));
    }
}
