package cc.ginpika.bootfs.sso;

import java.net.URI;
import javax.servlet.http.HttpServletRequest;

/** Same-origin checks also reject sibling subdomains sharing an SSO cookie. */
public final class BrowserRequestPolicy {
    private BrowserRequestPolicy() { }

    public static boolean allows(HttpServletRequest request, boolean requireEvidence) {
        return allows(request, requireEvidence, null);
    }

    public static boolean allows(HttpServletRequest request, boolean requireEvidence, String webEntrypoint) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return false;
        String origin = request.getHeader("Origin"), referer = request.getHeader("Referer");
        if (origin != null && !sameOrigin(origin, request, true, webEntrypoint)) return false;
        if (referer != null && !sameOrigin(referer, request, false, webEntrypoint)) return false;
        return !requireEvidence || origin != null || referer != null || "same-origin".equals(site)
                || "1".equals(request.getHeader("X-BootFS-Request"));
    }

    public static boolean isDocumentNavigation(HttpServletRequest request) {
        return ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
                && "document".equals(request.getHeader("Sec-Fetch-Dest"));
    }

    private static boolean sameOrigin(String value, HttpServletRequest request, boolean origin, String webEntrypoint) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawFragment() == null
                    && (!origin || ((uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null))
                    && (uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && port(uri) == request.getServerPort() || configuredOrigin(uri, webEntrypoint));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean configuredOrigin(URI source, String webEntrypoint) {
        if (webEntrypoint == null || webEntrypoint.isBlank()) return false;
        // TLS may terminate at a proxy while the servlet still sees HTTP and an internal port.
        // Trust only the saved public entrypoint, never client-supplied forwarding headers.
        URI target = URI.create(webEntrypoint);
        return source.getScheme().equalsIgnoreCase(target.getScheme())
                && source.getHost().equalsIgnoreCase(target.getHost()) && port(source) == port(target);
    }

    private static int port(URI uri) {
        return uri.getPort() < 0 ? "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80 : uri.getPort();
    }
}
