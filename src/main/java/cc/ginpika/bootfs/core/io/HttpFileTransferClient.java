package cc.ginpika.bootfs.core.io;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.domain.result.SimpleTransferResult;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Slf4j
@NoArgsConstructor
public class HttpFileTransferClient {
    public JSONObject prepare(File file, String id, String name, String sha256, NodeObject node, String token, int timeoutMs) throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().disableRedirectHandling().build()) {
            HttpPut request = new HttpPut(node.getUrl().replaceAll("/+$", "") + "/t/prepare");
            if (token != null && !token.isBlank()) request.setHeader("X-BootFS-Cluster-Token", token);
            request.setConfig(org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(timeoutMs)
                    .setConnectionRequestTimeout(timeoutMs).setSocketTimeout(timeoutMs).build());
            request.setEntity(MultipartEntityBuilder.create().setCharset(StandardCharsets.UTF_8)
                    .addPart("file", new FileBody(file))
                    .addTextBody("originalFilename", name, ContentType.create("text/plain", Consts.UTF_8))
                    .addTextBody("originalFileId", id).addTextBody("sha256", sha256).build());
            java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            var abort = timer.schedule(request::abort, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            try { return JSON.parseObject(client.execute(request, new BasicResponseHandler())); }
            finally { abort.cancel(false); timer.shutdownNow(); }
        }
    }

    public void transfer(File file, String fileId, String originalFilename, NodeObject nodeObject) throws IOException {
        transfer(file, fileId, originalFilename, nodeObject, null);
    }

    public void transfer(File file, String fileId, String originalFilename, NodeObject nodeObject, String clusterToken) throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPut rq = new HttpPut(nodeObject.getUrl().replaceAll("/+$", "") + "/t");
            if (clusterToken != null && !clusterToken.isBlank()) rq.setHeader("X-BootFS-Cluster-Token", clusterToken);
            rq.setConfig(org.apache.http.client.config.RequestConfig.custom()
                    .setConnectTimeout(10000).setSocketTimeout(1800000).build());
            FileBody fileBody = new FileBody(file);
            HttpEntity multipartEntity = MultipartEntityBuilder.create()
                    .setCharset(StandardCharsets.UTF_8)
                    // 添加文件参数
                    .addPart("file", fileBody)
                    // 添加文本参数
                    .addPart("originalFilename", new StringBody(originalFilename, ContentType.create("text/plain", Consts.UTF_8)))
                    .addPart("originalFileId", new StringBody(fileId, ContentType.create("text/plain", Consts.UTF_8)))
                    .addPart("isCopy", new StringBody("1", ContentType.create("text/plain", Consts.UTF_8)))
                    // 可选：设置字符集
                    .build();
            rq.setEntity(multipartEntity);
            ResponseHandler<String> handler = new BasicResponseHandler();
            JSONObject response = JSON.parseObject(client.execute(rq, handler));
            log.info("Transfer response: {}", response);
            SimpleTransferResult result = JSON.parseObject(response.toJSONString(), SimpleTransferResult.class);
            if (!result.getSucceed()) {
                throw new IOException(result.getMessage());
            }
        }
    }
}
