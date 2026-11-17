package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.core.io.ComicPageExtractor;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.binding.ObjectKeyBinding;
import cc.ginpika.bootfs.file.binding.ObjectKeyRepository;
import cc.ginpika.bootfs.file.binding.ResourceWriteConflictException;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.metadata.MetadataRegistry;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable archive upload and page-by-page comic/album assembly service. */
@Slf4j
@Service
public class AlbumPublicationService {
    private final LocalFileInventory context;
    private final MetadataRegistry metadataRegistry;
    private final ResourceSearchService resourceSearch;
    private final ObjectWritePolicy writePolicy;
    private final ResourceWriteLock writeLock;
    private final ResourceDeletionService deletionService;
    private final ResourcePublicationService publicationService;

    @Autowired(required = false)
    private ObjectKeyRepository objectKeys;

    @Autowired
    public AlbumPublicationService(LocalFileInventory context, MetadataRegistry metadataRegistry,
                                   ResourceSearchService resourceSearch, ObjectWritePolicy writePolicy,
                                   ResourceWriteLock writeLock, ResourceDeletionService deletionService,
                                   ResourcePublicationService publicationService) {
        this.context = context;
        this.metadataRegistry = metadataRegistry;
        this.resourceSearch = resourceSearch;
        this.writePolicy = writePolicy;
        this.writeLock = writeLock;
        this.deletionService = deletionService;
        this.publicationService = publicationService;
    }

    public AlbumPublicationService(LocalFileInventory context, MetadataRegistry metadataRegistry,
                                   ResourceSearchService resourceSearch, ObjectWritePolicy writePolicy,
                                   ResourceWriteLock writeLock, ResourceDeletionService deletionService,
                                   ResourcePublicationService publicationService, ObjectKeyRepository objectKeys) {
        this(context, metadataRegistry, resourceSearch, writePolicy, writeLock, deletionService, publicationService);
        this.objectKeys = objectKeys;
    }

    public String uploadAlbum(MultipartFile file, String requestId, String key) throws IOException {
        synchronized (writeLock) {
            String name = key == null ? ResourcePublicationService.filename(file.getOriginalFilename()) : key;
            String bucket = writePolicy.bucket();
            String albumId = publicationService.uploadId(requestId, key == null ? "album" : "album:" + bucket + ":" + key);
            JSONObject binding = null;
            if (key != null) {
                ObjectKeyBinding current = objectKeys.find(bucket, key);
                if (current != null && !albumId.equals(current.getUuid())) {
                    throw new ResourceWriteConflictException("A file with this name already exists");
                }
                String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
                binding = ResourcePublicationService.s3Binding(key, bucket, contentType, null, null);
            }
            JSONObject album = new JSONObject();
            album.put("uuid", albumId);
            album.put("filename", name);
            // Persist the assembly intent first. The original archive is a normal durable resource
            // until every page and the ordered album source have completed publication.
            metadataRegistry.put(albumPrefix() + albumId, album.toJSONString());
            try (InputStream input = file.getInputStream()) {
                publicationService.publishWithIntent(input, name, albumId, null, false, key == null, binding, null);
            }
            FileObject existingAlbum = context.query(albumId);
            if (existingAlbum == null || !"1".equals(existingAlbum.getAlbumAvailable())) {
                finishAlbum(album);
            } else {
                metadataRegistry.delete(albumPrefix() + albumId);
            }
            return context.buildUrl(albumId);
        }
    }

    public void finishAlbum(JSONObject album) throws IOException {
        synchronized (writeLock) {
            String albumId = album.getString("uuid");
            FileObject resource = context.query(albumId);
            if (resource == null) return; // Its durable publication intent is retried before album assembly.
            if (deletionService.isDeleted(resource)) {
                metadataRegistry.delete(albumPrefix() + albumId);
                return;
            }
            String name = resource.getFileName();
            String basename = name.substring(name.lastIndexOf('/') + 1);
            int titleDot = basename.lastIndexOf('.');
            String title = titleDot > 0 ? basename.substring(0, titleDot) : basename;
            if (title.startsWith("[Comic]")) title = title.substring(7);
            if (title.startsWith("[Picture]")) title = title.substring(9);
            List<Path> pages = List.of();
            try {
                // Extraction uses a .zip suffix to select the decoder; the archive itself is stored by UUID.
                Path extractionInput = Files.createTempFile("bootfs-album-", ".zip");
                try {
                    Files.copy(Path.of(resource.getPath()), extractionInput, StandardCopyOption.REPLACE_EXISTING);
                    pages = ComicPageExtractor.extraction(extractionInput);
                } finally {
                    Files.deleteIfExists(extractionInput);
                }
                if (pages.isEmpty()) throw new IOException("Archive contains no readable pages");
                List<String> resources = new ArrayList<>();
                int pageNumber = 1;
                for (Path page : pages) {
                    if (deletionService.isDeleted(resource)) throw new IOException("Album has been deleted");
                    String childId = UUID.nameUUIDFromBytes((albumId + ":" + pageNumber).getBytes(StandardCharsets.UTF_8)).toString();
                    FileObject existing = context.query(childId);
                    if (existing == null || !Files.isRegularFile(Path.of(existing.getPath()))) {
                        try (InputStream input = Files.newInputStream(page)) {
                            publicationService.publishWithIntent(input,
                                    title + "_" + pageNumber + ResourcePublicationService.extension(page.getFileName().toString()),
                                    childId, albumId, false, false, null, null);
                        }
                    }
                    resources.add(context.buildThumbUrl(childId));
                    pageNumber++;
                }
                if (deletionService.isDeleted(resource)) throw new IOException("Album has been deleted");
                resource.setAlbumAvailable("1");
                context.record(resource, albumId);
                resourceSearch.addToFullText(FullTextDocument.builder().uuid(albumId).title(title)
                        .fileName(name).resources(JSONArray.from(resources)).poster(resources.get(0)).thumbUrl(resources.get(0))
                        .albumAvailable("1").updatedAt(LocalDateTime.now()).size(resource.getSize()).contextUuid(context.uuid).build());
                metadataRegistry.delete(albumPrefix() + albumId);
            } finally {
                for (Path page : pages) Files.deleteIfExists(page);
            }
        }
    }

    public void resumePendingAlbums() {
        for (String raw : metadataRegistry.scan(albumPrefix()).values()) {
            try {
                finishAlbum(JSONObject.parseObject(raw));
            } catch (Exception failure) {
                log.warn("Pending album assembly will retry: {}", failure.getMessage());
            }
        }
    }

    private String albumPrefix() {
        return "/pending-albums/" + context.uuid + "/";
    }
}
