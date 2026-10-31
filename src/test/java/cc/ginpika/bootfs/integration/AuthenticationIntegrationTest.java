package cc.ginpika.bootfs.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"sso.enabled=true","s3.enabled=false","bootfs.cluster.token=test-cluster-token"})
@AutoConfigureMockMvc
@DirtiesContext
class AuthenticationIntegrationTest {
    static final Path ROOT=MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path",ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired cc.ginpika.bootfs.kernel.cluster.ClusterCredentials credentials;
    @Test void uploadsAndMetadataMutationsRequireLogin() throws Exception {
        mvc.perform(put("/f")).andExpect(status().is3xxRedirection());
        mvc.perform(put("/comic")).andExpect(status().is3xxRedirection());
        mvc.perform(put("/api/file/resource/tags").contentType("application/json").content("[]")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/hls/convert").param("uuid","resource")).andExpect(status().is3xxRedirection());
    }
    @Test void transferTokenDoesNotAuthorizeOtherMutationsAndHlsReadsRemainPublic() throws Exception {
        mvc.perform(put("/t")).andExpect(status().is3xxRedirection());
        // Authentication passes; missing multipart fields are rejected by MVC with 400.
        mvc.perform(put("/t").contentType("multipart/form-data").header("X-BootFS-Cluster-Token","test-cluster-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/f").header("X-BootFS-Cluster-Token","test-cluster-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/hls/missing/stream.m3u8")).andExpect(status().isNotFound());
    }
    @Test void clusterManagementAndVersionHistoryRequireLoginOrThePairingToken() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/cluster/status")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/cluster/leave")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/cluster/link").contentType("application/json").content("{\"url\":\"http://localhost:8182\"}"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/cluster/link/status").param("url", "http://localhost:8182"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/cluster/status").header("X-BootFS-Cluster-Token","test-cluster-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cluster/versions").param("namespace","s3").param("key","/s3/bootfs/key")
                .header("X-BootFS-Cluster-Token","test-cluster-token")).andExpect(status().isOk());
        mvc.perform(put("/t/prepare")).andExpect(status().is3xxRedirection());
        mvc.perform(put("/t/prepare").contentType("multipart/form-data").header("X-BootFS-Cluster-Token","test-cluster-token"))
                .andExpect(status().isBadRequest());
    }

    @Test void invitationCreationRequiresAuthenticationButRedemptionUsesTheCode() throws Exception {
        mvc.perform(post("/api/cluster/pairing/code")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/cluster/pairing/adopt").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        String body = mvc.perform(post("/api/cluster/pairing/code").header("X-BootFS-Cluster-Token", "test-cluster-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").doesNotExist()).andReturn().getResponse().getContentAsString();
        String code = com.alibaba.fastjson2.JSON.parseObject(body).getString("code");
        mvc.perform(post("/api/cluster/pairing/redeem").contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"requestId\":\"" + "a".repeat(64) + "\",\"nodeId\":\"remote\",\"url\":\"http://127.0.0.1:9999\",\"returnToken\":\""+"b".repeat(64)+"\",\"publicKey\":\""+credentials.publicKey()+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isString()).andExpect(jsonPath("$.nodeId").exists());
        mvc.perform(post("/api/cluster/pairing/redeem").contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"requestId\":\"" + "b".repeat(64) + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test void televisionManagementRequiresLoginWhilePlaybackRemainsPublic() throws Exception {
        mvc.perform(get("/tv/manage")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/tv/api/playlist")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/tv/api/videos")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/tv/api/playlist").contentType("application/json").content("[]"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/tv/refresh").header("X-BootFS-Cluster-Token","test-cluster-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/tv")).andExpect(status().isOk());
        mvc.perform(get("/tv/status")).andExpect(status().isOk());
        mvc.perform(get("/tv/playlist.m3u8")).andExpect(status().isOk());
        mvc.perform(get("/tv/ts/missing/0000.ts")).andExpect(status().isNotFound());
    }
}
