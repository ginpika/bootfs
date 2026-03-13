package cc.ginpika.bootfs.service;

import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.springframework.http.HttpHeaders;
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

    private static final Set<String> approvedImgExt = new HashSet<>(){{
        this.add(".jpeg");this.add(".png");this.add(".jpg");this.add(".gif");
    }};

    private static final Set<String> approvedVideoExt = new HashSet<>(){{
        this.add(".mp4");
    }};

    private static final Set<String> approvedAudioExt = new HashSet<>(){{
        this.add(".mp3");
    }};

    public void reverseProxyFile(String uuid, HttpServletResponse response, HttpServletRequest request) {
        String url = getTrueNodeUrl(uuid);
        String meta = getMetaJson(uuid);
        if (meta == null) {
            log.info("");
            return;
        }
        JSONObject metaJson = JSONObject.parseObject(meta);
        if (metaJson.size() > 2048000) {
            log.info("{} size > 2048000, no reverse proxy", uuid);
            return;
        }
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
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet rq = new HttpGet(url);
            Enumeration<String> requestHeaders = request.getHeaderNames();
            List<Header> headerList = new ArrayList<>();
            while (requestHeaders.hasMoreElements()) {
                String header = requestHeaders.nextElement();
                String val = request.getHeader(header);
                headerList.add(new BasicHeader(header, val));
            }
            headerList.add(new BasicHeader("Is-Proxy", "1"));
            headerList.add(new BasicHeader("Accept-Type", acceptType));
            rq.setHeaders(headerList.toArray(new Header[0]));

            response.setContentType(acceptType);
            CloseableHttpResponse rqRes = client.execute(rq);
            Arrays.stream(rqRes.getAllHeaders()).filter(header -> header.getName().equals(HttpHeaders.CONTENT_LENGTH)).findFirst().ifPresent(r -> {
//                response.setContentLengthLong(metaJson.getLong("size"));
                long metaJsonSize = metaJson.getLong("size");
                response.setContentLengthLong(Long.parseLong(r.getValue()));
                if (Long.parseLong(r.getValue()) != metaJsonSize) {
                    log.info("WARN 发现文件长度不匹配");
                }
            });
            InputStream is = rqRes.getEntity().getContent();
            OutputStream os = response.getOutputStream();
            is.transferTo(os);
            is.close();
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void proxyMedia(HttpServletResponse response, HttpServletRequest request, JSONObject metaJson, String url) {
        String acceptType = (approvedAudioExt.contains(metaJson.getString("ext")) ? "audio/" : "video/")
                + metaJson.getString("ext").substring(1);
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet rq = new HttpGet(url);
            Enumeration<String> requestHeaders = request.getHeaderNames();
            List<Header> headerList = new ArrayList<>();
            while (requestHeaders.hasMoreElements()) {
                String header = requestHeaders.nextElement();
                String val = request.getHeader(header);
                headerList.add(new BasicHeader(header, val));
            }
            headerList.add(new BasicHeader("Is-Range-Request", "1"));
            headerList.add(new BasicHeader("Accept-Type", acceptType));
            rq.setHeaders(headerList.toArray(new Header[0]));
            CloseableHttpResponse rqRes = client.execute(rq);
            Arrays.stream(rqRes.getAllHeaders()).forEach(header -> {
                response.setHeader(header.getName(), header.getValue());
            });
            response.setStatus(rqRes.getStatusLine().getStatusCode());
            response.setContentType(acceptType);
            InputStream is = rqRes.getEntity().getContent();
            OutputStream os = response.getOutputStream();
            is.transferTo(os);
            is.close();
        } catch (ClientAbortException ignore) {} catch (IOException e) {
            log.error("ReverseProxy failure", e);
        }
    }
    private String getTrueNodeUrl(String uuid) {
        return etcdService.getOne("/files/" + uuid);
    }

    private String getMetaJson(String uuid) {
        return etcdService.getOne("/files/" + uuid + "/meta");
    }
}
