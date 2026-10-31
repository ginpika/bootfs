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
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${bootfs.cluster.token:}") private String clusterToken;

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
        String key = "/prepared-replicas/" + context.uuid + "/" + id;
        Path directory = Path.of(tfsConfig.getPathPrefix()); Files.createDirectories(directory);
        Path target = directory.resolve(id);
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
        return receipt;
    }

    public void resumePreparedReplicas() {
        String prefix = "/prepared-replicas/" + context.uuid + "/";
        etcdService.scan(prefix).forEach((key, raw) -> {
            try {
                JSONObject prepared = JSONObject.parseObject(raw);
                FileObject resource = prepared.getJSONObject("file").toJavaObject(FileObject.class);
                String id = resource.getUuid();
                if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid prepared identity");
                Path target = Path.of(tfsConfig.getPathPrefix(), id); resource.setPath(target.toString());
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
        return new HttpFileTransferClient().prepare(file, id, name, sha256, node, clusterToken, timeoutMs);
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
        etcdService.putFileReplica(resource.getCopyOf(), resource.getUuid(), context.buildUrl(resource.getUuid()), metadata.toJSONString());
        if (etcdService.getOne("/documents/full-text/" + resource.getUuid()) == null) {
            LocalDateTime now = LocalDateTime.now();
            meiliSearchService.addToFullText(FullTextDocument.builder().uuid(resource.getUuid()).title(resource.getFileName())
                    .fileName(resource.getFileName()).size(resource.getSize()).copyOf(resource.getCopyOf()).tags(new JSONArray())
                    .poster(context.buildThumbUrl(resource.getUuid())).thumbUrl(context.buildThumbUrl(resource.getUuid()))
                    .createdAt(now).updatedAt(now).fileCreatedAt(resource.getCreatedAt()).contextUuid(context.uuid).build());
        }
    }

    public void resumePendingReplicas() {
        resumePreparedReplicas();
        for (String raw : etcdService.scan(prefix()).values()) {
            try { finishReplica(JSONObject.parseObject(raw)); }
            catch (Exception failure) { log.warn("Pending replica will retry: {}", failure.getMessage()); }
        }
    }

    public void put(File rawFile, String fileId, String originFileName, NodeObject server) throws IOException {
        new HttpFileTransferClient().transfer(rawFile, fileId, originFileName, server, clusterToken);
    }
}
