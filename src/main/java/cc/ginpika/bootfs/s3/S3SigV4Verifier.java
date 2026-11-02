package cc.ginpika.bootfs.s3;

import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AWS Signature Version 4 验证。仅校验 Authorization 头形式签名,信任 x-amz-content-sha256,
 * 此处验证签名中的 payload hash；S3StorageService/FileService 在流式落盘阶段校验实际 body。
 */
@Component
@RequiredArgsConstructor
public class S3SigV4Verifier {

    private static final long MAX_CLOCK_SKEW_MS = 15 * 60 * 1000L;
    private static final String ALGO = "AWS4-HMAC-SHA256";

    private final S3Config s3Config;

    /**
     * @return null 表示通过,否则返回对应错误码。
     */
    public boolean enabled() { return s3Config.isEnabled(); }
    public S3ErrorCode verify(HttpServletRequest req) {
        var credentials = s3Config.credentials();
        String accessKey = credentials[0], secretKey = credentials[1];
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith(ALGO + " ")) {
            return S3ErrorCode.ACCESS_DENIED;
        }
        ParsedAuth p = parseAuth(auth.substring(ALGO.length() + 1));
        if (p == null) {
            return S3ErrorCode.ACCESS_DENIED;
        }
        if (!p.signedHeaders.contains("host") || !p.signedHeaders.contains("x-amz-date")
                || !p.signedHeaders.equals(p.signedHeaders.stream().distinct().sorted().toList())
                || p.signedHeaders.stream().anyMatch(h -> req.getHeader(h) == null)
                || req.getHeader("host") == null) return S3ErrorCode.SIGNATURE_DOES_NOT_MATCH;
        if (req.getHeader("x-amz-copy-source") != null && !p.signedHeaders.contains("x-amz-copy-source")) {
            return S3ErrorCode.SIGNATURE_DOES_NOT_MATCH;
        }
        if (!"s3".equals(p.service) || !"aws4_request".equals(p.trailer)) {
            return S3ErrorCode.SIGNATURE_DOES_NOT_MATCH;
        }
        if (accessKey == null || !accessKey.equals(p.accessKey)) {
            return S3ErrorCode.ACCESS_DENIED;
        }

        String amzDate = req.getHeader("x-amz-date");
        if (amzDate == null) {
            amzDate = req.getHeader("X-Amz-Date");
        }
        if (amzDate == null || !amzDate.startsWith(p.date)) {
            return S3ErrorCode.SIGNATURE_DOES_NOT_MATCH;
        }
        Long reqTime = parseAmzDate(amzDate);
        if (reqTime == null) {
            return S3ErrorCode.ACCESS_DENIED;
        }
        long skew = Math.abs(System.currentTimeMillis() - reqTime);
        if (skew > MAX_CLOCK_SKEW_MS) {
            return S3ErrorCode.REQUEST_TIME_TOO_SKEWED;
        }

        String canonicalRequest = buildCanonicalRequest(req, p.signedHeaders);
        String stringToSign = ALGO + "\n"
                + amzDate + "\n"
                + p.date + "/" + p.region + "/s3/aws4_request\n"
                + S3CryptoUtils.sha256Hex(canonicalRequest);

        byte[] kDate = S3CryptoUtils.hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), p.date);
        byte[] kRegion = S3CryptoUtils.hmacSha256(kDate, p.region);
        byte[] kService = S3CryptoUtils.hmacSha256(kRegion, "s3");
        byte[] kSigning = S3CryptoUtils.hmacSha256(kService, "aws4_request");
        String computed = S3CryptoUtils.hmacSha256Hex(kSigning, stringToSign);

        if (!MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII), p.signature.getBytes(StandardCharsets.US_ASCII))) {
            return S3ErrorCode.SIGNATURE_DOES_NOT_MATCH;
        }
        return null;
    }

    private String buildCanonicalRequest(HttpServletRequest req, List<String> signedHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getMethod()).append('\n');
        sb.append(canonicalUri(req)).append('\n');
        sb.append(canonicalQueryString(req.getQueryString())).append('\n');
        sb.append(canonicalHeaders(req, signedHeaders)).append('\n');
        sb.append(String.join(";", signedHeaders)).append('\n');
        sb.append(hashedPayload(req));
        return sb.toString();
    }

    /**
     * CanonicalURI:使用原始编码 URI(getRequestURI),不解码。MVP 假设无 contextPath 改写。
     */
    private String canonicalUri(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri == null ? "/" : uri;
    }

    private String canonicalQueryString(String query) {
        if (query == null || query.isEmpty()) return "";
        String[] pairs = query.split("&");
        List<String[]> parameters = new ArrayList<>();
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            String k, v;
            if (idx < 0) {
                k = pair;
                v = "";
            } else {
                k = pair.substring(0, idx);
                v = pair.substring(idx + 1);
            }
            parameters.add(new String[]{k, v});
        }
        parameters.sort(java.util.Comparator.<String[], String>comparing(pair -> pair[0]).thenComparing(pair -> pair[1]));
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String[] e : parameters) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e[0]).append('=').append(e[1]);
        }
        return sb.toString();
    }

    private String canonicalHeaders(HttpServletRequest req, List<String> signedHeaders) {
        List<String> sorted = new ArrayList<>(signedHeaders);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String h : sorted) {
            String v = req.getHeader(h);
            if (v == null) v = "";
            sb.append(h).append(':').append(v.trim().replaceAll("\\s+", " ")).append('\n');
        }
        return sb.toString();
    }

    private String hashedPayload(HttpServletRequest req) {
        String v = req.getHeader("x-amz-content-sha256");
        if (v == null) v = req.getHeader("X-Amz-Content-Sha256");
        if (v == null) return "UNSIGNED-PAYLOAD";
        return v;
    }

    private Long parseAmzDate(String amzDate) {
        try {
            if (!amzDate.matches("[0-9]{8}T[0-9]{6}Z")) return null;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
            sdf.setLenient(false);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(amzDate).getTime();
        } catch (Exception e) {
            return null;
        }
    }

    private ParsedAuth parseAuth(String body) {
        // Credential=AK/date/region/service/aws4_request, SignedHeaders=h1;h2, Signature=hex
        String credential = extract(body, "Credential=");
        String signedHeadersRaw = extract(body, "SignedHeaders=");
        String signature = extract(body, "Signature=");
        if (credential == null || signedHeadersRaw == null || signature == null) {
            return null;
        }
        String[] parts = credential.split("/");
        if (parts.length != 5 || !parts[1].matches("[0-9]{8}") || !signature.matches("[a-f0-9]{64}")) return null;
        ParsedAuth p = new ParsedAuth();
        p.accessKey = parts[0];
        p.date = parts[1];
        p.region = parts[2];
        p.service = parts[3];
        p.trailer = parts[4];
        p.signature = signature.trim();
        String[] hs = signedHeadersRaw.split(";");
        List<String> list = new ArrayList<>();
        for (String h : hs) {
            String t = h.trim().toLowerCase();
            if (!t.isEmpty()) list.add(t);
        }
        p.signedHeaders = list;
        return p;
    }

    private String extract(String body, String key) {
        int idx = body.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = body.indexOf(',', start);
        if (end < 0) end = body.length();
        return body.substring(start, end).trim();
    }

    private static class ParsedAuth {
        String accessKey;
        String date;
        String region;
        String service;
        String trailer;
        String signature;
        List<String> signedHeaders;
    }
}
