package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.s3.*;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"sso.enabled=false","s3.enabled=true","s3.access-key=test","s3.secret-key=test-secret","tfs.ffmpeg-url=/nonexistent-ffmpeg"})
@AutoConfigureMockMvc
@DirtiesContext
class MononodeIntegrationTest {
    static final Path ROOT=tempRoot();
    static Path tempRoot() { try { return Files.createTempDirectory("bootfs-integration-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path",ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired Context context;
    @Autowired S3KeyIndex keys;
    @Autowired MeiliSearchService search;
    @Autowired cc.ginpika.bootfs.kernel.cluster.InventoryReconciler reconciler;

    // Exercise the real S3 filter as well as its MVC authentication marker.
    private org.springframework.test.web.servlet.ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(req -> {
            if (!req.getRequestURI().startsWith("/s3/")) return req;
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            String date = stamp.substring(0, 8), hash = S3CryptoUtils.sha256Hex(req.getContentAsByteArray() == null ? new byte[0] : req.getContentAsByteArray());
            String headers = "host;x-amz-content-sha256;x-amz-date", scope = date + "/us-east-1/s3/aws4_request";
            String canonical = req.getMethod() + "\n" + req.getRequestURI() + "\n\nhost:localhost\nx-amz-content-sha256:" + hash + "\nx-amz-date:" + stamp + "\n\n" + headers + "\n" + hash;
            byte[] key = "AWS4test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (String part : new String[]{date, "us-east-1", "s3", "aws4_request"}) key = S3CryptoUtils.hmacSha256(key, part);
            String signature = S3CryptoUtils.hmacSha256Hex(key, "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + S3CryptoUtils.sha256Hex(canonical));
            req.addHeader("Host", "localhost"); req.addHeader("x-amz-date", stamp); req.addHeader("x-amz-content-sha256", hash);
            req.addHeader("Authorization", "AWS4-HMAC-SHA256 Credential=test/" + scope + ", SignedHeaders=" + headers + ", Signature=" + signature);
            return req;
        }));
    }

    @Test void browserUploadIsS3ReadableAndS3DeleteRemovesBrowserResource() throws Exception {
        String url=perform(multipart("/f").file(new MockMultipartFile("file","星空笔记.txt","text/plain","hello".getBytes()))
                .with(request -> {request.setMethod("PUT"); return request;}))
                .andExpect(status().isOk()).andExpect(header().exists("X-BootFS-Revision"))
                .andReturn().getResponse().getContentAsString();
        String id=url.substring(url.lastIndexOf('/')+1);
        assertNotNull(context.query(id));
        assertNotNull(keys.getIndex("bootfs","星空笔记.txt"));
        perform(get("/s3/bootfs/星空笔记.txt")).andExpect(status().isOk()).andExpect(content().string("hello"));
        perform(get("/queryPageOffset").param("search","星空")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        perform(delete("/s3/bootfs/星空笔记.txt")).andExpect(status().isNoContent());
        assertNull(context.query(id));
        assertFalse(Files.exists(ROOT.resolve("data").resolve(id)));
        perform(get("/f/"+id)).andExpect(status().isNotFound());
    }

    @Test void s3UploadIsBrowserSearchableAndBrowserDeleteRemovesS3Alias() throws Exception {
        String key="books/银河说明.txt";
        perform(put("/s3/bootfs/"+key).contentType("text/plain").content("first"))
                .andExpect(status().isOk()).andExpect(header().exists("ETag"));
        String oldId=keys.getIndex("bootfs",key).getUuid();
        assertNotNull(context.query(oldId));
        perform(get("/f/"+oldId)).andExpect(status().isOk()).andExpect(content().string("first"));
        perform(put("/s3/bootfs/"+key).contentType("text/plain").content("second")).andExpect(status().isOk());
        String id=keys.getIndex("bootfs",key).getUuid();
        assertNotEquals(oldId,id);
        assertNull(context.query(oldId));
        perform(get("/queryPageOffset").param("search","银河")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        perform(post("/removeFileByUuid").param("uuid",id)).andExpect(status().isOk()).andExpect(content().string("true"));
        perform(get("/s3/bootfs/"+key)).andExpect(status().isNotFound());
        search.rebuildIndexes();
        perform(get("/queryPageOffset").param("search","银河")).andExpect(jsonPath("$.total").value(0));
    }

    @Test void failedTranscodeCannotBeReportedAsAvailable() throws Exception {
        String key="invalid-video.mp4";
        perform(put("/s3/bootfs/"+key).contentType("video/mp4").content("not-a-video")).andExpect(status().isOk());
        String id=keys.getIndex("bootfs",key).getUuid();
        perform(post("/hls/convert").param("uuid",id)).andExpect(status().isInternalServerError());
        assertNotEquals("1",context.query(id).getHlsAvailable());
        perform(delete("/s3/bootfs/"+key)).andExpect(status().isNoContent());
    }

    @Test void ownerInventoryConvergesAfterSharedMetadataWasEditedElsewhere() throws Exception {
        String key="owner-view.txt";
        perform(put("/s3/bootfs/"+key).contentType("text/plain").content("data")).andExpect(status().isOk());
        String id=keys.getIndex("bootfs",key).getUuid();
        search.updateDocumentTitle("full-text",id,"renamed.txt");
        search.updateDocumentTags("full-text",id,java.util.List.of(cc.ginpika.bootfs.domain.dto.Tag.manual("shared")));
        reconciler.reconcile();
        assertEquals("renamed.txt",context.query(id).getFileName());
        assertEquals("manual:shared",context.query(id).getTags().get(0).toIndexFormat());
        perform(delete("/s3/bootfs/"+key)).andExpect(status().isNoContent());
    }
}
