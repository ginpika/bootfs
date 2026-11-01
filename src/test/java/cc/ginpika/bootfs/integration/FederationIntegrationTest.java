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

    @Test @Timeout(90) void gbkTextEditingAndClashSubscriptionWorkThroughTheOwningNode() throws Exception {
        Node owner = new Node("gbk-owner", false), frontend = new Node("gbk-frontend", false);
        join(frontend, owner);
        var gbk = java.nio.charset.Charset.forName("GBK");
        String yaml = "proxies:\r\n  - {name: 中文节点, type: vless, server: example.com, port: 443, uuid: 11111111-1111-4111-8111-111111111111}\r\n";
        String id = owner.bean(FileService.class).publish(new java.io.ByteArrayInputStream(yaml.getBytes(gbk)), "gbk-clash.yaml", false).file().getUuid();
        owner.bean(MeiliSearchService.class).updateDocumentTags("full-text", id,
                List.of(cc.ginpika.bootfs.domain.dto.Tag.manual("clash")));
        owner.bean(cc.ginpika.bootfs.sso.SsoConfig.class).setEnabled(true);
        var read = request(frontend, "GET", "/api/file/" + id + "/text", null, null);
        assertEquals(200, read.statusCode(), read.body());
        JSONObject original = JSON.parseObject(read.body());
        assertEquals("GBK", original.getString("encoding"));
        assertEquals(yaml, original.getString("content"));
        var explicit = request(frontend, "GET", "/api/file/" + id + "/text?encoding=GBK", null, null);
        assertEquals(200, explicit.statusCode(), explicit.body());
        String updated = yaml.replace("中文节点", "更新节点");
        String body = new JSONObject(Map.of("content", updated, "version", original.getString("version"), "encoding", "GBK")).toJSONString();
        var saved = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, saved.statusCode(), saved.body());
        JSONObject document = JSON.parseObject(saved.body());
        String replacement = document.getString("uuid");
        assertEquals("GBK", document.getString("encoding"));
        assertArrayEquals(updated.getBytes(gbk), Files.readAllBytes(Path.of(owner.bean(Context.class).query(replacement).getPath())));
        var retry = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(replacement, JSON.parseObject(retry.body()).getString("uuid"));
        owner.bean(Context.class).reload();
        assertEquals("GBK", owner.bean(TextFileService.class).read(replacement).encoding());
        var details = request(frontend, "GET", "/api/file/" + replacement + "/clash", null, null);
        assertEquals(200, details.statusCode(), details.body());
        assertEquals("更新节点", JSON.parseObject(details.body()).getJSONArray("nodes").getJSONObject(0).getString("name"));
        var subscription = request(owner, "GET", "/p/" + replacement + "?p=vless", null, null);
        assertEquals(200, subscription.statusCode(), subscription.body());
        String links = new String(Base64.getDecoder().decode(subscription.body()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(links.contains("%E6%9B%B4%E6%96%B0%E8%8A%82%E7%82%B9"));
        var raw = http.send(HttpRequest.newBuilder(URI.create(owner.url + "/p/" + replacement)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, raw.statusCode());
        assertFalse(raw.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).contains("charset=utf-8"));
        assertArrayEquals(updated.getBytes(gbk), raw.body());
    }

    @Test @Timeout(90) void clashDetailsAndLatencyRunAtTheOwnerAndPublicSubscriptionKeepsItsFormat() throws Exception {
        try (var tcp = new java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())) {
            Node owner = new Node("clash-owner", false), frontend = new Node("clash-frontend", false);
            join(frontend, owner);
            String yaml = "proxies:\n"
                    + "  - {name: Local VLESS, type: vless, server: 127.0.0.1, port: " + tcp.getLocalPort()
                    + ", uuid: 11111111-1111-4111-8111-111111111111}\n"
                    + "  - {name: Local SS, type: ss, server: 127.0.0.1, port: " + tcp.getLocalPort()
                    + ", cipher: aes-128-gcm, password: private-test-password}\n";
            String id = owner.upload("clash.yaml", yaml);
            owner.bean(MeiliSearchService.class).updateDocumentTags("full-text", id,
                    List.of(cc.ginpika.bootfs.domain.dto.Tag.manual("clash")));
            owner.bean(cc.ginpika.bootfs.sso.SsoConfig.class).setEnabled(true);
            assertEquals(302, request(owner, "GET", "/api/file/" + id + "/clash", null, null).statusCode());
            assertEquals(302, request(owner, "POST", "/api/file/" + id + "/clash/latency", null, null).statusCode());
            var details = request(frontend, "GET", "/api/file/" + id + "/clash", null, null);
            assertEquals(200, details.statusCode(), details.body());
            assertEquals(2, JSON.parseObject(details.body()).getJSONArray("nodes").size());
            assertFalse(details.body().contains("private-test-password"));
            assertFalse(details.body().contains("11111111-1111-4111-8111-111111111111"));
            var latency = request(frontend, "POST", "/api/file/" + id + "/clash/latency?offset=0&limit=8", null, null);
            assertEquals(200, latency.statusCode(), latency.body());
            var measurements = JSON.parseObject(latency.body()).getJSONArray("nodes");
            assertEquals(2, measurements.size());
            assertEquals("ok", measurements.getJSONObject(0).getString("status"));
            assertTrue(measurements.getJSONObject(0).getLongValue("latencyMs") > 0);
            assertEquals(measurements.getJSONObject(0).getLongValue("latencyMs"), measurements.getJSONObject(1).getLongValue("latencyMs"));
            var redirected = request(frontend, "GET", "/p/" + id + "?p=vless", null, null);
            assertEquals(302, redirected.statusCode());
            assertEquals(owner.url + "/p/" + id + "?p=vless", redirected.headers().firstValue("Location").orElseThrow());
            var subscription = request(owner, "GET", "/p/" + id + "?p=vless", null, null);
            assertEquals(200, subscription.statusCode(), subscription.body());
            String links = new String(Base64.getDecoder().decode(subscription.body()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(links.contains("vless://")); assertTrue(links.contains("ss://"));
            assertEquals(yaml, request(owner, "GET", "/p/" + id, null, null).body());
            assertEquals(yaml, request(frontend, "GET", "/p/" + id, null, null).body());
        }
    }

    @Test @Timeout(90) void textEditingUsesSignedOwnerRequestsAndRetriesAfterTheOriginalWasRetired() throws Exception {
        Node owner = new Node("text-owner", false), frontend = new Node("text-frontend", false);
        join(frontend, owner);
        String id = owner.upload("settings.yaml", "port: 80\n");
        owner.bean(cc.ginpika.bootfs.sso.SsoConfig.class).setEnabled(true);
        assertEquals(302, request(owner, "GET", "/api/file/" + id + "/text", null, null).statusCode());
        assertEquals(302, request(owner, "PUT", "/api/file/" + id + "/text", "{}", null).statusCode());
        String token = frontend.store().tokenFor(owner.url);
        assertEquals(401, request(owner, "GET", "/api/file/" + id + "/text", null, token).statusCode());
        assertEquals(401, request(owner, "PUT", "/api/file/" + id + "/text", "{}", token).statusCode());
        var read = request(frontend, "GET", "/api/file/" + id + "/text", null, null);
        assertEquals(200, read.statusCode(), read.body());
        JSONObject original = JSON.parseObject(read.body());
        assertEquals("port: 80\n", original.getString("content"));
        String body = new JSONObject(Map.of("content", "port: 443\n", "version", original.getString("version"))).toJSONString();
        var saved = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, saved.statusCode(), saved.body());
        String replacement = JSON.parseObject(saved.body()).getString("uuid");
        assertNotEquals(id, replacement); assertNull(owner.bean(Context.class).query(id));
        var retry = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(replacement, JSON.parseObject(retry.body()).getString("uuid"));
        String stale = new JSONObject(Map.of("content", "port: 8080\n", "version", original.getString("version"))).toJSONString();
        assertEquals(409, request(frontend, "PUT", "/api/file/" + id + "/text", stale, null).statusCode());
        assertEquals("port: 443\n", owner.bean(TextFileService.class).read(replacement).content());
    }

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
