package cc.ginpika.bootfs.sso;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
public class LocalConnectionConfiguration implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {
    private final List<String> proxyHeaders;

    public LocalConnectionConfiguration(ServerProperties serverProperties) {
        ServerProperties.Tomcat.Remoteip remoteip = serverProperties.getTomcat().getRemoteip();
        proxyHeaders = Arrays.asList(remoteip.getRemoteIpHeader(), remoteip.getProtocolHeader(),
                remoteip.getHostHeader(), remoteip.getPortHeader()).stream().filter(Objects::nonNull).toList();
    }

    @Override
    public int getOrder() {
        // Spring Boot's native RemoteIpValve customizer runs at order 0.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addEngineValves(new ValveBase(true) {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                LocalAccessPolicy.capture(request, proxyHeaders);
                getNext().invoke(request, response);
            }
        });
    }
}
