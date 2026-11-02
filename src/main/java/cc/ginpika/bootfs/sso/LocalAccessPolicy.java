package cc.ginpika.bootfs.sso;

import cc.ginpika.bootfs.dto.SsoUser;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Request-scoped local access, never a reusable SSO session or cluster credential. */
@Component
public class LocalAccessPolicy {
    static final String CONNECTION = LocalAccessPolicy.class.getName() + ".connection";
    private final SsoConfig config;

    public LocalAccessPolicy(SsoConfig config) {
        this.config = config;
    }

    // Must run before RemoteIpValve / ForwardedHeaderFilter can rewrite addresses and headers.
    static void capture(HttpServletRequest request, Collection<String> proxyHeaders) {
        URI origin = parseOrigin(request.getScheme() + "://" + request.getHeader("Host"));
        boolean forwarded = Collections.list(request.getHeaderNames()).stream().anyMatch(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.equals("forwarded") || lower.startsWith("x-forwarded-")
                    || lower.equals("x-real-ip") || lower.equals("via")
                    || lower.equals("cf-connecting-ip") || lower.equals("true-client-ip")
                    || proxyHeaders.stream().anyMatch(name::equalsIgnoreCase);
        });
        boolean direct = loopback(request.getRemoteAddr()) && loopback(request.getLocalAddr())
                && !forwarded && origin != null && localHost(origin.getHost())
                && port(origin) == request.getLocalPort()
                && Collections.list(request.getHeaders("Host")).size() == 1;
        request.setAttribute(CONNECTION, new Connection(direct, origin));
    }

    public static boolean directAccess(HttpServletRequest request) {
        return request.getAttribute(CONNECTION) instanceof Connection c && c.direct() && safeContext(request,c.origin());
    }

    public boolean authenticate(HttpServletRequest request, HttpServletResponse response) {
        if (!config.isEnabled() || !config.isLocalBypassEnabled()
                || !(request.getAttribute(CONNECTION) instanceof Connection connection)
                || !connection.direct()) return false;

        if (!safeContext(request, connection.origin())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Local access requires a same-origin request");
        }
        SsoUser user = new SsoUser();
        user.setId("local");
        user.setUsername("local");
        user.setNickname("本地访问");
        user.setRole("local");
        request.setAttribute("ssoUser", user);
        request.setAttribute("ssoLocalAccess", true);
        response.setHeader("Cache-Control", "no-store");
        return true;
    }

    private static boolean safeContext(HttpServletRequest request, URI target) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return false;
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");
        if (origin != null && !sameOrigin(parseOrigin(origin), target)) return false;
        if (referer != null && !sameOrigin(parseReferer(referer), target)) return false;
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        return read || origin != null || referer != null || "same-origin".equals(site)
                || "1".equals(request.getHeader("X-BootFS-Local-Request"));
    }

    private static URI parseOrigin(String value) {
        URI uri = parseReferer(value);
        return uri != null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null ? uri : null;
    }

    private static URI parseReferer(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawFragment() == null
                    && uri.getPort() >= -1 && uri.getPort() <= 65535 ? uri : null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static boolean sameOrigin(URI source, URI target) {
        return source != null && source.getScheme().equalsIgnoreCase(target.getScheme())
                && source.getHost().equalsIgnoreCase(target.getHost()) && port(source) == port(target);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean localHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "[0:0:0:0:0:0:0:1]".equals(host);
    }

    private static boolean loopback(String address) {
        // Numeric allowlist: never resolve a supplied name or trust private / gateway addresses.
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private record Connection(boolean direct, URI origin) { }
}
