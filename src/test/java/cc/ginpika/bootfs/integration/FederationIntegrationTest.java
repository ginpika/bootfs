package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.BootFS;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.kernel.cluster.*;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FederationIntegrationTest {
    @TempDir Path root;
    final List<Node> nodes=new ArrayList<>();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
    class Node implements AutoCloseable {
        final String id,url;
        ConfigurableApplicationContext app;
        Node(String id,boolean replicated) throws Exception {
            this.id=id; int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
            url="http://127.0.0.1:"+port;
            app=new SpringApplicationBuilder(BootFS.class).logStartupInfo(false).run(
                    "--server.port="+port,"--server.address=127.0.0.1","--tfs.root-path="+root.resolve(id),"--tfs.unique-id="+id,
                    "--tfs.web-entrypoint="+url,"--tfs.copies="+(replicated?2:1),"--sso.enabled=false","--s3.enabled=false",
                    "--tfs.ffmpeg-url=/nonexistent-ffmpeg","--bootfs.objects.write-policy="+(replicated?"replicated":"single-local"),
                    "--bootfs.objects.required-copies="+(replicated?2:0),"--bootfs.cluster.transition-interval-ms=600000",
                    "--bootfs.cleanup.interval-ms=600000");
            nodes.add(this);
        }
        <T> T bean(Class<T> type) { return app.getBean(type); }
        DynamicMetadataStore store() { return (DynamicMetadataStore)bean(MetadataStore.class); }
        String upload(String name,String content) throws Exception { return bean(FileService.class).publish(new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name,false).file().getUuid(); }
        @Override public void close() { if(app!=null) { app.close(); app=null; } }
    }
    HttpResponse<String> request(Node node,String method,String path,String json,String token) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(node.url+path)).timeout(java.time.Duration.ofSeconds(8));
        if(token!=null) request.header("X-BootFS-Cluster-Token",token);
        if(json!=null) request.header("Content-Type","application/json");
        return http.send(request.method(method,json==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    void join(Node joining,Node target) throws Exception {
        var targetCoordinator=target.bean(ClusterCoordinator.class);
        joining.bean(ClusterCoordinator.class).link(target.url,targetCoordinator.issuePairingCode().getString("code")); flush();
    }
    void flush() throws Exception { for(int i=0;i<3;i++) for(Node n:nodes) if(n.app!=null) n.store().advance(); }
    @AfterEach void close() { for(Node n:nodes) n.close(); }

    @Test @Timeout(90) void federatedQueryAndMutationRunAtTheOwnerAndDepartingNodeKeepsAllOfItsData() throws Exception {
        Node a=new Node("alpha",false),x=new Node("portable",false),b=new Node("beta",false);
        String own=x.upload("own.txt","portable original"),other=a.upload("other.txt","alpha original"); b.upload("b.txt","beta original");
        join(x,a);
        JSONObject page=JSON.parseObject(request(x,"GET","/queryPageOffset?pageSize=20",null,null).body());
        assertEquals(2,page.getIntValue("total")); assertFalse(page.getBooleanValue("partial"));
        assertNull(x.store().get("/documents/full-text/"+other));
        assertNull(a.store().get("/documents/full-text/"+own));
        HttpResponse<String> renamed=request(a,"PUT","/api/file/"+own+"/rename","{\"fileName\":\"renamed.txt\"}",null);
        assertEquals(200,renamed.statusCode(),renamed.body());
        assertEquals("renamed.txt",x.bean(Context.class).query(own).getFileName());
        assertNull(a.store().get("/documents/full-text/"+own));
        assertEquals(200,request(a,"POST","/api/file/"+own+"/tags","[{\"namespace\":\"manual\",\"name\":\"travel\"}]",null).statusCode());
        String oldToken=a.store().tokenFor(x.url);
        x.store().leave(); flush();
        assertEquals(0,x.store().status().getIntValue("pendingNotifications"));
        assertEquals(401,request(x,"GET","/api/cluster/data/resource/"+own,null,oldToken).statusCode());
        assertEquals("portable original",request(x,"GET","/f/"+own,null,null).body());
        assertEquals(1,JSON.parseObject(request(x,"GET","/queryPageOffset",null,null).body()).getIntValue("total"));
        join(x,b);
        assertEquals("renamed.txt",x.bean(Context.class).query(own).getFileName());
        assertNull(x.store().get("/documents/full-text/"+other));
        assertEquals(2,JSON.parseObject(request(x,"GET","/queryPageOffset",null,null).body()).getIntValue("total"));
        b.close();
        x.upload("offline.txt","local while peers unavailable");
        JSONObject partial=JSON.parseObject(request(x,"GET","/queryPageOffset",null,null).body());
        assertTrue(partial.getBooleanValue("partial")); assertEquals(2,partial.getIntValue("total"));
        x.store().leave();
        assertFalse(JSON.parseObject(request(x,"GET","/queryPageOffset",null,null).body()).getBooleanValue("partial"));
    }
    @Test @Timeout(90) void wholeFileReplicaCarriesOriginalMetadataAndIsReadableAfterOwnerLeaves() throws Exception {
        Node owner=new Node("owner",true),backup=new Node("backup",false);
        join(backup,owner);
        String id=owner.upload("archive.txt","complete bytes");
        String replica=UUID.nameUUIDFromBytes((backup.id+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        owner.bean(MeiliSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"fileName","archive.txt","title","archive.txt","author","original author","description","portable description","contextUuid",owner.id)).toJSONString());
        owner.bean(FileTransferService.class).publicationCommitted(id);
        owner.bean(FileTransferService.class).resumePendingReplicas();
        assertNotNull(backup.bean(Context.class).query(replica));
        assertEquals("complete bytes",request(backup,"GET","/f/"+replica,null,null).body());
        assertEquals("original author",backup.bean(MeiliSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals("portable description",backup.bean(MeiliSearchService.class).findDocument("full-text",replica).getString("description"));
        assertNull(backup.store().get("/documents/full-text/"+id));
        owner.bean(MeiliSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"author","updated author")).toJSONString());
        backup.bean(FileTransferService.class).resumePendingReplicas();
        assertEquals("updated author",backup.bean(MeiliSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals(200,request(backup,"PUT","/api/file/"+replica+"/rename","{\"fileName\":\"my independent copy.txt\"}",null).statusCode());
        owner.bean(MeiliSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"author","later source change")).toJSONString());
        backup.bean(FileTransferService.class).resumePendingReplicas();
        assertEquals("updated author",backup.bean(MeiliSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals("my independent copy.txt",backup.bean(Context.class).query(replica).getFileName());
        backup.store().leave(); owner.close();
        assertEquals("complete bytes",request(backup,"GET","/f/"+replica,null,null).body());
        assertEquals("updated author",backup.bean(MeiliSearchService.class).findDocument("full-text",replica).getString("author"));
        backup.upload("independent.txt","new local write");
        assertTrue(backup.store().status().getBooleanValue("independent"));
    }
    @Test @Timeout(90) void formerCoordinatorCannotImpersonateRemainingMembersUsingPreviouslyKnownGrants() throws Exception {
        Node a=new Node("a",false),b=new Node("b",false),c=new Node("c",false);
        String id=b.upload("protected.txt","local authority"); join(b,a); join(c,a);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        String photo=b.bean(FileService.class).publish(new java.io.ByteArrayInputStream(png),"preview.png",false).file().getUuid();
        HttpResponse<byte[]> image=http.send(HttpRequest.newBuilder(URI.create(a.url+"/p/"+photo)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,image.statusCode()); assertArrayEquals(png,image.body());
        assertEquals(200,request(a,"GET","/file/"+id,null,null).statusCode());
        JSONObject state=JSON.parseObject(Files.readString(root.resolve("a/kernel/cluster/node.json")));
        String knownGrant=state.getJSONObject("admissions").getJSONObject("c").getJSONObject("links").getJSONObject("b").getString("outbound");
        a.store().leave(); flush();
        assertEquals(401,request(b,"GET","/api/cluster/data/resource/"+id,null,knownGrant).statusCode());
        assertThrows(ClusterHttp.HttpFailure.class,() -> ClusterHttp.call(b.url,"data/resource/"+id,null,knownGrant,2000,a.store().transportIdentity()));
        JSONObject valid=ClusterHttp.call(b.url,"data/resource/"+id,null,c.store().tokenFor(b.url),2000,c.store().transportIdentity());
        assertTrue(valid.getBooleanValue("found"));
    }
}
