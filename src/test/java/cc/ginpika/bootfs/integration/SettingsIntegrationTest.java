package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.settings.*;
import cc.ginpika.bootfs.kernel.cluster.ClusterCredentials;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.sso.SsoConfig;
import cc.ginpika.bootfs.s3.*;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"sso.enabled=false","s3.enabled=false"})
@AutoConfigureMockMvc @DirtiesContext
class SettingsIntegrationTest {
    static final Path ROOT=MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("tfs.root-path",ROOT::toString); }
    @Autowired MockMvc mvc; @Autowired NodeSettings settings; @Autowired ClusterCredentials credentials;
    @Autowired S3Config s3; @Autowired SsoConfig sso; @Autowired TfsConfig files; @Autowired MetadataStore metadata;
    long revision() { return ((Number)settings.view().get("revision")).longValue(); }
    MockHttpServletRequestBuilder save(Map<String,Object> patch) {
        return put("/api/settings").contentType("application/json").header("X-BootFS-Request","1")
                .content(JSON.toJSONString(Map.of("revision",revision(),"values",patch)));
    }
    MockHttpServletRequestBuilder signedS3(String method,String secret) {
        String path="/s3/bootfs/settings-test-object";
        byte[] content=method.equals("PUT") ? "settings-object".getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        String date=stamp.substring(0,8), hash=S3CryptoUtils.sha256Hex(content), headers="host;x-amz-content-sha256;x-amz-date", scope=date+"/us-east-1/s3/aws4_request";
        String canonical=method+"\n"+path+"\n\nhost:localhost\nx-amz-content-sha256:"+hash+"\nx-amz-date:"+stamp+"\n\n"+headers+"\n"+hash;
        byte[] key=("AWS4"+secret).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(String part:new String[]{date,"us-east-1","s3","aws4_request"}) key=S3CryptoUtils.hmacSha256(key,part);
        String signature=S3CryptoUtils.hmacSha256Hex(key,"AWS4-HMAC-SHA256\n"+stamp+"\n"+scope+"\n"+S3CryptoUtils.sha256Hex(canonical));
        return (method.equals("PUT")?put(path):get(path)).content(content).contentType("application/octet-stream").header("Host","localhost")
            .header("x-amz-date",stamp).header("x-amz-content-sha256",hash)
            .header("Authorization","AWS4-HMAC-SHA256 Credential=new-key/"+scope+", SignedHeaders="+headers+", Signature="+signature);
    }
    @Test void settingsPageAndApiUseNodeDefaultsWithoutDisclosingCredentials() throws Exception {
        mvc.perform(get("/settings")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("访问权限")));
        mvc.perform(get("/api/settings")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.values.s3SecretKey").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
    }
    @Test void validationCsrfAndRevisionFailuresLeaveSettingsUnchanged() throws Exception {
        int copies=files.getCopies(); long before=revision();
        mvc.perform(save(Map.of("copies",0))).andExpect(status().isBadRequest());
        mvc.perform(save(Map.of("copies","2"))).andExpect(status().isBadRequest());
        mvc.perform(save(Map.of("unknown",true))).andExpect(status().isBadRequest());
        mvc.perform(save(Map.of("s3Enabled",true))).andExpect(status().isBadRequest());
        mvc.perform(save(Map.of("ssoEnabled",true,"ssoServerUrl","http://127.0.0.1:1"))).andExpect(status().isBadRequest());
        mvc.perform(save(Map.of("copies",2)).header("Origin","https://evil.example")).andExpect(status().isForbidden());
        assertEquals(copies,files.getCopies()); assertEquals(before,revision());
        mvc.perform(put("/api/settings").contentType("application/json").content("{\"revision\":0,\"values\":{}}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings").header("X-BootFS-Request","1").contentType("application/json").content("{\"revision\":0,\"values\":{}}"))
                .andExpect(status().isConflict());
    }
    @Test void runtimeFlagsAndWriteOnlyS3CredentialsApplyAndRotateWithoutRestart() throws Exception {
        mvc.perform(save(Map.of("s3AccessKey","new-key","s3SecretKey","new-secret-value","s3Enabled",true,"copies",3,"allowedRoles","")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values.s3SecretConfigured").value(true));
        assertTrue(s3.isEnabled()); assertEquals("new-secret-value",s3.getSecretKey()); assertEquals(3,files.getCopies()); assertTrue(sso.permitsRole("user"));
        mvc.perform(get("/s3/bootfs")).andExpect(status().isForbidden());
        mvc.perform(signedS3("PUT","new-secret-value")).andExpect(status().isOk());
        mvc.perform(save(Map.of("s3SecretKey","rotated-secret-value","publicSharingEnabled",false,"allowPrivateProbes",false,"localBypassEnabled",false)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rotated-secret-value"))));
        mvc.perform(signedS3("GET","new-secret-value")).andExpect(status().isForbidden());
        mvc.perform(signedS3("GET","rotated-secret-value")).andExpect(status().isOk()).andExpect(content().string("settings-object"));
        assertEquals("rotated-secret-value",s3.getSecretKey()); assertFalse(sso.isPublicSharingEnabled()); assertFalse(sso.isLocalBypassEnabled()); assertFalse(settings.current().bool("allowPrivateProbes"));
        mvc.perform(save(Map.of("copies",2))).andExpect(status().isOk()); assertEquals("rotated-secret-value",s3.getSecretKey());
        mvc.perform(get("/api/etcd/keys")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rotated-secret-value"))));
        assertTrue(metadata.scan("").keySet().stream().noneMatch(k->k.startsWith("/settings/")));
        mvc.perform(save(Map.of("s3Enabled",false))).andExpect(status().isOk());
        mvc.perform(get("/s3/bootfs")).andExpect(status().isNotFound());
        mvc.perform(save(Map.of("s3SecretKey",""))).andExpect(status().isOk()); assertEquals("",s3.getSecretKey());
    }
    @Test void ownerTokenRotationPreservesSigningIdentityAndNeverReturnsToken() throws Exception {
        String old=credentials.token(), signing=credentials.publicKey();
        mvc.perform(post("/api/settings/credentials/rotate").header("X-BootFS-Request","1").contentType("application/json").content("{\"confirm\":\"ROTATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rotated").value(true)).andExpect(jsonPath("$.token").doesNotExist());
        assertFalse(credentials.accepts(old)); assertEquals(signing,credentials.publicKey());
        var reopened=new ClusterCredentials(files,""); assertTrue(reopened.accepts(credentials.token())); assertEquals(signing,reopened.publicKey());
    }
}
