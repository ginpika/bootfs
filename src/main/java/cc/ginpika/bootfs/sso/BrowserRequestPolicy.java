package cc.ginpika.bootfs.sso;

import java.net.URI;
import javax.servlet.http.HttpServletRequest;

/** Same-origin checks also reject sibling subdomains sharing an SSO cookie. */
public final class BrowserRequestPolicy {
    private BrowserRequestPolicy() { }

    public static boolean allows(HttpServletRequest request, boolean requireEvidence) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return false;
        String origin = request.getHeader("Origin"), referer = request.getHeader("Referer");
        if (origin != null && !sameOrigin(origin, request, true)) return false;
        if (referer != null && !sameOrigin(referer, request, false)) return false;
        return !requireEvidence || origin != null || referer != null || "same-origin".equals(site)
                || "1".equals(request.getHeader("X-BootFS-Request"));
    }

    private static boolean sameOrigin(String value, HttpServletRequest request, boolean origin) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawFragment() == null
                    && (!origin || ((uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null))
                    && uri.getScheme().equalsIgnoreCase(request.getScheme())
                    && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && (uri.getPort() < 0 ? "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80 : uri.getPort()) == request.getServerPort();
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
