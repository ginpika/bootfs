package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

@Slf4j
@Service
public class ReverseProxyService {
    @Resource
    EtcdService etcdService;
    @Resource FederationService federation;

    private static final Set<String> approvedImgExt = new HashSet<>(){{
        this.add(".jpeg");this.add(".png");this.add(".jpg");this.add(".gif");
    }};

    private static final Set<String> approvedVideoExt = new HashSet<>(){{
        this.add(".mp4");
    }};

    private static final Set<String> approvedAudioExt = new HashSet<>(){{
        this.add(".mp3");
    }};

    // 转发到上游时仅允许的请求头白名单：其余（Host、Cookie、Accept-Encoding、Content-Length 等）一律不转发
    private static final Set<String> ALLOWED_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "range", "if-range", "if-modified-since", "if-none-match"
    ));

    // 回传给客户端时跳过的响应头（hop-by-hop，以及 HttpClient 已处理过的 Content-Encoding/Content-Length）
    private static final Set<String> SKIP_RESPONSE_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
            "proxy-authenticate", "proxy-authorization", "content-length", "content-encoding"
    ));

    // HttpClientBuilder 默认开启 ContentCompressionInterceptor，会自己加 Accept-Encoding: gzip,deflate 并自动解压
    private void applyProxyHeaders(HttpGet rq, HttpServletRequest request, String acceptType, boolean isRange) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (ALLOWED_REQUEST_HEADERS.contains(name.toLowerCase())) {
                rq.addHeader(name, request.getHeader(name));
            }
        }
        rq.addHeader(isRange ? "Is-Range-Request" : "Is-Proxy", "1");
        rq.addHeader("Accept-Type", acceptType);
    }

    public void reverseProxyFile(String uuid, HttpServletResponse response, HttpServletRequest request) {
        String url = getTrueNodeUrl(uuid);
        String meta = getMetaJson(uuid);
        if (meta==null || url==null) {
            JSONObject remote=federation.locate(uuid);
            if (remote!=null) { meta=remote.getString("metadata"); url=remote.getString("url")+"/f/"+uuid; }
        }
        if (meta == null || url == null) {
            response.setStatus(404);
            log.info("uuid metaInfo 不存在", uuid);
            return;
        }
        JSONObject metaJson = JSONObject.parseObject(meta);
        String extension = metaJson.getString("ext");
        if (extension != null && Set.of(".yaml", ".yml").contains(extension.toLowerCase(Locale.ROOT))) {
            proxyYaml(response, request, url);
            return;
        }
        // if (metaJson.getLong("size") > 2048000) {
        //     log.info("{} size > 2048000, no reverse proxy", uuid);
        //     return;
        // }
        // 图片
        if (approvedImgExt.contains(metaJson.getString("ext"))) {
            proxyImage(response, request, metaJson, url);
            return;
        }
        if (approvedVideoExt.contains(metaJson.getString("ext"))) {
            proxyMedia(response, request, metaJson, url);
            return;
        }
        if (approvedAudioExt.contains(metaJson.getString("ext"))) {
            proxyMedia(response, request, metaJson, url);
        }
    }

    public void proxyImage(HttpServletResponse response, HttpServletRequest request, JSONObject metaJson, String url) {
        String acceptType = "image/" + metaJson.getString("ext").substring(1);
        try (CloseableHttpClient client = HttpClientBuilder.create().disableRedirectHandling().setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(2000).setSocketTimeout(5000).build()).build()) {
            HttpGet rq = new HttpGet(url);
            applyProxyHeaders(rq, request, acceptType, false);

            CloseableHttpResponse rqRes = client.execute(rq);
            response.setStatus(rqRes.getStatusLine().getStatusCode());
            response.setContentType(acceptType);

            HttpEntity entity = rqRes.getEntity();
            if (entity != null) {
                try (InputStream is = entity.getContent();
                     OutputStream os = response.getOutputStream()) {
                    is.transferTo(os);
                }
            }
        } catch (IOException e) {
            log.error("ReverseProxy image failure: {}", url, e);
            throw new RuntimeException(e);
        }
    }

    private void proxyYaml(HttpServletResponse response, HttpServletRequest request, String url) {
        try (CloseableHttpClient client = HttpClientBuilder.create().disableRedirectHandling()
                .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
                        .setConnectTimeout(2000).setSocketTimeout(5000).build()).build()) {
            HttpGet upstream = new HttpGet(url);
            // Range and conditional headers describe the original bytes, never the converted subscription.
            applyProxyHeaders(upstream, request, "application/yaml;charset=UTF-8", false);
            try (CloseableHttpResponse result = client.execute(upstream)) {
                response.setStatus(result.getStatusLine().getStatusCode());
                response.setContentType("application/yaml;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                for (String name : List.of("ETag", "Last-Modified", "Content-Range", "Accept-Ranges")) {
                    Header header = result.getFirstHeader(name);
                    if (header != null) response.setHeader(name, header.getValue());
                }
                HttpEntity entity = result.getEntity();
                if (entity != null) {
                    try (InputStream input = entity.getContent()) { input.transferTo(response.getOutputStream()); }
                }
            }
        } catch (ClientAbortException ignored) {
        } catch (IOException failure) {
            log.warn("YAML reverse proxy failed for {}", url);
            if (!response.isCommitted()) response.setStatus(502);
        }
    }

    public void proxyMedia(HttpServletResponse response, HttpServletRequest request, JSONObject metaJson, String url) {
        String acceptType = (approvedAudioExt.contains(metaJson.getString("ext")) ? "audio/" : "video/")
                + metaJson.getString("ext").substring(1);
        try (CloseableHttpClient client = HttpClientBuilder.create().disableRedirectHandling().setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(2000).setSocketTimeout(5000).build()).build()) {
            HttpGet rq = new HttpGet(url);
            applyProxyHeaders(rq, request, acceptType, true);

            CloseableHttpResponse rqRes = client.execute(rq);
            response.setStatus(rqRes.getStatusLine().getStatusCode());
            response.setContentType(acceptType);

            Arrays.stream(rqRes.getAllHeaders())
                    .filter(h -> !SKIP_RESPONSE_HEADERS.contains(h.getName().toLowerCase()))
                    .forEach(h -> response.setHeader(h.getName(), h.getValue()));

            HttpEntity entity = rqRes.getEntity();
            if (entity != null) {
                try (InputStream is = entity.getContent();
                     OutputStream os = response.getOutputStream()) {
                    is.transferTo(os);
                }
            }
        } catch (ClientAbortException ignore) {
        } catch (IOException e) {
            log.error("ReverseProxy failure: {}", url, e);
        }
    }
    private String getTrueNodeUrl(String uuid) {
        return etcdService.getOne("/files/" + uuid);
    }

    private String getMetaJson(String uuid) {
        return etcdService.getOne("/files/" + uuid + "/meta");
    }
}
