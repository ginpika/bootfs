package cc.ginpika.bootfs.core;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/** Site-relative URLs for BootFS resources, independent of node discovery addresses. */
public final class ResourceUrls {
    private static final Pattern ROUTE = Pattern.compile(
            "(/(?:f|p|thumb)/([A-Za-z0-9_-]+)(?:\\.webp)?|/hls/([A-Za-z0-9_-]+)/[A-Za-z0-9_.-]+)$");

    private ResourceUrls() {}

    public static String file(String id) { return "/f/" + id; }
    public static String thumb(String id) { return "/thumb/" + id; }
    public static String proxy(String id) { return "/p/" + id; }

    public static String relative(String url, String resourceId) {
        if (url == null || url.startsWith("/") && !url.startsWith("//")) return url;
        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return url;
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawPath() == null) return url;
            var route = ROUTE.matcher(uri.getRawPath());
            if (route.find() && (resourceId == null || resourceId.equals(route.group(2)) || resourceId.equals(route.group(3)))) {
                return route.group(1) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                        + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            }
        } catch (IllegalArgumentException ignored) {
            // Leave user-supplied values outside the BootFS route format intact.
        }
        return url;
    }

    public static JSONObject normalizeDocument(JSONObject document) {
        String id = document.getString("uuid");
        for (String field : List.of("url", "postUrl", "publicUrl", "poster", "thumbUrl")) {
            if (document.get(field) instanceof String url) {
                boolean albumCover = ("poster".equals(field) || "thumbUrl".equals(field))
                        && document.getIntValue("albumAvailable") == 1;
                document.put(field, relative(url, albumCover ? null : id));
            }
        }
        if (document.get("resources") instanceof JSONArray pages) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i) instanceof String url) pages.set(i, relative(url, null));
            }
        }
        if (document.get("legacyImageHost") instanceof JSONObject legacy) normalizeDocument(legacy);
        return document;
    }
}
