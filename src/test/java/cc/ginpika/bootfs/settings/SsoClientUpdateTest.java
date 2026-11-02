package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.sso.SsoConfig;
import cc.ginpika.bootfs.service.SsoService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SsoClientUpdateTest {
    HttpServer server(boolean authenticated) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/sso/session",e -> {
            byte[] body=("{\"authenticated\":"+authenticated+"}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,body.length); e.getResponseBody().write(body); e.close();
        }); server.start(); return server;
    }
    @Test void changingServerRebuildsClientForNewRequests() throws Exception {
        var a=server(false); var b=server(true);
        try {
            var config=new SsoConfig(); config.setCookieName("sso_token"); config.setServerUrl("http://127.0.0.1:"+a.getAddress().getPort());
            var service=new SsoService(WebClient.create(config.getServerUrl()),config);
            assertFalse(service.checkSession("token").block(Duration.ofSeconds(5)).isAuthenticated());
            config.setServerUrl("http://127.0.0.1:"+b.getAddress().getPort());
            assertTrue(service.checkSession("token").block(Duration.ofSeconds(5)).isAuthenticated());
        } finally { a.stop(0); b.stop(0); }
    }
}
