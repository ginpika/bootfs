package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.service.ClusterProxyService;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
@RequiredArgsConstructor
class ResourceRequestForwarder {
    private final LocalFileInventory inventory;
    private final ClusterProxyService proxy;

    /** Returns null when the caller should handle the request locally. */
    ResponseEntity<byte[]> forwardIfRemote(String uuid, HttpServletRequest request, Object body) {
        if (inventory.query(uuid) != null || request == null) return null;
        return forwardMissing(uuid, request, body);
    }

    ResponseEntity<byte[]> forwardMissing(String uuid, HttpServletRequest request, Object body) {
        if ("1".equals(request.getHeader("X-BootFS-Forwarded"))) return ResponseEntity.notFound().build();
        String endpoint = proxy.getRemoteNodeUrl(uuid);
        if (endpoint == null) return ResponseEntity.notFound().build();
        return proxy.proxyRequest(endpoint, request, body == null ? null : JSONObject.toJSONString(body));
    }
}
