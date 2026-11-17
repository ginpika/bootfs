package cc.ginpika.bootfs.service.hls;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.inventory.FileCatalogRepository;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.kernel.cluster.NodeIdentity;
import cc.ginpika.bootfs.kernel.cluster.ResourceVisibility;
import cc.ginpika.bootfs.kernel.metadata.RocksMetadataStore;
import cc.ginpika.bootfs.search.FullTextDocument;
import cc.ginpika.bootfs.search.ResourceSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HlsUploadServiceTest {
    @TempDir Path root;
    private RocksMetadataStore metadata;
    private LocalFileInventory inventory;
    private ResourceSearchService search;
    private HlsFileService hlsFiles;
    private HlsUploadService service;
    private HlsUploadController controller;

    @BeforeEach void setup() throws Exception {
        TfsConfig config = new TfsConfig();
        config.setRootPath(root.toString());
        config.setWebEntrypoint("http://node");
        metadata = new RocksMetadataStore(root.resolve("metadata"));
        NodeIdentity identity = new NodeIdentity(config);
        inventory = new LocalFileInventory(config, new FileCatalogRepository(metadata, config, identity), identity);
        inventory.load();
        search = mock(ResourceSearchService.class);
        hlsFiles = new HlsFileService(config, new ResourceVisibility(metadata));
        service = new HlsUploadService(inventory, config, search, hlsFiles);
        controller = new HlsUploadController(service);
    }

    @AfterEach void close() {
        metadata.close();
    }

    @Test void segmentUploadReorderFinalizeAndDeleteManageFilesAndPlaylistCleanly() throws Exception {
        Map<String, Object> created = service.createVirtualFile("demo.mp4", "videos/2026/", "video/mp4");
        String uuid = (String) created.get("uuid");
        assertEquals("videos/2026/demo.mp4", inventory.query(uuid).getFileName());
        assertEquals("1", inventory.query(uuid).getHlsAvailable());
        verify(search).addToFullText(any(FullTextDocument.class));

        Path playlist = hlsFiles.playlistPath(uuid);
        assertTrue(Files.isRegularFile(playlist));

        MockMultipartFile seg0 = new MockMultipartFile("segment", "seg_000.ts", "video/mp2t", new byte[]{1, 2, 3});
        MockMultipartFile seg1 = new MockMultipartFile("segment", "seg_001.ts", "video/mp2t", new byte[]{4, 5, 6, 7});
        service.uploadSegment(uuid, seg0, 0, 4.5);
        service.uploadSegment(uuid, seg1, 1, 6.25);
        service.finalizePlaylist(uuid);

        Map<String, Object> info = service.segmentInfo(uuid);
        assertEquals(2, info.get("segmentCount"));
        assertEquals(7L, info.get("totalSize"));
        assertEquals(true, info.get("hasPlaylist"));
        assertEquals(true, info.get("isFinalized"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) service.segmentList(uuid).get("segments");
        assertEquals(List.of("seg_000.ts", "seg_001.ts"), listed.stream().map(row -> (String) row.get("fileName")).toList());
        assertEquals(4.5, (Double) listed.get(0).get("duration"), 0.0001);

        service.reorderSegments(uuid, List.of("seg_001.ts", "seg_000.ts"));
        String reorderedPlaylist = Files.readString(playlist);
        assertTrue(reorderedPlaylist.contains("#EXT-X-ENDLIST"));
        assertTrue(reorderedPlaylist.indexOf("seg_001.ts") < reorderedPlaylist.indexOf("seg_000.ts"));

        // Appending after finalize re-opens the event playlist until finalized again.
        MockMultipartFile seg2 = new MockMultipartFile("segment", "seg_002.ts", "video/mp2t", new byte[]{8, 9});
        service.uploadSegment(uuid, seg2, 2, 5.0);
        assertFalse(Files.readString(playlist).contains("#EXT-X-ENDLIST"));

        service.deleteSegment(uuid, "seg_000.ts");
        assertFalse(Files.exists(hlsFiles.segmentPath(uuid, "seg_000.ts")));
        String afterDelete = Files.readString(playlist);
        assertFalse(afterDelete.contains("seg_000.ts"));
        assertFalse(afterDelete.contains("4.500000"));
        assertTrue(afterDelete.contains("seg_001.ts"));
        assertTrue(afterDelete.contains("seg_002.ts"));
    }

    @Test void controllerTranslatesValidationMissingAndInternalFailuresWithoutLeakingDetails() throws Exception {
        assertThrows(ResponseStatusException.class,
                () -> controller.createVirtualFile(Map.of("fileName", "bad/name.mp4", "folder", "videos/")));

        ResponseEntity<?> missing = controller.getHlsSegmentInfo("missing");
        assertEquals(404, missing.getStatusCodeValue());

        FileObject nonHls = new FileObject(root.resolve("data/plain").toString(), "plain", "plain.mp4", 1L);
        inventory.record(nonHls, "plain");
        MockMultipartFile ts = new MockMultipartFile("segment", "a.ts", "video/mp2t", new byte[]{1});
        ResponseEntity<?> wrongType = controller.uploadHlsSegment("plain", ts, 0, 5.0);
        assertEquals(400, wrongType.getStatusCodeValue());

        ResponseEntity<?> badReorder = controller.reorderHlsSegments("plain", List.of("../evil.ts"));
        assertEquals(400, badReorder.getStatusCodeValue());
        assertNull(badReorder.getBody());

        ResponseEntity<?> badDelete = controller.deleteHlsSegment("plain", "bad.mp4");
        assertEquals(400, badDelete.getStatusCodeValue());
        assertNull(badDelete.getBody());

        HlsUploadService failing = mock(HlsUploadService.class);
        when(failing.segmentInfo("boom")).thenThrow(new IOException("secret-disk-path /var/private"));
        ResponseEntity<?> internalError = new HlsUploadController(failing).getHlsSegmentInfo("boom");
        assertEquals(500, internalError.getStatusCodeValue());
        assertEquals(Map.of("error", "Operation failed"), internalError.getBody());
    }

    @Test void tombstonedVirtualResourceIsHiddenFromSegmentOperations() throws Exception {
        Map<String, Object> created = service.createVirtualFile("video.mp4", "", "video/mp4");
        String uuid = (String) created.get("uuid");
        metadata.put("/tombstones/" + uuid, "deleted");
        assertEquals(404, controller.getHlsSegmentInfo(uuid).getStatusCodeValue());
        MockMultipartFile ts = new MockMultipartFile("segment", "a.ts", "video/mp2t", new byte[]{1});
        assertEquals(404, controller.uploadHlsSegment(uuid, ts, 0, 5.0).getStatusCodeValue());
    }

    @Test void playlistParserHandlesFallbackWhenManifestIsAbsent() throws Exception {
        String uuid = "manual-hls";
        inventory.record(FileObject.builder().uuid(uuid).fileName("manual.mp4").size(0L).hlsAvailable("1").build(), uuid);
        Path dir = hlsFiles.directory(uuid);
        Files.createDirectories(dir);
        Files.write(dir.resolve("002.ts"), "two".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("001.ts"), "one".getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) service.segmentList(uuid).get("segments");
        assertEquals(List.of("001.ts", "002.ts"), segments.stream().map(row -> (String) row.get("fileName")).toList());
        assertEquals(HlsPlaylist.DEFAULT_DURATION, segments.get(0).get("duration"));
    }
}
