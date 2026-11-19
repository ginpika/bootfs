package cc.ginpika.bootfs.metrics;

import org.apache.commons.lang3.StringUtils;

public enum RequestCategory {
    FILE("文件服务"),
    HLS("流媒体服务"),
    S3("S3 接口"),
    OTHER("静态资源和其他");

    private final String label;

    RequestCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static RequestCategory from(String uri) {
        if (StringUtils.isBlank(uri)) {
            return OTHER;
        }
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (matchesPath(path, "/tv/status")) {
            return OTHER;
        }
        if (matchesPath(path, "/hls") || matchesPath(path, "/tv") || path.equals("/virtual-file")) {
            return HLS;
        }
        if (matchesPath(path, "/s3")) {
            return S3;
        }
        if (matchesPath(path, "/f") || matchesPath(path, "/thumb") || matchesPath(path, "/p")
                || matchesPath(path, "/g") || matchesPath(path, "/file") || matchesPath(path, "/api/file")
                || matchesPath(path, "/api/folders") || matchesPath(path, "/api/tags")
                || matchesPath(path, "/api/album") || matchesPath(path, "/publicAccess")
                || path.equals("/comic") || path.equals("/queryPageOffset") || path.equals("/downloadFileBatch")
                || path.equals("/removeFileByUuid") || path.equals("/removeFileBatch")) {
            return FILE;
        }
        return OTHER;
    }

    private static boolean matchesPath(String path, String prefix) {
        return path.startsWith(prefix)
                && (path.length() == prefix.length() || path.charAt(prefix.length()) == '/');
    }
}
