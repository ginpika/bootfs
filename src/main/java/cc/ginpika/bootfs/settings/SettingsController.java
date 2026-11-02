package cc.ginpika.bootfs.settings;

import cc.ginpika.bootfs.dto.SsoSession;
import cc.ginpika.bootfs.kernel.cluster.ClusterCredentials;
import cc.ginpika.bootfs.kernel.metadata.MetadataStore;
import cc.ginpika.bootfs.sso.*;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Controller
public class SettingsController {
    private final NodeSettings settings;
    private final MetadataStore metadata;
    private final SsoConfig sso;
    private final ClusterCredentials credentials;
    public SettingsController(NodeSettings settings,MetadataStore metadata,SsoConfig sso,ClusterCredentials credentials) {
        this.settings=settings; this.metadata=metadata; this.sso=sso; this.credentials=credentials;
    }
    @GetMapping("/settings") public String page() { return "settings"; }
    @GetMapping("/api/settings") @ResponseBody public Map<String,Object> read() { return settings.view(); }
    private void browser(HttpServletRequest request) {
        if (!BrowserRequestPolicy.allows(request,true,settings.current().string("webEntrypoint"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请从当前节点设置页操作");
    }
    @PutMapping("/api/settings") @ResponseBody
    public Map<String,Object> save(@RequestBody JSONObject body,HttpServletRequest request) throws IOException {
        browser(request);
        if (!Set.of("revision","values","confirmAddressChange","confirmBucketChange").containsAll(body.keySet())
                || !(body.get("revision") instanceof Number) || body.getDoubleValue("revision") != body.getLongValue("revision")
                || body.containsKey("confirmAddressChange") && !(body.get("confirmAddressChange") instanceof Boolean)
                || body.containsKey("confirmBucketChange") && !(body.get("confirmBucketChange") instanceof Boolean) || !(body.get("values") instanceof Map<?,?>)) throw new IllegalArgumentException("设置请求格式无效");
        Map<String,Object> patch=new LinkedHashMap<>(body.getJSONObject("values"));
        if (!SettingsValues.FIELDS.keySet().containsAll(patch.keySet())) throw new IllegalArgumentException("包含未知设置项");
        Map<String,Object> merged=new LinkedHashMap<>(settings.current().map());
        merged.putAll((Map<String,Object>)settings.view().get("pending")); merged.putAll(patch);
        // Absent secrets retain the current value; an explicit empty string clears them only when S3 is disabled.
        SettingsValues next=new SettingsValues(merged);
        validateAuthentication(next,request);
        return settings.update(body.getLongValue("revision"),next,Boolean.TRUE.equals(body.getBoolean("confirmAddressChange")),
                Boolean.TRUE.equals(body.getBoolean("confirmBucketChange")),metadata);
    }
    private void validateAuthentication(SettingsValues next,HttpServletRequest request) {
        SettingsValues old=settings.current();
        boolean changed=List.of("ssoEnabled","ssoServerUrl","allowedRoles","localBypassEnabled").stream().anyMatch(k -> !old.map().get(k).equals(next.map().get(k)));
        SsoSession session=null;
        if (!old.string("ssoServerUrl").equals(next.string("ssoServerUrl")) && !next.string("ssoServerUrl").isEmpty())
            session=checkSso(next.string("ssoServerUrl"),request);
        if (!changed || !next.bool("ssoEnabled")) return;
        if (next.string("ssoServerUrl").isEmpty()) throw new IllegalArgumentException("开启登录认证前请填写并测试认证服务地址");
        if (session==null) session=checkSso(next.string("ssoServerUrl"),request);
        boolean authenticated=session!=null && session.isAuthenticated() && session.getUser()!=null && next.permitsRole(session.getUser().getRole());
        if (!authenticated && !(next.bool("localBypassEnabled") && LocalAccessPolicy.directAccess(request)))
            throw new IllegalArgumentException("当前会话无法通过新认证配置；请先在认证服务登录并允许当前角色，或从原始回环连接保留本机访问");
    }
    private SsoSession checkSso(String url,HttpServletRequest request) {
        SettingsValues.url(url,false);
        String token="";
        if (request.getCookies()!=null) for (Cookie cookie:request.getCookies()) if (sso.getCookieName().equals(cookie.getName())) token=cookie.getValue();
        try {
            // No automatic redirects: do not forward SSO cookies to a different host.
            var client=reactor.netty.http.client.HttpClient.create().followRedirect(false).responseTimeout(Duration.ofSeconds(4));
            String json=WebClient.builder().baseUrl(url.replaceAll("/+$",""))
                    .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(client)).build()
                    .get().uri("/api/sso/session").cookie(sso.getCookieName(),token).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
            JSONObject result=com.alibaba.fastjson2.JSON.parseObject(json);
            if (result==null || !(result.get("authenticated") instanceof Boolean)) throw new IllegalArgumentException();
            return result.toJavaObject(SsoSession.class);
        } catch (Exception failure) { throw new IllegalArgumentException("认证服务连接测试失败，未保存设置"); }
    }
    @PostMapping("/api/settings/sso/test") @ResponseBody
    public Map<String,Object> testSso(@RequestBody JSONObject body,HttpServletRequest request) {
        browser(request); if (!body.keySet().equals(Set.of("url")) || !(body.get("url") instanceof String)) throw new IllegalArgumentException("请填写认证服务地址");
        SsoSession session=checkSso(body.getString("url"),request);
        return Map.of("connected",true,"authenticated",session!=null && session.isAuthenticated());
    }
    @PostMapping("/api/settings/credentials/rotate") @ResponseBody
    public Map<String,Object> rotate(@RequestBody JSONObject body,HttpServletRequest request) throws IOException {
        browser(request);
        if (!body.keySet().equals(Set.of("confirm")) || !"ROTATE".equals(body.getString("confirm"))) throw new IllegalArgumentException("请确认轮换节点管理凭证");
        credentials.rotate(); return Map.of("rotated",true);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) @ResponseBody
    public ResponseEntity<?> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(IOException.class) @ResponseBody
    public ResponseEntity<?> storageFailure(IOException e) { return ResponseEntity.status(503).body(Map.of("error","无法持久化设置，原配置继续有效")); }
}
