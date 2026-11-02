package cc.ginpika.bootfs.s3;

import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 手拼 S3 XML 响应。无 XML 库依赖,用 StringBuilder + 转义。
 */
@Component
public class S3XmlWriter {

    private static final String DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    public String copyObjectResult(S3ObjectMeta object) {
        StringBuilder sb = new StringBuilder(DECL);
        sb.append("<CopyObjectResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        tag(sb, "LastModified", formatIso(object.getLastModified()));
        tag(sb, "ETag", "\"" + object.getEtag() + "\"");
        return sb.append("</CopyObjectResult>").toString();
    }

    public String bucketLocation(String region) {
        // AWS represents us-east-1 with an empty LocationConstraint for compatibility.
        return DECL + "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + esc("us-east-1".equals(region) ? "" : region) + "</LocationConstraint>";
    }

    public String listBuckets(String bucket) {
        StringBuilder sb = new StringBuilder(DECL);
        sb.append("<ListAllMyBucketsResult>");
        sb.append("<Owner><ID>bootfs</ID><DisplayName>bootfs</DisplayName></Owner>");
        sb.append("<Buckets>");
        sb.append("<Bucket><Name>").append(esc(bucket)).append("</Name>")
          .append("<CreationDate>").append(formatIso(0L)).append("</CreationDate></Bucket>");
        sb.append("</Buckets>");
        sb.append("</ListAllMyBucketsResult>");
        return sb.toString();
    }

    public String listBucketResult(S3ListPage page, String bucket, String prefix,
                                   String delimiter, int maxKeys, String continuationToken) {
        return listBucketResult(page, bucket, prefix, delimiter, maxKeys, continuationToken, true);
    }

    public String listBucketResult(S3ListPage page, String bucket, String prefix,
                                   String delimiter, int maxKeys, String cursor, boolean v2) {
        StringBuilder sb = new StringBuilder(DECL);
        sb.append("<ListBucketResult>");
        tag(sb, "Name", bucket);
        tag(sb, "Prefix", prefix == null ? "" : prefix);
        if (delimiter != null) {
            tag(sb, "Delimiter", delimiter);
        }
        if (v2) tag(sb, "KeyCount", String.valueOf(countKeys(page)));
        else tag(sb, "Marker", cursor == null ? "" : cursor);
        tag(sb, "MaxKeys", String.valueOf(maxKeys));
        tag(sb, "IsTruncated", String.valueOf(page.isTruncated()));
        if (v2 && cursor != null) {
            tag(sb, "ContinuationToken", cursor);
        }
        if (v2 && page.isTruncated() && page.getNextContinuationToken() != null) {
            tag(sb, "NextContinuationToken", page.getNextContinuationToken());
        }
        if (!v2 && page.isTruncated() && page.getNextMarker() != null) tag(sb, "NextMarker", page.getNextMarker());
        List<S3ObjectMeta> contents = page.getContents();
        if (contents != null) {
            for (S3ObjectMeta meta : contents) {
                sb.append("<Contents>");
                tag(sb, "Key", meta.getKey());
                tag(sb, "LastModified", formatIso(meta.getLastModified()));
                tag(sb, "ETag", "\"" + (meta.getEtag() == null ? "" : meta.getEtag()) + "\"");
                tag(sb, "Size", String.valueOf(meta.getSize()));
                tag(sb, "StorageClass", "STANDARD");
                sb.append("</Contents>");
            }
        }
        List<String> commonPrefixes = page.getCommonPrefixes();
        if (commonPrefixes != null) {
            for (String cp : commonPrefixes) {
                sb.append("<CommonPrefixes>");
                tag(sb, "Prefix", cp);
                sb.append("</CommonPrefixes>");
            }
        }
        sb.append("</ListBucketResult>");
        return sb.toString();
    }

    public String error(S3ErrorCode code, String message, String resource, String requestId) {
        StringBuilder sb = new StringBuilder(DECL);
        sb.append("<Error>");
        tag(sb, "Code", code.getXmlCode());
        tag(sb, "Message", message == null ? code.getDefaultMessage() : message);
        if (resource != null) {
            tag(sb, "Resource", resource);
        }
        tag(sb, "RequestId", requestId == null ? "" : requestId);
        sb.append("</Error>");
        return sb.toString();
    }

    private int countKeys(S3ListPage page) {
        int n = page.getContents() == null ? 0 : page.getContents().size();
        n += page.getCommonPrefixes() == null ? 0 : page.getCommonPrefixes().size();
        return n;
    }

    private void tag(StringBuilder sb, String name, String value) {
        sb.append('<').append(name).append('>').append(esc(value)).append("</").append(name).append('>');
    }

    private String formatIso(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(millis));
    }

    private String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
