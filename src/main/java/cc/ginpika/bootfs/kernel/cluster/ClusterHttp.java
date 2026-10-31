package cc.ginpika.bootfs.kernel.cluster;

import com.alibaba.fastjson2.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class ClusterHttp {
    public static final class HttpFailure extends IOException {
        public final int status;
        HttpFailure(int status, String detail) { super(detail); this.status=status; }
    }
    private ClusterHttp() {}
    static JSONObject call(String endpoint, String path, JSONObject body, String token) throws IOException {
        return call(endpoint, path, body, token, 30000);
    }
    public static JSONObject call(String endpoint, String path, JSONObject body, String token, int timeoutMs) throws IOException {
        return call(endpoint,path,body,token,timeoutMs,null);
    }
    public static JSONObject call(String endpoint,String path,JSONObject body,String token,int timeoutMs,ClusterCredentials identity) throws IOException {
        URI uri = URI.create(endpoint.replaceAll("/+$", "") + "/api/cluster/" + path);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("Invalid cluster HTTP endpoint");
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(Math.min(10000,timeoutMs)); connection.setReadTimeout(timeoutMs);
        if (token != null && !token.isBlank()) connection.setRequestProperty("X-BootFS-Cluster-Token", token);
        byte[] bytes=body==null ? new byte[0] : body.toJSONString().getBytes(StandardCharsets.UTF_8);
        if (identity!=null) {
            var headers=signedHeaders(identity,body==null ? "GET" : "POST",uri,token,digest(bytes));
            headers.forEach(connection::setRequestProperty);
        }
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200 && status != 202) {
                String detail = "";
                InputStream error = connection.getErrorStream();
                if (error != null) try (error) { detail = new String(error.readNBytes(2048), StandardCharsets.UTF_8); }
                throw new HttpFailure(status, "Cluster request " + path + " returned HTTP " + status + ": " + detail);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] response = input.readNBytes(64 * 1024 * 1024 + 1);
                if (response.length > 64 * 1024 * 1024) throw new IOException("Cluster response exceeds the first-generation transfer limit");
                return JSON.parseObject(response);
            }
        } finally { connection.disconnect(); }
    }
    public static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String signingInput(String method,String target,String token,String time,String nonce,String digest) {
        return method+"\n"+target+"\n"+token+"\n"+time+"\n"+nonce+"\n"+digest;
    }
    public static java.util.Map<String,String> signedHeaders(ClusterCredentials identity,String method,URI uri,String token,String digest) throws IOException {
        String time=Long.toString(System.currentTimeMillis()),nonce=java.util.UUID.randomUUID().toString();
        String target=uri.getRawPath()+(uri.getRawQuery()==null ? "" : "?"+uri.getRawQuery());
        return java.util.Map.of("X-BootFS-Time",time,"X-BootFS-Nonce",nonce,"X-BootFS-Digest",digest,
                "X-BootFS-Signature",identity.sign(signingInput(method,target,token,time,nonce,digest)));
    }
}
