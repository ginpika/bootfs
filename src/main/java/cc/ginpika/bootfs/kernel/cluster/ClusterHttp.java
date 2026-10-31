package cc.ginpika.bootfs.kernel.cluster;

import com.alibaba.fastjson2.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class ClusterHttp {
    private ClusterHttp() {}
    static JSONObject call(String endpoint, String path, JSONObject body, String token) throws IOException {
        return call(endpoint, path, body, token, 30000);
    }
    static JSONObject call(String endpoint, String path, JSONObject body, String token, int timeoutMs) throws IOException {
        URI uri = URI.create(endpoint.replaceAll("/+$", "") + "/api/cluster/" + path);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("Invalid cluster HTTP endpoint");
        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(Math.min(10000,timeoutMs)); connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("X-BootFS-Cluster-Token", token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200 && status != 202) {
                String detail = "";
                InputStream error = connection.getErrorStream();
                if (error != null) try (error) { detail = new String(error.readNBytes(2048), StandardCharsets.UTF_8); }
                throw new IOException("Cluster request " + path + " returned HTTP " + status + ": " + detail);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] response = input.readNBytes(64 * 1024 * 1024 + 1);
                if (response.length > 64 * 1024 * 1024) throw new IOException("Cluster response exceeds the first-generation transfer limit");
                return JSON.parseObject(response);
            }
        } finally { connection.disconnect(); }
    }
}
