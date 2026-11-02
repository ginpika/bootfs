package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.dto.*;
import cc.ginpika.bootfs.service.SsoService;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.FileService;
import java.nio.file.Path;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"sso.enabled=true", "s3.enabled=false", "tfs.ffmpeg-url=/nonexistent-ffmpeg"})
@AutoConfigureMockMvc
@DirtiesContext
class EndpointSecurityIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("tfs.root-path", ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired FileService files;
    @Autowired Context context;
    @MockBean SsoService sso;
    final Cookie cookie = new Cookie("sso_token", "test-session");

    @BeforeEach void authentication() {
        when(sso.isEnabled()).thenReturn(true);
        when(sso.getCookieName()).thenReturn("sso_token");
        when(sso.getLoginUrl(anyString())).thenReturn("https://sso.example/login");
        role("admin");
    }
    void role(String role) {
        SsoUser user = new SsoUser(); user.setRole(role); user.setUsername("test");
        SsoSession session = new SsoSession(); session.setAuthenticated(true); session.setUser(user);
        when(sso.checkSession("test-session")).thenReturn(Mono.just(session));
    }

    @Test void everyMappedApplicationEndpointRequiresAuthenticationByDefault() throws Exception {
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getName().startsWith("cc.ginpika.bootfs.")) continue;
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "missing").replace("**", "missing");
                for (var verb : entry.getKey().getMethodsCondition().getMethods()) {
                    if (path.equals("/api/cluster/pairing/redeem")) continue; // One-time code authentication.
                    var request = request(HttpMethod.valueOf(verb.name()), path).contentType("application/json").content("{}");
                    for (var expression : entry.getKey().getParamsCondition().getExpressions()) {
                        if (!expression.isNegated()) request.param(expression.getName(), expression.getValue() == null ? "1" : expression.getValue());
                    }
                    int status = mvc.perform(request).andReturn().getResponse().getStatus();
                    assertTrue(status == 302 || status == 401, verb + " " + pattern + " returned " + status);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 70, "Unexpectedly small endpoint inventory: " + checked);
    }

    @Test void ordinarySsoAccountsCannotReadOrModifyTheSharedApplication() throws Exception {
        role("user");
        mvc.perform(get("/queryPageOffset").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/etcd/keys").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/f/missing").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/cluster/pairing/code").cookie(cookie).header("Origin", "http://localhost"))
                .andExpect(status().isForbidden());
    }

    @Test void cookieWritesNeedSameOriginEvidenceAndDoNotTrustACustomHeaderOverAnEvilOrigin() throws Exception {
        mvc.perform(post("/api/cluster/pairing/code").cookie(cookie)).andExpect(status().isForbidden());
        for (String origin : new String[]{"https://evil.example", "http://sibling.localhost", "null", "http://localhost:8181"}) {
            mvc.perform(post("/api/cluster/pairing/code").cookie(cookie).header("Origin", origin).header("X-BootFS-Request", "1"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/cluster/pairing/code").cookie(cookie).header("Origin", "http://localhost"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/cluster/pairing/code").cookie(cookie).header("X-BootFS-Request", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/logout").cookie(cookie).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test void identityAndStaticAssetsWorkAndBrowserLoginNavigationIsAllowed() throws Exception {
        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/queryPageOffset").cookie(cookie).header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document")).andExpect(status().isOk());
        mvc.perform(get("/security-utils.js")).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(options("/api/cluster/pairing/code").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }

    @Test void aForgedProxyMimeCannotTurnAnUploadIntoSameOriginHtml() throws Exception {
        String id = files.publish(new java.io.ByteArrayInputStream("<script>alert(1)</script>".getBytes()), "test.html", false).file().getUuid();
        mvc.perform(get("/f/" + id).cookie(cookie).header("Is-Proxy", "1").header("Accept-Type", "text/html"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/p/" + id).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test void complexAndAmbiguousJsonIsRejectedBeforeItCanMutateState() throws Exception {
        int before = context.STORAGE.size();
        for (String body : new String[]{"{\"fileName\":\"first\",\"fileName\":\"second\"}", "{\"extra\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"}) {
            mvc.perform(post("/virtual-file").cookie(cookie).header("Origin", "http://localhost")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/virtual-file").cookie(cookie).header("Origin", "http://localhost")
                .contentType("application/json").content("{\"fileName\":\"" + "x".repeat(16 * 1024 * 1024) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(before, context.STORAGE.size());
    }

    @Test void segmentNamesCannotInjectNewPlaylistDirectives() throws Exception {
        mvc.perform(post("/hls/missing/segments/reorder").cookie(cookie).header("Origin", "http://localhost")
                .contentType("application/json").content("[\"ok.ts\\n#EXT-X-KEY:METHOD=AES-128,URI=\\\"http://internal/\\\"\"]"))
                .andExpect(status().isBadRequest());
    }

    @Test void plainTextCannotBypassJsonDepthValidationOnLegacyStringBodies() throws Exception {
        mvc.perform(post("/removeFileBatch").cookie(cookie).header("Origin", "http://localhost")
                .contentType("text/plain").content("[".repeat(65) + "0" + "]".repeat(65))).andExpect(status().isBadRequest());
    }

    @Test void privateThumbnailsNeverBecomeSharedCacheEntries() throws Exception {
        String id = files.publish(new java.io.ByteArrayInputStream(new byte[]{1,2,3}), "image.png", false).file().getUuid();
        mvc.perform(get("/thumb/" + id).cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/thumb/" + id)).andExpect(status().is3xxRedirection());
    }

    @Test void batchArchivesRequireLoginAndCannotExportTraversalNames() throws Exception {
        String id = files.publish(new java.io.ByteArrayInputStream("test".getBytes()), "../../evil.txt", false).file().getUuid();
        String body = mvc.perform(post("/downloadFileBatch").cookie(cookie).header("Origin", "http://localhost")
                .contentType("application/json").content("[\"" + id + "\"]")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String archive = com.alibaba.fastjson2.JSON.parseObject(body).getString("uuid");
        mvc.perform(get("/g/b/" + archive)).andExpect(status().is3xxRedirection());
        byte[] zip = mvc.perform(get("/g/b/" + archive).cookie(cookie)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            assertEquals("evil.txt", input.getNextEntry().getName()); assertEquals("test", new String(input.readAllBytes()));
        }
    }

    @Test void ssoReturnUrlsUseTheConfiguredOriginInsteadOfTheHostHeader() throws Exception {
        mvc.perform(get("/dashboard").header("Host", "evil.example"));
        verify(sso).getLoginUrl("http://localhost:8181/dashboard");
    }
}
