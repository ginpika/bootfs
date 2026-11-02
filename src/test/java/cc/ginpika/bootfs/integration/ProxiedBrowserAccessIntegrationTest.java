package cc.ginpika.bootfs.integration;

import cc.ginpika.bootfs.dto.SsoSession;
import cc.ginpika.bootfs.dto.SsoUser;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.service.SsoService;
import cc.ginpika.bootfs.settings.NodeSettings;
import cc.ginpika.bootfs.settings.SettingsValues;
import com.alibaba.fastjson2.JSON;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "tfs.web-entrypoint=https://files.example", "sso.enabled=true", "sso.server-url=https://sso.example",
        "sso.allowed-roles=admin", "sso.local-bypass.enabled=false",
        "bootfs.security.public-sharing.enabled=false", "s3.enabled=false", "tfs.ffmpeg-url=/nonexistent-ffmpeg"
})
@AutoConfigureMockMvc
@DirtiesContext
class ProxiedBrowserAccessIntegrationTest {
    static final Path ROOT = MononodeIntegrationTest.tempRoot();
    static final String ENTRYPOINT = "https://files.example";
    final Cookie cookie = new Cookie("sso_token", "proxied-session");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("tfs.root-path", ROOT::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired NodeSettings settings;
    @Autowired MetadataStore metadata;
    @MockBean SsoService sso;

    @BeforeEach void authentication() throws Exception {
        updateSettings(Map.of("ssoEnabled", true, "copies", 1));
        when(sso.isEnabled()).thenAnswer(invocation -> settings.current().bool("ssoEnabled"));
        when(sso.getCookieName()).thenReturn("sso_token");
        when(sso.getLoginUrl(anyString())).thenReturn("https://sso.example/login");
        SsoUser user = new SsoUser();
        user.setUsername("proxied-admin");
        user.setRole("admin");
        SsoSession session = new SsoSession();
        session.setAuthenticated(true);
        session.setUser(user);
        when(sso.checkSession("proxied-session")).thenReturn(Mono.just(session));
    }

    long revision() { return ((Number) settings.view().get("revision")).longValue(); }

    void updateSettings(Map<String, Object> patch) throws Exception {
        Map<String, Object> next = new LinkedHashMap<>(settings.current().map());
        next.putAll(patch);
        settings.update(revision(), new SettingsValues(next), false, false, metadata);
    }

    // Model TLS termination without trusting or parsing proxy headers at the backend.
    MockHttpServletRequestBuilder backend(MockHttpServletRequestBuilder request) {
        return request.with(servlet -> {
            servlet.setScheme("http");
            servlet.setServerName("bootfs.internal");
            servlet.setServerPort(8181);
            return servlet;
        });
    }

    MockHttpServletRequestBuilder browser(MockHttpServletRequestBuilder request) {
        return backend(request).cookie(cookie).header("Sec-Fetch-Site", "same-origin")
                .header("Origin", ENTRYPOINT).header("Referer", ENTRYPOINT + "/");
    }

    @Test void administratorCanEnterAndUseApisThroughTheConfiguredHttpsEntrypoint() throws Exception {
        assertEquals("none", settings.current().string("forwardHeadersStrategy"));
        mvc.perform(backend(get("/")).cookie(cookie).header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document")
                        .header("Referer", "https://sso.example/"))
                .andExpect(status().isOk()).andExpect(view().name("admin-dashboard"));
        mvc.perform(browser(get("/api/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("proxied-admin"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(browser(post("/api/cluster/pairing/code"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString());
        mvc.perform(backend(get("/api/auth/me")).cookie(cookie).header("Referer", ENTRYPOINT + "/dashboard"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("admin"));
    }

    @Test void proxiedSettingsWritesPassBothTheInterceptorAndControllerPolicies() throws Exception {
        mvc.perform(browser(put("/api/settings")).contentType("application/json")
                        .content(JSON.toJSONString(Map.of("revision", revision(), "values", Map.of("copies", 2)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.values.copies").value(2));
        assertEquals(2, settings.current().integer("copies"));
    }

    @Test void foreignOriginsWrongPortsAndForgedProxyHeadersCannotAuthorizeRequests() throws Exception {
        for (String origin : new String[]{"https://evil.example", "https://sibling.files.example",
                "https://files.example:8443", "http://files.example", "null"}) {
            mvc.perform(backend(get("/api/auth/me")).cookie(cookie).header("Origin", origin)
                            .header("Sec-Fetch-Site", "same-origin").header("X-BootFS-Request", "1")
                            .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "files.example"))
                    .andExpect(status().isForbidden());
            mvc.perform(backend(post("/api/cluster/pairing/code")).cookie(cookie).header("Origin", origin)
                            .header("X-BootFS-Request", "1"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(backend(get("/api/auth/me")).cookie(cookie).header("Origin", "https://evil.example")
                        .header("Forwarded", "proto=https;host=evil.example")
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().isForbidden());
        mvc.perform(backend(get("/api/auth/me")).cookie(cookie).header("Origin", ENTRYPOINT)
                        .header("Referer", "https://evil.example/"))
                .andExpect(status().isForbidden());
        mvc.perform(backend(get("/api/auth/me")).cookie(cookie).header("Origin", ENTRYPOINT)
                        .header("Referer", ENTRYPOINT + "/").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(backend(post("/api/cluster/pairing/code")).cookie(cookie))
                .andExpect(status().isForbidden());
    }

    @Test void disablingSsoAllowsLinkedDocumentNavigationButStillRejectsCrossOriginApiRequests() throws Exception {
        updateSettings(Map.of("ssoEnabled", false));
        mvc.perform(backend(get("/")).header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document")
                        .header("Referer", "https://external.example/"))
                .andExpect(status().isOk()).andExpect(view().name("admin-dashboard"));
        mvc.perform(backend(get("/api/settings")).header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
                        .header("Origin", "https://external.example"))
                .andExpect(status().isForbidden());
        mvc.perform(backend(post("/api/cluster/pairing/code")).header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document")
                        .header("Origin", "https://external.example"))
                .andExpect(status().isForbidden());
        mvc.perform(backend(get("/api/logout")).header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().isForbidden());
    }
}
