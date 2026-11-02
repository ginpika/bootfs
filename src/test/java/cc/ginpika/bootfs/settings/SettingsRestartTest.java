package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.BootFS;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.sso.SsoConfig;
import com.alibaba.fastjson2.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SettingsRestartTest {
    @TempDir Path root;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    ConfigurableApplicationContext start(String... extra) {
        var args=new ArrayList<>(List.of("--tfs.root-path="+root,"--server.port=0","--server.address=127.0.0.1","--tfs.ffmpeg-url=/nonexistent-ffmpeg"));
        args.addAll(List.of(extra)); return new SpringApplicationBuilder(BootFS.class).logStartupInfo(false).run(args.toArray(String[]::new));
    }
    String base(ConfigurableApplicationContext app) { return "http://127.0.0.1:"+((ServletWebServerApplicationContext)app).getWebServer().getPort(); }
    HttpResponse<String> request(ConfigurableApplicationContext app,String method,String path,Map<String,Object> patch,boolean cookie,String... headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base(app)+path)).timeout(Duration.ofSeconds(8)).header("X-BootFS-Request","1").header("Origin",base(app));
        if (cookie) b.header("Cookie","sso_token=valid-session");
        if (headers.length>0) b.headers(headers);
        if (patch!=null) {
            var settings=app.getBean(NodeSettings.class);
            b.header("Content-Type","application/json"); b.method(method,HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("revision",settings.view().get("revision"),"values",patch))));
        } else b.method(method,HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpServer auth(AtomicInteger requests) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/sso/session",exchange -> {
            requests.incrementAndGet(); boolean authenticated=Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),"").contains("valid-session");
            byte[] body=("{\"authenticated\":"+authenticated+",\"user\":{\"id\":\"one\",\"role\":\"user\",\"username\":\"one\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start(); return server;
    }
    @Test void runtimeSsoRoleLocalAndPublicRulesSurviveRestartAndProxyDatabaseWinsAtBoot() throws Exception {
        var aCount=new AtomicInteger(); var bCount=new AtomicInteger(); var a=auth(aCount); var b=auth(bCount);
        try {
            try (var app=start()) {
                assertEquals(200,request(app,"GET","/api/settings",null,false).statusCode());
                String authA="http://127.0.0.1:"+a.getAddress().getPort(),authB="http://127.0.0.1:"+b.getAddress().getPort();
                assertEquals(200,request(app,"PUT","/api/settings",Map.of("ssoEnabled",true,"ssoServerUrl",authA),false).statusCode());
                // Raw loopback recovers access; adding a forwarding marker must use SSO.
                assertEquals(200,request(app,"GET","/api/settings",null,false).statusCode());
                assertEquals(302,request(app,"GET","/api/settings",null,false,"X-Forwarded-For","127.0.0.1").statusCode());
                assertEquals(200,request(app,"PUT","/api/settings",Map.of("localBypassEnabled",false),true).statusCode());
                assertEquals(302,request(app,"GET","/api/settings",null,false).statusCode());
                assertEquals(200,request(app,"GET","/api/settings",null,true).statusCode()); // No role whitelist.
                assertEquals(400,request(app,"PUT","/api/settings",Map.of("allowedRoles","admin"),true).statusCode());
                assertEquals("",app.getBean(SsoConfig.class).getAllowedRoles()); // Invalid candidate cannot lock the caller out.
                assertEquals(200,request(app,"PUT","/api/settings",Map.of("ssoServerUrl",authB,"publicSharingEnabled",false),true).statusCode());
                int before=bCount.get(); assertEquals(200,request(app,"GET","/api/settings",null,true).statusCode()); assertTrue(bCount.get()>before);
                assertEquals(302,request(app,"GET","/f/missing",null,false).statusCode());
                assertEquals(200,request(app,"PUT","/api/settings",Map.of("publicSharingEnabled",true,"forwardHeadersStrategy","native","internalProxies","127[.]0[.]0[.]1"),true).statusCode());
                assertEquals(404,request(app,"GET","/f/missing",null,false).statusCode());
                assertTrue((Boolean)app.getBean(NodeSettings.class).view().get("restartRequired"));
            }
            try (var app=start("--server.forward-headers-strategy=none","--sso.enabled=false")) {
                assertTrue(app.getBean(SsoConfig.class).isEnabled());
                assertEquals(ServerProperties.ForwardHeadersStrategy.NATIVE,app.getBean(ServerProperties.class).getForwardHeadersStrategy());
                assertEquals("127[.]0[.]0[.]1",app.getBean(ServerProperties.class).getTomcat().getRemoteip().getInternalProxies());
                assertFalse((Boolean)app.getBean(NodeSettings.class).view().get("restartRequired"));
                assertEquals(302,request(app,"GET","/api/settings",null,false).statusCode());
            }
        } finally { a.stop(0); b.stop(0); }
    }
}
