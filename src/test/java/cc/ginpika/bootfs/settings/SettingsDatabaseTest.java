package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsDatabaseTest {
    @TempDir Path root;
    MockEnvironment env() { return new MockEnvironment().withProperty("tfs.root-path",root.toString()); }
    TfsConfig files() { var f=new TfsConfig(); f.setRootPath(root.toString()); f.setWebEntrypoint("http://localhost:8181"); return f; }
    SettingsValues changed(SettingsValues old,String key,Object value) { var m=new LinkedHashMap<>(old.map()); m.put(key,value); return new SettingsValues(m); }
    @Test void newInstallHasExactlyEighteenSettingsAndRequestedDefaults() throws Exception {
        try (var s=new NodeSettings(env())) {
            var v=s.current(); assertEquals(18,v.map().size());
            assertFalse(v.bool("ssoEnabled")); assertFalse(v.bool("s3Enabled"));
            assertTrue(v.bool("localBypassEnabled")); assertTrue(v.bool("publicSharingEnabled")); assertTrue(v.bool("allowPrivateProbes"));
            assertTrue(v.permitsRole(null)); assertTrue(v.permitsRole("user")); assertEquals("",v.string("ssoServerUrl"));
            assertEquals(1,v.effectiveCopies()); assertEquals("none",v.string("forwardHeadersStrategy"));
        }
    }
    @Test void explicitLegacyValuesImportOnceAndRestartCannotOverrideDatabase() throws Exception {
        var env=env().withProperty("SSO_ALLOWED_ROLES","admin, editor").withProperty("SSO_LOCAL_BYPASS_ENABLED","false")
                .withProperty("S3_ACCESS_KEY","test-key").withProperty("S3_SECRET_KEY","long-secret-value").withProperty("TFS_COPIES","3");
        try (var s=new NodeSettings(env)) {
            assertEquals(3,s.current().integer("copies")); assertFalse(s.current().bool("localBypassEnabled"));
            assertTrue(s.current().permitsRole("editor")); assertFalse(s.current().permitsRole("user"));
            assertFalse(JSON.toJSONString(s.view()).contains("long-secret-value"));
        }
        try (var s=new NodeSettings(env().withProperty("TFS_COPIES","99").withProperty("S3_SECRET_KEY","different-secret"))) {
            assertEquals(3,s.current().integer("copies")); assertEquals("long-secret-value",s.current().string("s3SecretKey"));
        }
        try (var db=new RocksMetadataStore(root.resolve("kernel/settings"))) {
            String raw=db.get("settings"); assertFalse(raw.contains("long-secret-value")); assertFalse(raw.contains("test-key"));
        }
    }
    @Test void invalidCombinationsAreRejectedBeforePersistenceAndStaleUpdatesConflict() throws Exception {
        try (var s=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"copies",0));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"writePolicy","replicated"));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"s3Enabled",true));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"allowedRoles","*"));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"s3AccessKey","invalid/key"));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"ssoInfoUrl","javascript:alert(1)"));
            assertThrows(IllegalArgumentException.class,()->changed(s.current(),"internalProxies","["));
            assertEquals(1L,s.view().get("revision"));
            s.update(1,changed(s.current(),"copies",2),false,false,metadata);
            assertThrows(IllegalStateException.class,()->s.update(1,changed(s.current(),"copies",3),false,false,metadata));
            assertEquals(2,s.current().integer("copies"));
        }
    }
    @Test void settingsKeyCannotBeSilentlyRegenerated() throws Exception {
        try (var s=new NodeSettings(env())) { assertNotNull(s.current()); }
        Files.delete(root.resolve("kernel/security/settings.key"));
        assertThrows(java.io.IOException.class,()->new NodeSettings(env()));
    }
    @Test void proxySettingsAreBoundFromDatabaseBeforeContainerInitialization() throws Exception {
        var first=env().withProperty("SERVER_FORWARD_HEADERS_STRATEGY","native").withProperty("SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES","172[.]18[.]0[.]1");
        new SettingsEnvironment().postProcessEnvironment(first,null);
        var restart=env().withProperty("SERVER_FORWARD_HEADERS_STRATEGY","none");
        new SettingsEnvironment().postProcessEnvironment(restart,null);
        var server=Binder.get(restart).bind("server",ServerProperties.class).get();
        assertEquals(ServerProperties.ForwardHeadersStrategy.NATIVE,server.getForwardHeadersStrategy());
        assertEquals("172[.]18[.]0[.]1",server.getTomcat().getRemoteip().getInternalProxies());
    }
    @Test void nodeSettingsRemainLocalAndPortableDataExcludesSecretsAndIdentity() throws Exception {
        try (var s=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            var values=new LinkedHashMap<>(s.current().map()); values.put("s3SecretKey","secret-value"); values.put("s3AccessKey","access-key");
            s.update(1,new SettingsValues(values),false,false,metadata);
            assertTrue(metadata.scan("").isEmpty());
            String portable=JSON.toJSONString(s.current().portable());
            assertFalse(portable.contains("secret-value")); assertFalse(portable.contains("access-key"));
            assertFalse(portable.contains("webEntrypoint")); assertFalse(portable.contains("internalProxies"));
            try (var other=new NodeSettings(new MockEnvironment().withProperty("tfs.root-path",root.resolve("other").toString()))) { assertEquals("",other.current().string("s3SecretKey")); }
        }
    }
    @Test void controlledAddressChangeMigratesManifestAndRoutesWithoutChangingIdentity() throws Exception {
        String node,publicKey;
        try (var s=new NodeSettings(env()); var metadata=new DynamicMetadataStore(files())) {
            node=metadata.nodeId(); publicKey=metadata.transportIdentity().publicKey();
            metadata.put("/files/file","http://localhost:8181/f/file");
            metadata.put("/documents/full-text/file","{\"poster\":\"http://localhost:8181/thumb/file\",\"title\":\"unchanged\"}");
            s.update(1,changed(s.current(),"webEntrypoint","https://new.example"),true,false,metadata);
            assertEquals("http://localhost:8181",s.current().string("webEntrypoint"));
        }
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var s=new NodeSettings(env())) {
            var config=files(); config.setWebEntrypoint(s.current().string("webEntrypoint"));
            try (var metadata=new DynamicMetadataStore(config)) {
                assertEquals(node,metadata.nodeId()); assertEquals(publicKey,metadata.transportIdentity().publicKey());
                assertEquals("https://new.example/f/file",metadata.get("/files/file"));
                assertTrue(metadata.get("/documents/full-text/file").contains("https://new.example/thumb/file"));
            }
        }
    }
    @Test void addressChangeSkipsPlainMigrationMarkerAndStillRewritesJsonRoutes() throws Exception {
        String node;
        try (var s=new NodeSettings(env()); var metadata=new DynamicMetadataStore(files())) {
            node=metadata.nodeId();
            metadata.put("/cluster/node/"+node,"http://localhost:8181");
            metadata.put("/nodes/"+node+"/migration/db-json-v1","complete");
            metadata.put("/nodes/"+node+"/catalog/file","{\"uuid\":\"file\",\"url\":\"http://localhost:8181/f/file\",\"thumbUrl\":\"http://localhost:8181/thumb/file.webp\",\"title\":\"keep\"}");
            s.update(1,changed(s.current(),"webEntrypoint","https://new.example"),true,false,metadata);
            assertEquals("http://localhost:8181",s.current().string("webEntrypoint"));
        }
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var s=new NodeSettings(env())) {
            var config=files(); config.setWebEntrypoint(s.current().string("webEntrypoint"));
            try (var metadata=new DynamicMetadataStore(config)) {
                assertEquals("https://new.example",s.current().string("webEntrypoint"));
                assertTrue(((Map<?,?>)s.view().get("pending")).isEmpty());
                assertEquals("complete",metadata.get("/nodes/"+node+"/migration/db-json-v1"));
                assertEquals("https://new.example",metadata.get("/cluster/node/"+node));
                String catalog=metadata.get("/nodes/"+node+"/catalog/file");
                assertTrue(catalog.contains("https://new.example/f/file"));
                assertTrue(catalog.contains("https://new.example/thumb/file.webp"));
                assertTrue(catalog.contains("\"title\":\"keep\""));
            }
        }
    }
    @Test void bucketChangeMovesAllBindingsOnlyAfterRestart() throws Exception {
        try (var s=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            metadata.put("/s3/bootfs/key","{\"uuid\":\"file\",\"key\":\"key\"}");
            assertThrows(IllegalArgumentException.class,()->s.update(1,changed(s.current(),"s3Bucket","new-bucket"),false,false,metadata));
            s.update(1,changed(s.current(),"s3Bucket","new-bucket"),false,true,metadata);
            assertEquals("bootfs",s.current().string("s3Bucket")); assertNotNull(metadata.get("/s3/bootfs/key"));
        }
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var s=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            assertEquals("new-bucket",s.current().string("s3Bucket")); assertNull(metadata.get("/s3/bootfs/key")); assertNotNull(metadata.get("/s3/new-bucket/key"));
        }
    }
    @Test void conflictAtRestartKeepsOriginalBucketAndReportsFailure() throws Exception {
        try (var s=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            metadata.put("/s3/bootfs/key","{\"uuid\":\"one\"}"); metadata.put("/s3/new-bucket/key","{\"uuid\":\"two\"}");
            s.update(1,changed(s.current(),"s3Bucket","new-bucket"),false,true,metadata);
        }
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var s=new NodeSettings(env())) { assertEquals("bootfs",s.current().string("s3Bucket")); assertNotNull(s.view().get("transitionError")); }
    }
    @Test void activeTasksRejectControlledChangesAndPairedNodesRejectAddressChange() throws Exception {
        try (var s=new NodeSettings(env()); var metadata=new DynamicMetadataStore(files())) {
            metadata.put("/pending-publications/file","{}");
            assertThrows(IllegalArgumentException.class,()->s.update(1,changed(s.current(),"s3Bucket","new-bucket"),false,true,metadata));
            metadata.delete("/pending-publications/file");
            Path file=root.resolve("kernel/cluster/node.json"); JSONObject manifest=JSON.parseObject(Files.readString(file));
            manifest.getJSONObject("members").put("peer",new JSONObject()); Files.writeString(file,manifest.toJSONString());
            assertThrows(IllegalArgumentException.class,()->s.update(1,changed(s.current(),"webEntrypoint","https://new.example"),true,false,metadata));
        }
    }
    @Test void interruptedMigrationRetriesIdempotentlyWithoutChangingSigningIdentity() throws Exception {
        try (var settings=new NodeSettings(env()); var metadata=new DynamicMetadataStore(files())) {
            metadata.put("/s3/bootfs/key","{\"uuid\":\"file\",\"key\":\"key\"}");
            var values=new LinkedHashMap<>(settings.current().map()); values.put("webEntrypoint","https://new.example/"); values.put("s3Bucket","new-bucket");
            settings.update(1,new SettingsValues(values),true,true,metadata);
        }
        // Simulate a crash after the resource batch and manifest were written, before the settings record was committed.
        try (var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            metadata.writeBatch(Map.of("/s3/new-bucket/key",metadata.get("/s3/bootfs/key")),Set.of("/s3/bootfs/key"));
        }
        Path manifestFile=root.resolve("kernel/cluster/node.json"); var manifest=JSON.parseObject(Files.readString(manifestFile));
        String signing=manifest.getJSONObject("members").getJSONObject(manifest.getString("nodeId")).getString("publicKey");
        manifest.put("endpoint","https://new.example"); manifest.getJSONObject("members").getJSONObject(manifest.getString("nodeId")).put("url","https://new.example");
        Files.writeString(manifestFile,manifest.toJSONString()); Files.writeString(root.resolve("kernel/settings-transition.json"),"{}");
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var settings=new NodeSettings(env())) {
            assertEquals("https://new.example",settings.current().string("webEntrypoint")); assertEquals("new-bucket",settings.current().string("s3Bucket"));
            var f=files(); f.setWebEntrypoint(settings.current().string("webEntrypoint"));
            try (var metadata=new DynamicMetadataStore(f)) { assertEquals(signing,metadata.transportIdentity().publicKey()); assertNotNull(metadata.get("/s3/new-bucket/key")); }
        }
        assertFalse(Files.exists(root.resolve("kernel/settings-transition.json")));
    }

    @Test void privateProbeSwitchAffectsNewJobsAndDoesNotReuseOppositePolicyCache() throws Exception {
        try (var settings=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"));
             var socket=new java.net.ServerSocket(0,5,java.net.InetAddress.getByName("127.0.0.1"))) {
            var latency=new cc.ginpika.bootfs.service.ClashLatencyService(settings);
            try {
                var details=new cc.ginpika.bootfs.service.ClashFileService.ClashDetails("file","version",List.of(
                    new cc.ginpika.bootfs.service.ClashFileService.ClashEndpoint("one","local","vless","127.0.0.1",socket.getLocalPort())),List.of(),"tcp","server");
                assertEquals("ok",latency.measure(details,0,1).nodes().get(0).status());
                settings.update(1,changed(settings.current(),"allowPrivateProbes",false),false,false,metadata);
                assertEquals("blocked",latency.measure(details,0,1).nodes().get(0).status());
            } finally { latency.close(); }
        }
    }

    @Test void bucketTransitionKeepsHistoricalBindingsAccessibleUnderNewBucket() throws Exception {
        String oldKey="/s3/bootfs/key",newKey="/s3/new-bucket/key";
        String oldVersion="/versions/s3/"+Base64.getUrlEncoder().withoutPadding().encodeToString(oldKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/one";
        String newVersion="/versions/s3/"+Base64.getUrlEncoder().withoutPadding().encodeToString(newKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/one";
        try (var settings=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            metadata.put(oldVersion,new JSONObject(Map.of("namespace","s3","key",oldKey,"value","{\"uuid\":\"historical\"}")).toJSONString());
            settings.update(1,changed(settings.current(),"s3Bucket","new-bucket"),false,true,metadata);
        }
        new SettingsEnvironment().postProcessEnvironment(env(),null);
        try (var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            assertNull(metadata.get(oldVersion)); assertEquals(newKey,JSON.parseObject(metadata.get(newVersion)).getString("key"));
        }
    }

    @Test void incompleteLegacyS3ConfigurationDoesNotPreventStartup() throws Exception {
        try (var settings=new NodeSettings(env().withProperty("S3_ENABLED","true"))) {
            assertFalse(settings.current().bool("s3Enabled")); assertNotNull(settings.view().get("migrationWarning"));
        }
    }
    @Test void confirmedPendingChangeSurvivesUnrelatedUpdatesAndCanBeCancelled() throws Exception {
        try (var settings=new NodeSettings(env()); var metadata=new RocksMetadataStore(root.resolve("kernel/metadata"))) {
            var candidate=changed(settings.current(),"s3Bucket","new-bucket"); settings.update(1,candidate,false,true,metadata);
            settings.update(2,changed(candidate,"copies",2),false,false,metadata);
            assertEquals("new-bucket",((Map<?,?>)settings.view().get("pending")).get("s3Bucket"));
            settings.update(3,settings.current(),false,false,metadata);
            assertTrue(((Map<?,?>)settings.view().get("pending")).isEmpty());
        }
    }

}
