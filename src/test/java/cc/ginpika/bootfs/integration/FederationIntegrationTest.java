package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.file.TextFileService;
import cc.ginpika.bootfs.file.FileTransferService;
import cc.ginpika.bootfs.file.FileService;
import cc.ginpika.bootfs.BootFS;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.cluster.*;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.service.*;
import cc.ginpika.bootfs.search.ResourceSearchService;
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
        Node(String id,boolean replicated,String... extraProperties) throws Exception {
            this.id=id; int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
            url="http://127.0.0.1:"+port;
            List<String> arguments = new ArrayList<>(List.of(
                    "--server.port="+port,"--server.address=127.0.0.1","--tfs.root-path="+root.resolve(id),"--tfs.unique-id="+id,
                    "--tfs.web-entrypoint="+url,"--tfs.copies="+(replicated?2:1),"--sso.enabled=false","--sso.local-bypass.enabled=false","--s3.enabled=false",
                    "--bootfs.security.public-sharing.enabled=true",
                    "--tfs.ffmpeg-url=/nonexistent-ffmpeg","--bootfs.objects.write-policy="+(replicated?"replicated":"single-local"),
                    "--bootfs.objects.required-copies="+(replicated?2:0),"--bootfs.cluster.transition-interval-ms=600000",
                    "--bootfs.cleanup.interval-ms=600000"));
            arguments.addAll(List.of(extraProperties));
            app=new SpringApplicationBuilder(BootFS.class).logStartupInfo(false).run(arguments.toArray(String[]::new));
            nodes.add(this);
        }
        <T> T bean(Class<T> type) { return app.getBean(type); }
        void setSso(boolean enabled) throws Exception {
            var settings=bean(cc.ginpika.bootfs.settings.NodeSettings.class);
            var next=new LinkedHashMap<>(settings.current().map()); next.put("ssoEnabled",enabled);
            settings.update(((Number)settings.view().get("revision")).longValue(),new cc.ginpika.bootfs.settings.SettingsValues(next),false,false,store());
        }
        DynamicMetadataStore store() { return (DynamicMetadataStore)bean(MetadataStore.class); }
        String upload(String name,String content) throws Exception { return bean(FileService.class).publish(new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name,false).file().getUuid(); }
        @Override public void close() { if(app!=null) { app.close(); app=null; } }
    }
    HttpResponse<String> request(Node node,String method,String path,String json,String token) throws Exception {
        return request(node,method,path,json,token,null);
    }
    HttpResponse<String> request(Node node,String method,String path,String json,String token,String acceptEncoding) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(node.url+path)).timeout(java.time.Duration.ofSeconds(8));
        if(token!=null) request.header("X-BootFS-Cluster-Token",token);
        if(acceptEncoding!=null) request.header("Accept-Encoding",acceptEncoding);
        if(json!=null) request.header("Content-Type","application/json");
        return http.send(request.method(method,json==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    void join(Node joining,Node target) throws Exception {
        var targetCoordinator=target.bean(ClusterCoordinator.class);
        joining.bean(ClusterCoordinator.class).link(target.url,targetCoordinator.issuePairingCode().getString("code")); flush();
    }
    void flush() throws Exception { for(int i=0;i<3;i++) for(Node n:nodes) if(n.app!=null) n.store().advance(); }
    @AfterEach void close() { for(Node n:nodes) n.close(); }

    @Test @Timeout(90) void galleryRefreshesFromAnOrdinaryPeerWithTheCoordinatorOffline() throws Exception {
        Node coordinator = new Node("gallery-a", false);
        Node publisher = new Node("gallery-b", false);
        Node viewer = new Node("gallery-c", false);
        join(publisher, coordinator);
        join(viewer, coordinator);
        coordinator.close();

        String first = viewer.upload("first.webp", "first");
        assertEquals("no-store", request(viewer, "GET", "/gallery", null, null)
                .headers().firstValue("Cache-Control").orElseThrow());
        var unpublished = request(viewer, "GET", "/gallery/image/" + viewer.id + "/" + first, null, null);
        assertEquals(404, unpublished.statusCode());
        assertEquals("no-store", unpublished.headers().firstValue("Cache-Control").orElseThrow());
        var empty = galleryWall(viewer, null);
        assertEquals(404, empty.statusCode());
        assertEquals("no-store", empty.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200, request(viewer, "POST", "/publicAccess/" + first, null, null).statusCode());
        var initial = galleryWall(viewer, null);
        assertEquals(200, initial.statusCode());
        assertEquals("no-store", initial.headers().firstValue("Cache-Control").orElseThrow());
        String initialTag = initial.headers().firstValue("ETag").orElseThrow();
        assertEquals(List.of("/gallery/image/" + viewer.id + "/" + first), JSON.parseArray(initial.body()).toJavaList(String.class));

        String next = publisher.upload("next.webp", "next");
        var unchanged = galleryWall(viewer, initialTag);
        assertEquals(304, unchanged.statusCode(), "Uploading alone does not change the public wall");
        assertEquals("no-store", unchanged.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200, request(publisher, "POST", "/publicAccess/" + next, null, null).statusCode());
        var refreshed = galleryWall(viewer, initialTag);
        assertEquals(200, refreshed.statusCode(), refreshed.body());
        String newTag = refreshed.headers().firstValue("ETag").orElseThrow();
        assertNotEquals(initialTag, newTag);
        assertEquals(List.of("/gallery/image/" + publisher.id + "/" + next), JSON.parseArray(refreshed.body()).toJavaList(String.class));
        assertEquals(304, galleryWall(viewer, newTag).statusCode());

        assertEquals(200, request(publisher, "POST", "/publicAccess/" + next + "?enabled=false", null, null).statusCode());
        assertEquals(initial.body(), galleryWall(viewer, newTag).body());
    }

    private HttpResponse<String> galleryWall(Node node, String tag) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(node.url + "/setu-urls?count=1"))
                .timeout(java.time.Duration.ofSeconds(8));
        if (tag != null) request.header("If-None-Match", tag);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @Timeout(90) void galleryAndTvShareMediaAndSsoChatAcrossNodes() throws Exception {
        var sso = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sso.createContext("/api/sso/session", exchange -> {
            boolean signedIn = Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"), "").contains("sso_token=viewer-token");
            String body = signedIn
                    ? "{\"authenticated\":true,\"user\":{\"id\":\"viewer-1\",\"nickname\":\"Viewer\",\"role\":\"member\"}}"
                    : "{\"authenticated\":false}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        sso.start();
        try {
            Node a = new Node("media-a", false);
            Node b = new Node("media-b", false, "--sso.server-url=http://127.0.0.1:" + sso.getAddress().getPort(),
                    "--sso.allowed-roles=admin");
            join(b, a);

            String left = a.upload("left.jpg", "left"), right = b.upload("right.webp", "right");
            String privateImage = b.upload("private.png", "private");
            assertEquals(200, request(a, "POST", "/publicAccess/" + left, null, null).statusCode());
            assertEquals(200, request(a, "POST", "/publicAccess/" + right, null, null).statusCode());
            String leftUrl = "/gallery/image/" + a.id + "/" + left;
            String rightUrl = "/gallery/image/" + b.id + "/" + right;
            String privateUrl = "/gallery/image/" + b.id + "/" + privateImage;
            assertEquals(400, request(a, "GET", "/setu-urls?count=0", null, null).statusCode());
            assertEquals(400, request(a, "GET", "/setu-urls?count=481", null, null).statusCode());
            assertEquals(Set.of(leftUrl, rightUrl), new HashSet<>(JSON.parseArray(
                    request(a, "GET", "/setu-urls?count=8", null, null).body()).toJavaList(String.class)));
            assertEquals(Set.of(leftUrl, rightUrl), new HashSet<>(JSON.parseArray(
                    request(b, "GET", "/setu-urls?count=8", null, null).body()).toJavaList(String.class)));
            assertEquals("right", request(a, "GET", rightUrl + "?thumbnail=true", null, null).body());
            assertEquals(200, request(a, "HEAD", rightUrl, null, null).statusCode());
            assertEquals(404, request(a, "GET", privateUrl, null, null).statusCode());

            String videoA = tvFixture(a, "first.mp4"), videoB = tvFixture(b, "second.mp4");
            String other = tvFixture(b, "unscheduled.mp4");
            String keyA = a.id + ":" + videoA, keyB = b.id + ":" + videoB;
            Set<String> keys = new HashSet<>();
            for (Object video : JSON.parseArray(request(a, "GET", "/tv/api/videos", null, null).body())) {
                keys.add(((JSONObject) video).getString("key"));
            }
            assertEquals(Set.of(keyA, keyB, b.id + ":" + other), keys);
            var saved = request(a, "POST", "/tv/api/playlist", JSON.toJSONString(List.of(keyB, keyA)), null);
            assertEquals(200, saved.statusCode(), saved.body());
            awaitMedia(() -> JSON.parseObject(request(b, "GET", "/tv/status", null, null).body()).getBooleanValue("live"));
            assertEquals(List.of(keyB, keyA), JSON.parseArray(
                    request(b, "GET", "/tv/api/playlist", null, null).body()).toJavaList(String.class));
            String tsB = "/tv/ts/" + b.id + "/" + videoB + "/segment_00000.ts";
            String tsA = "/tv/ts/" + a.id + "/" + videoA + "/segment_00000.ts";
            assertTrue(request(a, "GET", "/tv/playlist.m3u8", null, null).body().contains(tsB));
            assertEquals("second.mp4", request(a, "GET", tsB, null, null).body());
            assertEquals("first.mp4", request(b, "GET", tsA, null, null).body());
            assertEquals(404, request(a, "GET", "/tv/ts/" + b.id + "/" + other + "/segment_00000.ts", null, null).statusCode());
            assertEquals(401, request(a, "GET", "/api/cluster/data/tv/ts/" + videoA + "/segment_00000.ts", null, null).statusCode());
            assertEquals(200, request(a, "GET", "/tv", null, null).statusCode());
            assertEquals(200, request(a, "GET", "/tv/manage", null, null).statusCode());

            var settings = b.bean(cc.ginpika.bootfs.settings.NodeSettings.class);
            var privateSettings = new LinkedHashMap<>(settings.current().map());
            privateSettings.put("publicSharingEnabled", false);
            settings.update(((Number) settings.view().get("revision")).longValue(),
                    new cc.ginpika.bootfs.settings.SettingsValues(privateSettings), false, false, b.store());
            b.setSso(true);
            assertEquals(200, request(a, "GET", rightUrl, null, null).statusCode());
            assertEquals(200, request(a, "GET", tsB, null, null).statusCode());
            assertEquals(401, viewerRequest(b, "POST", "/tv/chat", "{\"text\":\"anonymous\"}", false, b.url).statusCode());
            assertEquals(403, viewerRequest(b, "POST", "/tv/chat", "{\"text\":\"cross-origin\"}", true, a.url).statusCode());
            assertEquals(403, viewerRequest(b, "GET", "/tv/manage", null, true, null).statusCode());
            assertEquals(200, viewerRequest(b, "GET", "/tv", null, true, null).statusCode());
            assertTrue(JSON.parseObject(viewerRequest(b, "GET", "/tv/chat/session", null, true, null).body()).getBooleanValue("authenticated"));
            var message = viewerRequest(b, "POST", "/tv/chat", "{\"text\":\"你好 <b>TV</b>\",\"author\":\"forged\"}", true, b.url);
            assertEquals(201, message.statusCode(), message.body());
            JSONObject posted = JSON.parseObject(message.body());
            assertEquals("Viewer", posted.getString("author"));
            awaitMedia(() -> request(a, "GET", "/tv/chat", null, null).body().contains(posted.getString("id")));
            assertEquals("你好 <b>TV</b>", JSON.parseArray(request(a, "GET", "/tv/chat", null, null).body())
                    .getJSONObject(0).getString("text"));

            b.setSso(false);
            assertEquals(200, request(b, "POST", "/publicAccess/" + right + "?enabled=false", null, null).statusCode());
            assertEquals(404, request(a, "GET", rightUrl, null, null).statusCode());
            assertEquals(200, request(b, "POST", "/tv/api/playlist", "[]", null).statusCode());
            awaitMedia(() -> !JSON.parseObject(request(a, "GET", "/tv/status", null, null).body()).getBooleanValue("live"));
            assertEquals(404, request(a, "GET", tsB, null, null).statusCode());
        } finally {
            sso.stop(0);
        }
    }

    private String tvFixture(Node node, String filename) throws Exception {
        String id = node.upload(filename, filename);
        Path folder = Files.createDirectories(root.resolve(node.id).resolve("data/hls").resolve(id));
        Files.writeString(folder.resolve("playlist.m3u8"), "#EXTM3U\n#EXTINF:8.50,\nsegment_00000.ts\n#EXT-X-ENDLIST\n");
        Files.writeString(folder.resolve("segment_00000.ts"), filename);
        return id;
    }

    private HttpResponse<String> viewerRequest(Node node, String method, String path, String data,
                                               boolean authenticated, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(node.url + path)).timeout(java.time.Duration.ofSeconds(8));
        if (authenticated) request.header("Cookie", "sso_token=viewer-token");
        if (origin != null) request.header("Origin", origin);
        if (data != null) request.header("Content-Type", "application/json");
        request.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @FunctionalInterface private interface MediaCondition { boolean ready() throws Exception; }
    private void awaitMedia(MediaCondition condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            if (condition.ready()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("The media change did not appear on the other node");
    }

    @Test @Timeout(90) void dashboardFromEitherNodeContainsIndependentPeerStatsAndHandlesAnOfflinePeer() throws Exception {
        Node a = new Node("dashboard-a", false), b = new Node("dashboard-b", false);
        join(b, a);
        a.upload("a.txt", "a");
        b.upload("b1.txt", "b1");
        b.upload("b2.txt", "b2");
        a.bean(cc.ginpika.bootfs.metrics.RequestStatsService.class).record(
                cc.ginpika.bootfs.metrics.RequestCategory.FILE, "GET", "/only-a", 200, 100, 5, "192.0.2.1");
        b.bean(cc.ginpika.bootfs.metrics.RequestStatsService.class).record(
                cc.ginpika.bootfs.metrics.RequestCategory.FILE, "GET", "/only-b", 200, 200, 10, "2001:db8::2");

        // The dashboard must use signed peer requests even when the target requires SSO.
        b.setSso(true);
        assertEquals(302, request(b, "GET", "/api/dashboard/node-stats", null, null).statusCode());
        assertDashboardNodes(a, a.id, b.id);
        b.setSso(false);
        a.setSso(true);
        assertDashboardNodes(b, b.id, a.id);
        a.setSso(false);

        b.close();
        JSONObject stats = JSON.parseObject(request(a, "GET", "/api/dashboard/stats", null, null).body());
        assertTrue(stats.getBooleanValue("succeed"), stats.toJSONString());
        JSONObject offline = stats.getJSONObject("data").getJSONArray("nodes").getJSONObject(1);
        assertEquals(b.id, offline.getString("nodeId"));
        assertFalse(offline.getBooleanValue("online"));
        assertFalse(offline.containsKey("system"));
        assertFalse(offline.containsKey("requests"));

        var removed = request(a, "POST", "/api/cluster/remove", new JSONObject(Map.of("nodeId", b.id)).toJSONString(), null);
        assertEquals(200, removed.statusCode(), removed.body());
        assertFalse(JSON.parseObject(removed.body()).getJSONObject("peers").containsKey(b.id));
        JSONObject refreshed = JSON.parseObject(request(a, "GET", "/api/dashboard/stats", null, null).body());
        JSONArray remaining = refreshed.getJSONObject("data").getJSONArray("nodes");
        assertEquals(1, remaining.size());
        assertEquals(a.id, remaining.getJSONObject(0).getString("nodeId"));
    }

    private void assertDashboardNodes(Node entry, String currentId, String remoteId) throws Exception {
        var response = request(entry, "GET", "/api/dashboard/stats", null, null);
        assertEquals(200, response.statusCode(), response.body());
        JSONObject result = JSON.parseObject(response.body());
        assertTrue(result.getBooleanValue("succeed"), response.body());
        JSONArray members = result.getJSONObject("data").getJSONArray("nodes");
        assertEquals(2, members.size());
        assertEquals(currentId, members.getJSONObject(0).getString("nodeId"));
        assertTrue(members.getJSONObject(0).getBooleanValue("isCurrent"));
        assertEquals(remoteId, members.getJSONObject(1).getString("nodeId"));
        for (int i = 0; i < members.size(); i++) {
            JSONObject member = members.getJSONObject(i);
            boolean isA = "dashboard-a".equals(member.getString("nodeId"));
            assertTrue(member.getBooleanValue("online"));
            assertNotNull(member.getJSONObject("system").getString("runtime"));
            assertTrue(member.getJSONObject("system").getJSONObject("memory").getLongValue("heapUsedBytes") > 0);
            assertEquals(isA ? 1 : 2, member.getJSONObject("resources").getIntValue("total"));
            assertTrue(member.getLongValue("appStartTime") > 0);
            assertNotNull(member.getJSONObject("traffic"));
            JSONArray recent = member.getJSONObject("requests").getJSONArray("recent");
            assertTrue(recent.stream().map(value -> (JSONObject) value).anyMatch(log ->
                    (isA ? "/only-a" : "/only-b").equals(log.getString("uri"))
                            && (isA ? "192.0.2.1" : "2001:db8::2").equals(log.getString("clientIp"))));
            assertFalse(recent.stream().map(value -> (JSONObject) value).anyMatch(log ->
                    (isA ? "/only-b" : "/only-a").equals(log.getString("uri"))));
        }
    }

    @Test @Timeout(90) void remoteAlbumKeepsItsPagesAndThumbnailsAccessibleThroughTheFrontend() throws Exception {
        Node owner = new Node("album-owner", false, "--server.compression.enabled=true",
                "--server.compression.min-response-size=1");
        Node frontend = new Node("album-frontend", false);
        join(frontend, owner);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        var archive = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(archive)) {
            for (String name : List.of("02.png", "01.png")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(png); zip.closeEntry();
            }
        }
        String uploaded = owner.bean(FileService.class).uploadComic(new org.springframework.mock.web.MockMultipartFile(
                "file", "album.zip", "application/zip", archive.toByteArray()));
        String id = uploaded.substring(uploaded.lastIndexOf('/') + 1);
        JSONObject canonical = owner.bean(ResourceSearchService.class).findDocument("full-text", id);
        JSONArray storedPages = canonical.getJSONArray("resources");
        assertEquals(2, storedPages.size());
        owner.setSso(true);

        URI ownerAlbum = URI.create(owner.url + "/api/album/" + id);
        String token = frontend.store().tokenFor(owner.url);
        var direct = HttpRequest.newBuilder(ownerAlbum).GET()
                .header("Accept-Encoding", "gzip").header("X-BootFS-Cluster-Token", token);
        ClusterHttp.signedHeaders(frontend.store().transportIdentity(), "GET", ownerAlbum, token,
                ClusterHttp.digest(new byte[0])).forEach(direct::header);
        var compressed = http.send(direct.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, compressed.statusCode());
        assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());

        JSONObject grid = JSON.parseObject(request(frontend, "GET", "/queryPageOffset?pageSize=20", null, null).body());
        assertEquals(1, grid.getIntValue("total"));
        assertEquals(id, grid.getJSONArray("rows").getJSONObject(0).getString("uuid"));
        assertEquals("1", grid.getJSONArray("rows").getJSONObject(0).getString("albumAvailable"));
        assertEquals(200, request(frontend, "GET", "/album/" + id, null, null).statusCode());
        var response = request(frontend, "GET", "/api/album/" + id, null, null, "gzip, deflate, br, zstd");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Encoding").isEmpty());
        JSONObject album = JSON.parseObject(response.body());
        assertEquals(2, album.getJSONArray("resources").size());
        var reader = request(frontend, "GET", "/api/album/" + id + "/pages", null, null);
        assertEquals(200, reader.statusCode(), reader.body());
        JSONObject readerAlbum = JSON.parseObject(reader.body());
        assertEquals(Set.of("title", "resources"), readerAlbum.keySet());
        assertEquals(album.getJSONArray("resources"), readerAlbum.getJSONArray("resources"));
        for (int i = 0; i < storedPages.size(); i++) {
            String path = URI.create(storedPages.getString(i)).getPath();
            assertEquals(path, album.getJSONArray("resources").getString(i));
            var thumbnail = http.send(HttpRequest.newBuilder(URI.create(frontend.url + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, thumbnail.statusCode());
            assertTrue(thumbnail.body().length > 0);
            String pageId = path.substring(path.lastIndexOf('/') + 1);
            var original = http.send(HttpRequest.newBuilder(URI.create(frontend.url + "/p/" + pageId)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, original.statusCode());
            assertArrayEquals(png, original.body());
        }
        assertEquals(album.getJSONArray("resources").getString(0), album.getString("poster"));
        assertEquals(album.getString("poster"), album.getString("thumbUrl"));
        assertEquals(canonical, owner.bean(ResourceSearchService.class).findDocument("full-text", id));
        assertNull(frontend.bean(LocalFileInventory.class).query(id));
        assertNull(frontend.store().get("/documents/full-text/" + id));
    }

    @Test @Timeout(90) void gbkTextEditingAndClashSubscriptionWorkThroughTheOwningNode() throws Exception {
        Node owner = new Node("gbk-owner", false), frontend = new Node("gbk-frontend", false);
        join(frontend, owner);
        var gbk = java.nio.charset.Charset.forName("GBK");
        String yaml = "proxies:\r\n  - {name: 中文节点, type: vless, server: example.com, port: 443, uuid: 11111111-1111-4111-8111-111111111111}\r\n";
        String id = owner.bean(FileService.class).publish(new java.io.ByteArrayInputStream(yaml.getBytes(gbk)), "gbk-clash.yaml", false).file().getUuid();
        owner.bean(ResourceSearchService.class).updateDocumentTags("full-text", id,
                List.of(cc.ginpika.bootfs.domain.dto.Tag.manual("clash")));
        owner.setSso(true);
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
        assertArrayEquals(updated.getBytes(gbk), Files.readAllBytes(Path.of(owner.bean(LocalFileInventory.class).query(replacement).getPath())));
        var retry = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(replacement, JSON.parseObject(retry.body()).getString("uuid"));
        owner.bean(LocalFileInventory.class).reload();
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
            Node owner = new Node("clash-owner", false, "--bootfs.security.allow-private-probes=true"), frontend = new Node("clash-frontend", false);
            join(frontend, owner);
            String yaml = "proxies:\n"
                    + "  - {name: Local VLESS, type: vless, server: 127.0.0.1, port: " + tcp.getLocalPort()
                    + ", uuid: 11111111-1111-4111-8111-111111111111}\n"
                    + "  - {name: Local SS, type: ss, server: 127.0.0.1, port: " + tcp.getLocalPort()
                    + ", cipher: aes-128-gcm, password: private-test-password}\n";
            String id = owner.upload("clash.yaml", yaml);
            owner.bean(ResourceSearchService.class).updateDocumentTags("full-text", id,
                    List.of(cc.ginpika.bootfs.domain.dto.Tag.manual("clash")));
            owner.setSso(true);
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

    @Test @Timeout(90) void textEditingUsesSignedOwnerRequestsAndRetriesWithoutChangingTheUuid() throws Exception {
        Node owner = new Node("text-owner", false), frontend = new Node("text-frontend", false);
        join(frontend, owner);
        String id = owner.upload("settings.yaml", "port: 80\n");
        owner.setSso(true);
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
        assertEquals(id, replacement); assertNotNull(owner.bean(LocalFileInventory.class).query(id));
        var retry = request(frontend, "PUT", "/api/file/" + id + "/text", body, null);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(replacement, JSON.parseObject(retry.body()).getString("uuid"));
        String stale = new JSONObject(Map.of("content", "port: 8080\n", "version", original.getString("version"))).toJSONString();
        assertEquals(409, request(frontend, "PUT", "/api/file/" + id + "/text", stale, null).statusCode());
        assertEquals("port: 443\n", owner.bean(TextFileService.class).read(replacement).content());
    }

    @Test @Timeout(90) void browserCanReadDetailsAndSaveRemoteTextWhenTheOwnerCompressesJson() throws Exception {
        Node owner = new Node("compressed-owner", false, "--server.compression.enabled=true",
                "--server.compression.min-response-size=1");
        Node frontend = new Node("compressed-frontend", false);
        join(frontend, owner);
        String content = "# 中文配置\r\nport: 80\r\nproxies: []\r\n";
        String id = owner.upload("clash.yaml", content);
        owner.setSso(true);
        String browserCodecs = "gzip, deflate, br, zstd";

        // Check that this test really exercises a gzip-compressing owner, with peer authentication.
        URI uri = URI.create(owner.url + "/api/file/" + id + "/text");
        String token = frontend.store().tokenFor(owner.url);
        HttpRequest.Builder direct = HttpRequest.newBuilder(uri).GET()
                .header("Accept-Encoding", "gzip").header("X-BootFS-Cluster-Token", token);
        ClusterHttp.signedHeaders(frontend.store().transportIdentity(), "GET", uri, token,
                ClusterHttp.digest(new byte[0])).forEach(direct::header);
        var compressed = http.send(direct.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, compressed.statusCode());
        assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());

        var details = request(frontend, "GET", "/api/file/" + id + "/details", null, null, browserCodecs);
        assertEquals(200, details.statusCode(), details.body());
        assertEquals("clash.yaml", JSON.parseObject(details.body()).getString("fileName"));
        assertTrue(details.headers().firstValue("Content-Encoding").isEmpty());
        var read = request(frontend, "GET", "/api/file/" + id + "/text", null, null, browserCodecs);
        assertEquals(200, read.statusCode(), read.body());
        JSONObject original = JSON.parseObject(read.body());
        assertEquals(content, original.getString("content"));
        String updated = content.replace("80", "443");
        String body = new JSONObject(Map.of("content", updated, "version", original.getString("version"))).toJSONString();
        var saved = request(frontend, "PUT", "/api/file/" + id + "/text", body, null, browserCodecs);
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(id, JSON.parseObject(saved.body()).getString("uuid"));
        assertEquals(updated, owner.bean(TextFileService.class).read(id).content());
        var reread = request(frontend, "GET", "/api/file/" + id + "/text", null, null, browserCodecs);
        assertEquals(200, reread.statusCode(), reread.body());
        assertEquals(updated, JSON.parseObject(reread.body()).getString("content"));
    }

    @Test @Timeout(90) void folderListingDoesNotDuplicateImportedRemoteMetadataAsALocalResource() throws Exception {
        Node owner = new Node("folder-owner", false), frontend = new Node("folder-frontend", false);
        String id = owner.bean(FileService.class).publish(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}),
                "legacy-remote.txt", true).file().getUuid();
        String documentKey = "/documents/full-text/" + id, bindingKey = "/s3/bootfs/legacy-remote.txt";
        String document = owner.store().get(documentKey), binding = owner.store().get(bindingKey);
        // Legacy imports may contain cluster-wide sources without this node's durable catalog entry.
        frontend.store().put(documentKey, document);
        frontend.store().put(bindingKey, binding);
        assertNull(frontend.bean(LocalFileInventory.class).query(id));
        join(frontend, owner);

        JSONObject search = JSON.parseObject(request(frontend, "GET", "/queryPageOffset?pageSize=20", null, null).body());
        assertEquals(1, search.getIntValue("total"));
        JSONObject folders = JSON.parseObject(request(frontend, "GET", "/api/folders?pageSize=20", null, null).body());
        assertEquals(1, folders.getIntValue("total"), folders.toJSONString());
        JSONObject row = folders.getJSONArray("rows").getJSONObject(0);
        assertEquals(id, row.getString("uuid"));
        assertEquals(owner.url + "/f/" + id, row.getString("url"));
        assertEquals(0, JSON.parseObject(request(frontend, "GET", "/api/folders?search=meta:local", null, null).body())
                .getIntValue("total"));
        assertEquals(document, frontend.store().get(documentKey));
        assertEquals(binding, frontend.store().get(bindingKey));
    }

    @Test @Timeout(90) void nodesBrowseAndMoveFilesUsingTheOwnersObjectKeys() throws Exception {
        Node a = new Node("move-a", false), b = new Node("move-b", false);
        join(a, b);
        assertEquals(200, request(a, "POST", "/api/folders", "{\"name\":\"archive\"}", null).statusCode());
        assertEquals(200, request(b, "POST", "/api/folders", "{\"name\":\"empty\"}", null).statusCode());
        String local = a.bean(FileService.class).publishBoundObject(new java.io.ByteArrayInputStream(new byte[]{1}),
                "reports/original.txt", "bootfs", "text/plain", null, null).file().getUuid();
        String remote = b.bean(FileService.class).publishBoundObject(new java.io.ByteArrayInputStream(new byte[]{2}),
                "reports/original.txt", "bootfs", "text/plain", null, null).file().getUuid();
        assertEquals(200, request(b, "PUT", "/api/file/" + remote + "/rename", "{\"fileName\":\"display.txt\"}", null).statusCode());

        var rootListing = request(a, "GET", "/api/folders?pageSize=20", null, null);
        assertEquals(200, rootListing.statusCode(), rootListing.body());
        assertEquals(List.of("archive/", "empty/", "reports/"), JSON.parseObject(rootListing.body()).getJSONArray("rows").stream()
                .map(raw -> ((JSONObject) raw).getString("folderPath")).toList());
        var reportListing = request(a, "GET", "/api/folders?folder=reports%2F&pageSize=1", null, null);
        assertEquals(200, reportListing.statusCode(), reportListing.body());
        assertEquals(2, JSON.parseObject(reportListing.body()).getIntValue("total"));
        JSONObject row = JSON.parseObject(reportListing.body()).getJSONArray("rows").getJSONObject(0);
        if (!remote.equals(row.getString("uuid"))) {
            row = JSON.parseObject(request(a, "GET", "/api/folders?folder=reports%2F&pageNumber=1&pageSize=1", null, null).body())
                    .getJSONArray("rows").getJSONObject(0);
        }
        assertEquals(remote, row.getString("uuid"));
        assertEquals("display.txt", row.getString("fileName"));
        assertEquals("reports/original.txt", row.getString("objectKey"));
        assertEquals(b.id, row.getString("originNodeId"));
        assertFalse(row.containsKey("path"));

        b.setSso(true);
        String move = new JSONObject(Map.of("destination", "archive/", "files", List.of(row))).toJSONString();
        assertEquals(401, request(b, "POST", "/api/cluster/data/folders/move", move, null).statusCode());
        var moved = request(a, "POST", "/api/folders/move", move, null);
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(1, JSON.parseObject(moved.body()).getIntValue("moved"));
        assertNull(b.store().get("/s3/bootfs/reports/original.txt"));
        assertEquals(remote, JSON.parseObject(b.store().get("/s3/bootfs/archive/original.txt")).getString("uuid"));
        assertEquals(local, JSON.parseObject(a.store().get("/s3/bootfs/reports/original.txt")).getString("uuid"));
        assertNull(a.store().get("/s3/bootfs/archive/original.txt"));
        assertNull(a.store().get("/documents/full-text/" + remote));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(Path.of(b.bean(LocalFileInventory.class).query(remote).getPath())));
        JSONObject archived = JSON.parseObject(request(a, "GET", "/api/folders?folder=archive%2F", null, null).body())
                .getJSONArray("rows").getJSONObject(0);
        assertEquals(remote, archived.getString("uuid"));
        assertEquals("display.txt", archived.getString("fileName"));
        assertEquals("archive/original.txt", archived.getString("objectKey"));
        assertEquals(404, request(a, "POST", "/api/folders/move", move, null).statusCode());

        String moveLocal = new JSONObject(Map.of("destination", "empty/", "files", List.of(Map.of(
                "objectKey", "reports/original.txt", "uuid", local)))).toJSONString();
        assertEquals(200, request(a, "POST", "/api/folders/move", moveLocal, null).statusCode());
        assertEquals(local, JSON.parseObject(a.store().get("/s3/bootfs/empty/original.txt")).getString("uuid"));
        b.close();
        JSONObject partial = JSON.parseObject(request(a, "GET", "/api/folders?folder=empty%2F", null, null).body());
        assertTrue(partial.getBooleanValue("partial"));
        assertEquals(List.of(b.id), partial.getJSONArray("unavailableNodes").toJavaList(String.class));
        assertEquals(1, partial.getIntValue("total"));
        assertEquals(503, request(a, "POST", "/api/folders/move", move, null).statusCode());
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
        assertEquals("renamed.txt",x.bean(LocalFileInventory.class).query(own).getFileName());
        assertNull(a.store().get("/documents/full-text/"+own));
        assertEquals(200,request(a,"POST","/api/file/"+own+"/tags","[{\"namespace\":\"manual\",\"name\":\"travel\"}]",null).statusCode());
        String oldToken=a.store().tokenFor(x.url);
        x.store().leave(); flush();
        assertEquals(0,x.store().status().getIntValue("pendingNotifications"));
        assertEquals(401,request(x,"GET","/api/cluster/data/resource/"+own,null,oldToken).statusCode());
        assertEquals("portable original",request(x,"GET","/f/"+own,null,null).body());
        assertEquals(1,JSON.parseObject(request(x,"GET","/queryPageOffset",null,null).body()).getIntValue("total"));
        join(x,b);
        assertEquals("renamed.txt",x.bean(LocalFileInventory.class).query(own).getFileName());
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
        owner.bean(ResourceSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"fileName","archive.txt","title","archive.txt","author","original author","description","portable description","contextUuid",owner.id)).toJSONString());
        owner.bean(FileTransferService.class).publicationCommitted(id);
        owner.bean(FileTransferService.class).resumePendingReplicas();
        assertNotNull(backup.bean(LocalFileInventory.class).query(replica));
        assertEquals("complete bytes",request(backup,"GET","/f/"+replica,null,null).body());
        assertEquals("original author",backup.bean(ResourceSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals("portable description",backup.bean(ResourceSearchService.class).findDocument("full-text",replica).getString("description"));
        assertNull(backup.store().get("/documents/full-text/"+id));
        owner.bean(ResourceSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"author","updated author")).toJSONString());
        backup.bean(FileTransferService.class).resumePendingReplicas();
        assertEquals("updated author",backup.bean(ResourceSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals(200,request(backup,"PUT","/api/file/"+replica+"/rename","{\"fileName\":\"my independent copy.txt\"}",null).statusCode());
        owner.bean(ResourceSearchService.class).addToFullText(new JSONObject(Map.of("uuid",id,"author","later source change")).toJSONString());
        backup.bean(FileTransferService.class).resumePendingReplicas();
        assertEquals("updated author",backup.bean(ResourceSearchService.class).findDocument("full-text",replica).getString("author"));
        assertEquals("my independent copy.txt",backup.bean(LocalFileInventory.class).query(replica).getFileName());
        backup.store().leave(); owner.close();
        assertEquals("complete bytes",request(backup,"GET","/f/"+replica,null,null).body());
        assertEquals("updated author",backup.bean(ResourceSearchService.class).findDocument("full-text",replica).getString("author"));
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

    @Test @Timeout(90) void signedPeersCannotChangeBodiesThroughVendorJsonOrRemoveMembership() throws Exception {
        Node owner = new Node("body-owner", false), caller = new Node("body-caller", false);
        join(caller, owner);
        String token = caller.store().tokenFor(owner.url);
        URI target = URI.create(owner.url + "/api/cluster/data/search");
        String body = "{\"query\":\"original\",\"tags\":[],\"offset\":0,\"limit\":10}";
        for (boolean tamper : new boolean[]{false, true}) {
            var request = HttpRequest.newBuilder(target).header("X-BootFS-Cluster-Token", token)
                    .header("Content-Type", "application/problem+json");
            ClusterHttp.signedHeaders(caller.store().transportIdentity(), "POST", target, token,
                    ClusterHttp.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))).forEach(request::header);
            var result = http.send(request.POST(HttpRequest.BodyPublishers.ofString(tamper ? body.replace("original", "changed") : body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(tamper ? 401 : 200, result.statusCode(), result.body());
        }
        String extremeTime = Long.toString(System.currentTimeMillis() - Long.MIN_VALUE);
        String nonce = java.util.UUID.randomUUID().toString(), digest = ClusterHttp.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signature = caller.store().transportIdentity().sign(ClusterHttp.signingInput("POST", target.getRawPath(), token, extremeTime, nonce, digest));
        var expired = HttpRequest.newBuilder(target).header("X-BootFS-Cluster-Token", token).header("Content-Type", "application/json")
                .header("X-BootFS-Time", extremeTime).header("X-BootFS-Nonce", nonce).header("X-BootFS-Digest", digest)
                .header("X-BootFS-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(body));
        assertEquals(401, http.send(expired.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        URI remove = URI.create(owner.url + "/api/cluster/remove");
        String removal = "{\"nodeId\":\"body-caller\"}";
        var request = HttpRequest.newBuilder(remove).header("X-BootFS-Cluster-Token", token).header("Content-Type", "application/json");
        ClusterHttp.signedHeaders(caller.store().transportIdentity(), "POST", remove, token,
                ClusterHttp.digest(removal.getBytes(java.nio.charset.StandardCharsets.UTF_8))).forEach(request::header);
        assertEquals(401, http.send(request.POST(HttpRequest.BodyPublishers.ofString(removal)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertTrue(owner.store().peerEndpoints().containsKey(caller.id));
    }
}
