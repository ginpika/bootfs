package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.service.meilisearch.MeiliSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 集群跨节点代理：基于 FullTextDocument.contextUuid 判断文件归属节点，
// 将管理端请求转发到持有文件的远程节点，避免本地查询失败导致 404。
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterProxyService {
    private final MeiliSearchService meiliSearchService;
    private final EtcdService etcdService;
    private final Context context;

    // 转发请求时跳过的 hop-by-hop / 上下文相关 header
    private static final Set<String> SKIP_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "connection", "keep-alive", "transfer-encoding",
            "te", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization"
    ));

    // 回传响应时跳过的 header（HttpClient 已处理 Content-Encoding/Content-Length）
    private static final Set<String> SKIP_RESPONSE_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
            "proxy-authenticate", "proxy-authorization", "content-length", "content-encoding"
    ));

    /**
     * 通过 FullTextDocument.contextUuid 判断文件是否属于其他节点。
     * 返回远程节点的 web entrypoint URL；若文件在本地或无法解析则返回 null。
     */
    @SuppressWarnings("unchecked")
    public String getRemoteNodeUrl(String uuid) {
        try {
            Map<String, Object> docResult = meiliSearchService.getDocument("full-text", uuid);
            if (docResult == null || !Boolean.TRUE.equals(docResult.get("succeed")) || docResult.get("data") == null) {
                return null;
            }
            Object data = docResult.get("data");
            String contextUuid;
            if (data instanceof JSONObject) {
                contextUuid = ((JSONObject) data).getString("contextUuid");
            } else if (data instanceof Map) {
                contextUuid = (String) ((Map<String, Object>) data).get("contextUuid");
            } else {
                return null;
            }
            if (StringUtils.isBlank(contextUuid) || contextUuid.equals(context.uuid)) {
                return null;
            }
            String nodeUrl = etcdService.getOne("/cluster/node/" + contextUuid);
            return StringUtils.isNotBlank(nodeUrl) ? nodeUrl : null;
        } catch (Exception e) {
            log.warn("解析文件 contextUuid 失败, uuid={}: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * 代理 HTTP 请求到远程节点，将响应（状态码、header、body）原样返回。
     * 适用于 /api/file/** 等管理端 JSON/二进制 API 的跨节点转发。
     *
     * @param remoteNodeUrl 远程节点 web entrypoint（不含路径）
     * @param request       原始请求（提供 method、URI、query string、headers）
     * @param bodyJson      请求体 JSON；GET/DELETE 传 null
     */
    public ResponseEntity<byte[]> proxyRequest(String remoteNodeUrl,
                                               HttpServletRequest request,
                                               String bodyJson) {
        String targetUrl = remoteNodeUrl + request.getRequestURI();
        if (request.getQueryString() != null) {
            targetUrl += "?" + request.getQueryString();
        }

        RequestBuilder requestBuilder = RequestBuilder.create(request.getMethod())
                .setUri(targetUrl);

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (!SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
                requestBuilder.addHeader(name, request.getHeader(name));
            }
        }

        if (bodyJson != null && !bodyJson.isEmpty()) {
            StringEntity entity = new StringEntity(bodyJson, StandardCharsets.UTF_8);
            entity.setContentType("application/json");
            requestBuilder.setEntity(entity);
        }

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            CloseableHttpResponse response = client.execute(requestBuilder.build());
            HttpEntity entity = response.getEntity();
            byte[] responseBody = entity != null ? EntityUtils.toByteArray(entity) : new byte[0];

            HttpHeaders responseHeaders = new HttpHeaders();
            for (Header header : response.getAllHeaders()) {
                if (!SKIP_RESPONSE_HEADERS.contains(header.getName().toLowerCase())) {
                    responseHeaders.add(header.getName(), header.getValue());
                }
            }

            return ResponseEntity.status(response.getStatusLine().getStatusCode())
                    .headers(responseHeaders)
                    .body(responseBody);
        } catch (Exception e) {
            log.error("代理请求到远程节点失败: {}", targetUrl, e);
            return ResponseEntity.status(502).build();
        }
    }
}
