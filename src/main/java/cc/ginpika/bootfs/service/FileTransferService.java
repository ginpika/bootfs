package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.DurableFiles;
import cc.ginpika.bootfs.core.io.HttpFileTransferClient;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.domain.result.SimpleTransferResult;
import cc.ginpika.bootfs.domain.result.TransferResult;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.FullTextDocument;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Idempotent immutable replica receipt. An uncertain metadata reply never triggers destructive rollback. */
@Slf4j
@Service
public class FileTransferService {
    @Resource Context context;
    @Resource TfsConfig tfsConfig;
    @Resource EtcdService etcdService;
    @Resource MeiliSearchService meiliSearchService;
    @Resource private cc.ginpika.bootfs.kernel.cluster.ClusterCredentials credentials;
    @Resource private cc.ginpika.bootfs.kernel.metadata.MetadataStore metadata;
    @Resource private FederationService federation;
    private int commitCursor,sourceCursor;

    private String prefix() { return "/pending-replicas/" + context.uuid + "/"; }

    public synchronized TransferResult accept(MultipartFile file, String originalFilename, String originalFileUuid) {
        Path stage = null;
        boolean intentAttempted = false;
        try {
            if (originalFileUuid == null || !originalFileUuid.matches("[A-Za-z0-9_-]+")) throw new IOException("Original resource ID is invalid");
            if (etcdService.getOne("/tombstones/" + originalFileUuid) != null) throw new IOException("Original resource has been deleted");
            String uuid = UUID.nameUUIDFromBytes((context.uuid + ":" + originalFileUuid).getBytes(StandardCharsets.UTF_8)).toString();
            String existingIntent = etcdService.getOne(prefix() + uuid);
            if (existingIntent != null) return receipt(finishReplica(JSONObject.parseObject(existingIntent)));
            FileObject existing = context.query(uuid);
            if (existing != null && originalFileUuid.equals(existing.getCopyOf()) && Files.isRegularFile(Path.of(existing.getPath()))) {
                registerReplica(existing);
                return receipt(existing);
            }
            Path directory = Path.of(tfsConfig.getPathPrefix());
            Files.createDirectories(directory);
            stage = Files.createTempFile(directory, ".replica-", ".part");
            long actualSize;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(stage)) { actualSize = input.transferTo(output); }
            if (actualSize != file.getSize()) throw new IOException("Replica size does not match its declared size");
            DurableFiles.syncFile(stage);
            DurableFiles.syncDirectory(directory);
            FileObject resource = new FileObject(directory.resolve(uuid).toString(), uuid, originalFilename, actualSize);
            resource.setCopyOf(originalFileUuid); resource.setCreatedAt(System.currentTimeMillis());
            JSONObject serialized = JSONObject.from(resource); serialized.remove("path");
            JSONObject intent = new JSONObject(); intent.put("file", serialized); intent.put("stage", stage.getFileName().toString());
            intentAttempted = true;
            etcdService.put(prefix() + uuid, intent.toJSONString());
            return receipt(finishReplica(intent));
        } catch (Exception failure) {
            log.error("Replica receipt is incomplete", failure);
            return SimpleTransferResult.builder().succeed(false).message(failure.getMessage()).build();
        } finally {
            if (!intentAttempted && stage != null) {
                try { Files.deleteIfExists(stage); } catch (IOException cleanup) { log.warn("Cannot remove incomplete replica staging", cleanup); }
            }
        }
    }

    /** Prepared replicas are durable but have no public catalog, route, search or S3 binding. */
    public synchronized JSONObject prepare(MultipartFile file, String name, String original, String sha256) throws IOException {
        if (original == null || !original.matches("[A-Za-z0-9_-]+") || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid immutable content identity");
        }
        if (etcdService.getOne("/tombstones/" + original) != null) throw new IOException("Resource is deleted");
        String id = UUID.nameUUIDFromBytes((context.uuid + ":" + original).getBytes(StandardCharsets.UTF_8)).toString();
        if (etcdService.getOne("/tombstones/"+id)!=null) throw new IOException("This local replica was explicitly deleted");
        String localSource = etcdService.getOne("/replica-sources/" + id);
        if (localSource != null && JSONObject.parseObject(localSource).getBooleanValue("forked")) {
            throw new IOException("Replica has independent local changes");
        }
        String key = preparedKey(id, sha256);
        Path directory = Path.of(tfsConfig.getPathPrefix()); Files.createDirectories(directory);
        String contentId = UUID.nameUUIDFromBytes(("replica-content:" + id + ":" + sha256).getBytes(StandardCharsets.UTF_8)).toString();
        Path target = directory.resolve(contentId);
        String previous = etcdService.getOne(key);
        if (previous != null && !sha256.equals(JSONObject.parseObject(previous).getString("sha256"))) {
            throw new IOException("Immutable replica identity collision");
        }
        if (!Files.isRegularFile(target)) {
            Path stage = Files.createTempFile(directory, ".replica-", ".part");
            try {
                long size;
                try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(stage)) { size = input.transferTo(output); }
                if (size != file.getSize() || !sha256.equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(stage))) {
                    throw new IOException("Replica checksum or size mismatch");
                }
                DurableFiles.syncFile(stage);
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                DurableFiles.syncDirectory(directory);
            } finally { Files.deleteIfExists(stage); }
        }
        if (Files.size(target) != file.getSize() || !sha256.equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(target))) {
            throw new IOException("Existing replica content does not match request");
        }
        // Existing bytes may come from a crash between rename and directory fsync.
        DurableFiles.syncFile(target); DurableFiles.syncDirectory(directory);
        FileObject resource = new FileObject(target.toString(), id, name, file.getSize());
        resource.setContentObjectId(contentId);
        resource.setCopyOf(original); resource.setCreatedAt(System.currentTimeMillis());
        JSONObject persisted = JSONObject.from(resource); persisted.remove("path");
        JSONObject prepared = new JSONObject(); prepared.put("file", persisted); prepared.put("sha256", sha256);
        if (!etcdService.compareAndSet(key, null, prepared.toJSONString())) {
            JSONObject saved = JSONObject.parseObject(etcdService.getOne(key));
            if (saved == null || !sha256.equals(saved.getString("sha256"))) throw new IOException("Replica preparation changed");
        }
        JSONObject receipt = new JSONObject();
        receipt.put("nodeId", context.uuid); receipt.put("objectId", original); receipt.put("replicaId", id);
        receipt.put("sha256", sha256); receipt.put("size", Files.size(target));
        etcdService.put("/resource-content/" + context.uuid + "/" + id + "/" + contentId, "content");
        return receipt;
    }
    public synchronized JSONObject prepare(MultipartFile file,String name,String original,String sha256,JSONObject snapshot,String actor) throws IOException {
        if (snapshot==null || !Objects.equals(actor,snapshot.getString("nodeId")) || !original.equals(snapshot.getString("objectId")) || !sha256.equals(snapshot.getString("sha256"))
                || snapshot.getJSONObject("file")==null || snapshot.getJSONObject("file").getLongValue("size")!=file.getSize()) throw new IOException("Replica snapshot must come from the resource owner and match its bytes");
        JSONObject receipt=prepare(file,name,original,sha256);
        String key=preparedKey(receipt.getString("replicaId"),sha256);
        JSONObject prepared=JSONObject.parseObject(etcdService.getOne(key)); prepared.put("snapshot",snapshot); prepared.put("owner",actor);
        etcdService.put(key,prepared.toJSONString()); return receipt;
    }

    private String preparedKey(String id, String hash) { return "/prepared-replicas/" + context.uuid + "/" + id + "/" + hash; }

    private Path contentPath(FileObject resource) throws IOException {
        String id = resource.getContentObjectId() == null ? resource.getUuid() : resource.getContentObjectId();
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid replica content identity");
        return Path.of(tfsConfig.getPathPrefix(), id);
    }
    public JSONObject snapshot(String id) {
        Map<String,String> state=null; long revision=0;
        for (int attempt=0;attempt<8;attempt++) {
            long before=metadata==null ? 0 : metadata.revision(); Map<String,String> scanned=etcdService.scan("");
            if (metadata==null || before==metadata.revision()) { state=scanned; revision=before; break; }
        }
        if (state==null) throw new IllegalStateException("Local metadata is changing; retry the resource snapshot");
        String document=state.get("/documents/full-text/"+id), meta=state.get("/files/"+id+"/meta");
        String file=state.get("/nodes/"+context.uuid+"/catalog/"+id);
        if (file==null) {
            String pending=state.get("/pending-publications/"+context.uuid+"/"+id);
            if (pending!=null) file=JSONObject.parseObject(pending).getJSONObject("file").toJSONString();
        }
        JSONObject result=new JSONObject(); result.put("nodeId",context.uuid); result.put("objectId",id);
        result.put("revision",state.containsKey("/resource-revisions/"+id) ? Long.parseLong(state.get("/resource-revisions/"+id)) : revision); result.put("file",file==null ? null : JSONObject.parseObject(file));
        result.put("document",document==null ? null : JSONObject.parseObject(document)); result.put("metadata",meta==null ? null : JSONObject.parseObject(meta));
        JSONObject bindings=new JSONObject(); state.forEach((key,value) -> { if (key.startsWith("/s3/") && id.equals(JSONObject.parseObject(value).getString("uuid"))) bindings.put(key,JSONObject.parseObject(value)); });
        result.put("bindings",bindings); return result;
    }
    public synchronized JSONObject commitReplica(JSONObject snapshot,String actor) throws IOException {
        if (!Objects.equals(actor,snapshot.getString("nodeId"))) throw new IOException("Replica publication must come from its owner");
        String original=snapshot.getString("objectId");
        if (original==null || !original.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid replica object");
        String id=UUID.nameUUIDFromBytes((context.uuid+":"+original).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject sourceMeta = snapshot.getJSONObject("metadata");
        if (sourceMeta == null || sourceMeta.getString("sha256") == null) throw new IOException("Replica content hash is missing");
        String savedSource = etcdService.getOne("/replica-sources/" + id);
        if (savedSource != null) {
            JSONObject previous = JSONObject.parseObject(savedSource);
            if (!actor.equals(previous.getString("nodeId"))) throw new IOException("Replica belongs to another owner");
            if (previous.getBooleanValue("forked")) throw new IOException("Replica has independent local changes");
            if (previous.getLongValue("revision") >= snapshot.getLongValue("revision")) return new JSONObject(Map.of("accepted", true, "replicaId", id));
        }
        String key=preparedKey(id,sourceMeta.getString("sha256"));
        String raw=etcdService.getOne(key);
        if (raw == null) {
            String legacy = "/prepared-replicas/" + context.uuid + "/" + id;
            String candidate = etcdService.getOne(legacy);
            if (candidate != null && sourceMeta.getString("sha256").equals(JSONObject.parseObject(candidate).getString("sha256"))) {
                key = legacy; raw = candidate;
            }
        }
        if (raw==null) {
            String saved=etcdService.getOne("/replica-sources/"+id);
            if (saved!=null && actor.equals(JSONObject.parseObject(saved).getString("nodeId"))) {
                JSONObject previous=JSONObject.parseObject(saved);
                if (previous.getBooleanValue("forked")) throw new IOException("Replica has independent local changes");
                if (previous.getLongValue("revision")>=snapshot.getLongValue("revision")) return new JSONObject(Map.of("accepted",true,"replicaId",id));
                FileObject local=context.query(id);
                if (local==null) throw new IOException("Replica is no longer present");
                JSONObject prepared=new JSONObject(Map.of("file",JSONObject.from(local),"sha256",previous.getJSONObject("metadata").getString("sha256")));
                publishSnapshot(prepared,snapshot,id); return new JSONObject(Map.of("accepted",true,"replicaId",id));
            }
            throw new IOException("Replica bytes have not been prepared");
        }
        JSONObject prepared=JSONObject.parseObject(raw);
        if (!actor.equals(prepared.getString("owner"))) throw new IOException("Prepared replica belongs to another owner");
        publishSnapshot(prepared,snapshot,id); etcdService.delete(key);
        return new JSONObject(Map.of("accepted",true,"replicaId",id));
    }
    private void publishSnapshot(JSONObject prepared,JSONObject snapshot,String id) throws IOException {
        JSONObject sourceMeta=snapshot.getJSONObject("metadata"),doc=snapshot.getJSONObject("document"),sourceFile=snapshot.getJSONObject("file");
        if (sourceMeta==null || doc==null || sourceFile==null || !prepared.getString("sha256").equals(sourceMeta.getString("sha256"))) throw new IOException("Complete replica metadata does not match prepared bytes");
        FileObject resource=prepared.getJSONObject("file").toJavaObject(FileObject.class);
        Path path=contentPath(resource);
        if (!Files.isRegularFile(path) || Files.size(path)!=resource.getSize() || !prepared.getString("sha256").equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(path))) throw new IOException("Replica bytes are missing or corrupt");
        JSONObject localFile=JSONObject.parseObject(sourceFile.toJSONString()); localFile.put("uuid",id); localFile.put("copyOf",snapshot.getString("objectId"));
        if (resource.getContentObjectId() == null) localFile.remove("contentObjectId");
        else localFile.put("contentObjectId", resource.getContentObjectId());
        localFile.remove("path"); localFile.remove("url");
        localFile.remove("parent"); localFile.remove("hlsAvailable"); localFile.remove("thumbAvailable"); localFile.remove("albumAvailable");
        resource=localFile.toJavaObject(FileObject.class); resource.setPath(path.toString());
        JSONObject localDoc=JSONObject.parseObject(doc.toJSONString()); localDoc.put("uuid",id); localDoc.put("copyOf",snapshot.getString("objectId")); localDoc.put("contextUuid",context.uuid);
        for (String field:List.of("parent","hlsAvailable","thumbAvailable","albumAvailable","resources","publicUrl")) localDoc.remove(field);
        localDoc.put("thumbUrl",context.buildThumbUrl(id)); localDoc.put("poster",context.buildThumbUrl(id));
        JSONObject localMeta=JSONObject.parseObject(sourceMeta.toJSONString()); localMeta.put("copyOf",snapshot.getString("objectId")); localMeta.put("requiredCopies",1); localMeta.put("durableNodes",Map.of(context.uuid,prepared.getString("sha256")));
        if (resource.getContentObjectId() == null) localMeta.remove("contentObjectId");
        else localMeta.put("contentObjectId", resource.getContentObjectId());
        // The local copy has its own physical content identity, never the owner's disk path.
        if (resource.getContentObjectId() == null) localDoc.remove("contentObjectId");
        else localDoc.put("contentObjectId", resource.getContentObjectId());
        String current=etcdService.getOne("/replica-sources/"+id);
        if (current!=null && JSONObject.parseObject(current).getBooleanValue("forked")) throw new IOException("Replica has independent local changes");
        if (etcdService.getOne("/tombstones/"+id)!=null) throw new IOException("Replica was deleted");
        Map<String,String> expected=new HashMap<>(); expected.put("/replica-sources/"+id,current); expected.put("/tombstones/"+id,null);
        if (!etcdService.compareAndWrite(expected,Map.of("/files/"+id,context.buildUrl(id),"/files/"+id+"/meta",localMeta.toJSONString(),
                "/nodes/"+context.uuid+"/catalog/"+id,localFile.toJSONString(),"/documents/full-text/"+id,localDoc.toJSONString(),"/replica-sources/"+id,snapshot.toJSONString()),Set.of())) throw new IOException("Replica changed locally; retry publication");
        context.STORAGE.put(id,resource);
    }
    public void publicationCommitted(String id) {
        if (!(metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore)) return;
        String raw=etcdService.getOne("/files/"+id+"/meta"); if (raw==null) return;
        JSONObject confirmation=JSONObject.parseObject(raw).getJSONObject("durableNodes"); if (confirmation==null) confirmation=new JSONObject();
        Set<String> recipients = new HashSet<>(confirmation.keySet());
        etcdService.scan("/replication-receipts/" + context.uuid + "/" + id + "/").keySet()
                .forEach(key -> recipients.add(key.substring(key.lastIndexOf('/') + 1)));
        Map<String,String> nodes=etcdService.getNodesWithKeys();
        for (String node:recipients) if (!context.uuid.equals(node) && nodes.containsKey(node)) {
            JSONObject job=new JSONObject(Map.of("nodeId",node,"url",nodes.get(node),"snapshot",snapshot(id)));
            etcdService.put("/replica-commits/"+context.uuid+"/"+id+"/"+node,job.toJSONString());
        }
    }
    private void resumeReplicaCommits() {
        if (federation==null) return;
        var entries=new ArrayList<>(etcdService.scan("/replica-commits/"+context.uuid+"/").entrySet());
        if (entries.isEmpty()) return;
        int start=Math.floorMod(commitCursor,entries.size()); commitCursor=(start+Math.min(16,entries.size()))%entries.size();
        for (int step=0;step<Math.min(16,entries.size());step++) {
            var entry=entries.get((start+step)%entries.size());
            JSONObject job=JSONObject.parseObject(entry.getValue());
            if (!Objects.equals(federation.peers().get(job.getString("nodeId")),job.getString("url"))) continue;
            try {
                JSONObject current = snapshot(job.getJSONObject("snapshot").getString("objectId"));
                FileObject local = context.query(current.getString("objectId"));
                if (local == null || etcdService.getOne("/tombstones/" + local.getUuid()) != null) continue;
                String hash = current.getJSONObject("metadata").getString("sha256");
                prepareRemote(new File(local.getPath()), local.getUuid(), local.getFileName(), hash,
                        NodeObject.builder().url(job.getString("url")).build(), 5000);
                federation.call(job.getString("url"),"data/replica/commit",current);
                etcdService.compareAndSet(entry.getKey(),entry.getValue(),null);
            }
            catch(Exception unavailable) { log.debug("Replica metadata publication will retry"); }
        }
    }
    private void refreshReplicaMetadata() {
        if (federation==null || !(metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore)) return;
        var entries=new ArrayList<>(etcdService.scan("/replica-sources/").entrySet());
        if (entries.isEmpty()) return;
        int start=Math.floorMod(sourceCursor,entries.size()); sourceCursor=(start+Math.min(16,entries.size()))%entries.size();
        for (int step=0;step<Math.min(16,entries.size());step++) {
            var entry=entries.get((start+step)%entries.size());
            JSONObject previous=JSONObject.parseObject(entry.getValue()); if (previous.getBooleanValue("forked")) continue;
            String owner=previous.getString("nodeId"),url=federation.peers().get(owner);
            if (url==null) continue;
            String id=entry.getKey().substring("/replica-sources/".length());
            if (etcdService.getOne("/tombstones/"+id)!=null) continue;
            try {
                JSONObject response=federation.call(url,"data/replica-source/"+previous.getString("objectId"),null);
                if (response.getBooleanValue("deleted")) {
                    // Only an authenticated current owner can propagate deletion. Detached copies remain local.
                    Map<String,String> expected=new HashMap<>(); expected.put(entry.getKey(),entry.getValue()); expected.put("/tombstones/"+id,null);
                    etcdService.compareAndWrite(expected,Map.of("/tombstones/"+id,Long.toString(System.currentTimeMillis())),Set.of());
                } else if (response.getJSONObject("snapshot")!=null) commitReplica(response.getJSONObject("snapshot"),owner);
            } catch(Exception unavailable) { /* The last complete local snapshot remains readable. */ }
        }
    }

    public void resumePreparedReplicas() {
        String prefix = "/prepared-replicas/" + context.uuid + "/";
        etcdService.scan(prefix).forEach((key, raw) -> {
            try {
                if (metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore) return; // Owner commit arrives explicitly; no shared metadata lookup.
                JSONObject prepared = JSONObject.parseObject(raw);
                FileObject resource = prepared.getJSONObject("file").toJavaObject(FileObject.class);
                String id = resource.getUuid();
                if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid prepared identity");
                Path target = contentPath(resource); resource.setPath(target.toString());
                if (etcdService.getOne("/tombstones/" + resource.getCopyOf()) != null || etcdService.getOne("/tombstones/" + id) != null) {
                    Files.deleteIfExists(target); DurableFiles.syncDirectory(target.getParent()); etcdService.delete(key); return;
                }
                String committed = etcdService.getOne("/files/" + resource.getCopyOf() + "/meta");
                if (committed == null) return;
                if (!prepared.getString("sha256").equals(JSONObject.parseObject(committed).getString("sha256"))) {
                    throw new IOException("Published content does not match prepared replica");
                }
                if (Files.size(target) != resource.getSize() || !prepared.getString("sha256").equals(cc.ginpika.bootfs.kernel.object.ContentHash.sha256(target))) {
                    throw new IOException("Prepared replica bytes are missing or corrupt");
                }
                context.record(resource, id); registerReplica(resource);
                etcdService.delete(key);
            } catch (Exception failure) { log.warn("Prepared replica recovery will retry: {}", failure.getMessage()); }
        });
    }

    public JSONObject prepareRemote(File file, String id, String name, String sha256, NodeObject node, int timeoutMs) throws IOException {
        return prepareRemote(file, id, name, sha256, node, timeoutMs, null);
    }

    public JSONObject prepareRemote(File file, String id, String name, String sha256, NodeObject node, int timeoutMs, FileObject pending) throws IOException {
        if (metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore) {
            JSONObject snapshot=snapshot(id); snapshot.put("sha256",sha256);
            if (pending != null) {
                JSONObject resource = JSONObject.from(pending); resource.remove("path");
                snapshot.put("file", resource);
            }
            return new HttpFileTransferClient().prepare(file,id,name,sha256,node,federation.tokenFor(node.getUrl()),timeoutMs,snapshot,federation.identity());
        }
        return new HttpFileTransferClient().prepare(file, id, name, sha256, node, credentials==null ? null : credentials.token(), timeoutMs);
    }

    private SimpleTransferResult receipt(FileObject resource) {
        return SimpleTransferResult.builder().succeed(true).fileNode(resource).build();
    }

    private synchronized FileObject finishReplica(JSONObject intent) throws IOException {
        FileObject requested = intent.getJSONObject("file").toJavaObject(FileObject.class);
        String uuid = requested.getUuid();
        if (uuid == null || !uuid.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid replica identity");
        String stageName = intent.getString("stage");
        if (stageName == null || !stageName.matches("\\.replica-[A-Za-z0-9_-]+\\.part")) throw new IOException("Invalid replica stage");
        Path directory = Path.of(tfsConfig.getPathPrefix());
        Path staged = directory.resolve(stageName), target = directory.resolve(uuid);
        if (etcdService.getOne("/tombstones/" + requested.getCopyOf()) != null || etcdService.getOne("/tombstones/" + uuid) != null) {
            context.remove(uuid);
            etcdService.delFileAndReplicas(uuid);
            Files.deleteIfExists(staged); Files.deleteIfExists(target); DurableFiles.syncDirectory(directory);
            etcdService.delete(prefix() + uuid);
            throw new IOException("Replica belongs to a deleted resource");
        }
        if (!Files.isRegularFile(target)) {
            if (!Files.isRegularFile(staged)) throw new IOException("Replica staging is missing");
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            DurableFiles.syncDirectory(directory);
        }
        if (Files.isRegularFile(staged)) {
            if (Files.mismatch(staged, target) != -1) throw new IOException("Immutable replica content collision");
            Files.delete(staged); DurableFiles.syncDirectory(directory);
        }
        DurableFiles.syncFile(target); DurableFiles.syncDirectory(directory);
        requested.setPath(target.toString());
        FileObject resource = context.query(uuid);
        if (resource == null) {
            context.record(requested, uuid);
            resource = requested;
        }
        registerReplica(resource);
        if (etcdService.getOne("/tombstones/" + requested.getCopyOf()) != null || etcdService.getOne("/tombstones/" + uuid) != null) {
            throw new IOException("Replica was deleted during publication");
        }
        etcdService.delete(prefix() + uuid);
        return resource;
    }

    private void registerReplica(FileObject resource) {
        JSONObject metadata = new JSONObject();
        String name = resource.getFileName(); int dot = name == null ? -1 : name.lastIndexOf('.');
        metadata.put("size", resource.getSize()); metadata.put("ext", dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT));
        metadata.put("copyOf", resource.getCopyOf());
        String originalMeta = etcdService.getOne("/files/" + resource.getCopyOf() + "/meta");
        if (originalMeta != null) {
            JSONObject source = JSONObject.parseObject(originalMeta);
            for (String field : List.of("textEncoding", "textVersion", "etag")) if (source.containsKey(field)) metadata.put(field, source.get(field));
        }
        if (resource.getContentObjectId() != null) metadata.put("contentObjectId", resource.getContentObjectId());
        try { metadata.put("sha256", cc.ginpika.bootfs.kernel.object.ContentHash.sha256(Path.of(resource.getPath()))); }
        catch (IOException failure) { throw new IllegalStateException("Replica bytes are unavailable", failure); }
        etcdService.putFileReplica(resource.getCopyOf(), resource.getUuid(), context.buildUrl(resource.getUuid()), metadata.toJSONString());
        String originalDoc = etcdService.getOne("/documents/full-text/" + resource.getCopyOf());
        if (originalDoc != null && resource.getContentObjectId() != null) {
            JSONObject document = JSONObject.parseObject(originalDoc);
            document.put("uuid", resource.getUuid()); document.put("copyOf", resource.getCopyOf()); document.put("contextUuid", context.uuid);
            document.put("contentObjectId", resource.getContentObjectId()); document.put("size", resource.getSize());
            for (String field : List.of("parent", "hlsAvailable", "thumbAvailable", "albumAvailable", "resources", "publicUrl")) document.remove(field);
            document.put("thumbUrl", context.buildThumbUrl(resource.getUuid())); document.put("poster", context.buildThumbUrl(resource.getUuid()));
            etcdService.put("/documents/full-text/" + resource.getUuid(), document.toJSONString());
        }
        if (etcdService.getOne("/documents/full-text/" + resource.getUuid()) == null) {
            LocalDateTime now = LocalDateTime.now();
            meiliSearchService.addToFullText(FullTextDocument.builder().uuid(resource.getUuid()).title(resource.getFileName())
                    .fileName(resource.getFileName()).size(resource.getSize()).copyOf(resource.getCopyOf()).tags(new JSONArray())
                    .poster(context.buildThumbUrl(resource.getUuid())).thumbUrl(context.buildThumbUrl(resource.getUuid()))
                    .createdAt(now).updatedAt(now).fileCreatedAt(resource.getCreatedAt()).contextUuid(context.uuid).build());
        }
    }

    public void resumePendingReplicas() {
        resumeReplicaCommits();
        refreshReplicaMetadata();
        resumePreparedReplicas();
        for (String raw : etcdService.scan(prefix()).values()) {
            try { finishReplica(JSONObject.parseObject(raw)); }
            catch (Exception failure) { log.warn("Pending replica will retry: {}", failure.getMessage()); }
        }
    }

    public void put(File rawFile, String fileId, String originFileName, NodeObject server) throws IOException {
        if (metadata instanceof cc.ginpika.bootfs.kernel.cluster.DynamicMetadataStore) {
            JSONObject receipt=prepareRemote(rawFile,fileId,originFileName,cc.ginpika.bootfs.kernel.object.ContentHash.sha256(rawFile.toPath()),server,30000);
            federation.call(server.getUrl(),"data/replica/commit",snapshot(fileId));
            etcdService.put("/replication-receipts/"+context.uuid+"/"+fileId+"/"+receipt.getString("nodeId"),new JSONObject(Map.of("url",server.getUrl(),"receipt",receipt)).toJSONString());
            return;
        }
        prepareRemote(rawFile, fileId, originFileName, cc.ginpika.bootfs.kernel.object.ContentHash.sha256(rawFile.toPath()), server, 30000);
    }
}
