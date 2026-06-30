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
        // HLS 与 TV 直播统归流媒体服务
        if (path.startsWith("/hls/") || path.startsWith("/tv/") || path.equals("/tv")) {
            return HLS;
        }
        if (path.startsWith("/s3/")) {
            return S3;
        }
        if (path.startsWith("/f/") || path.startsWith("/thumb/") || path.startsWith("/p/")
                || path.startsWith("/g/")) {
            return FILE;
        }
        return OTHER;
    }
}
