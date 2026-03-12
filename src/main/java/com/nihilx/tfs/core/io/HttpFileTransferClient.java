package com.nihilx.tfs.core.io;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nihilx.tfs.domain.dto.NodeObject;
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
    public void transfer(File file, String fileId, String originalFilename, NodeObject nodeObject) throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpPut rq = new HttpPut(nodeObject.getUrl() + "/t");
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
        }
    }
}
