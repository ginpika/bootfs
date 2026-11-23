package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import cc.ginpika.bootfs.sso.SsoUser;
import cc.ginpika.bootfs.sso.SsoConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Slf4j
@Controller
@SuppressWarnings("all")
@RequiredArgsConstructor
public class WebUIController {
    private static final String VIDEO_FRAME_ANCESTORS = "frame-ancestors 'self'"
            + " https://*.ginpika.cc:* http://*.ginpika.cc:* https://localhost:* http://localhost:*";
    private final LocalFileInventory context;
    private final SsoConfig ssoConfig;

    @ModelAttribute("ssoLocalAccess")
    public boolean localAccess(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute("ssoLocalAccess"));
    }

    // Web UI - Old version (keep for compatibility)
    @GetMapping("/index-old")
    public ModelAndView indexOld(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("index");
        SsoUser ssoUser = (SsoUser) request.getAttribute("ssoUser");
        if (ssoUser != null) {
            mv.getModel().put("ssoUser", ssoUser);
        }
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }
    
    // Web UI - New Modern Dashboard
    @GetMapping("/")
    public ModelAndView index(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("admin-dashboard");
        SsoUser ssoUser = (SsoUser) request.getAttribute("ssoUser");
        if (ssoUser != null) {
            mv.getModel().put("ssoUser", ssoUser);
        }
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        mv.getModel().put("ssoEnabled", ssoConfig.isEnabled()
                && !Boolean.TRUE.equals(request.getAttribute("ssoLocalAccess")));
        return mv;
    }

    @GetMapping("/old-admin")
    public ModelAndView oldAdmin(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("index");
        SsoUser ssoUser = (SsoUser) request.getAttribute("ssoUser");
        if (ssoUser != null) {
            mv.getModel().put("ssoUser", ssoUser);
        }
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }

    // Embed Video.js hls player
    @GetMapping("/video-player")
    public ModelAndView hlsPreview(@RequestParam(value = "uuid", required = false) String uuid,
                                   HttpServletResponse response) {
        // Browsers that enforce frame-ancestors use it in place of the default X-Frame-Options.
        response.addHeader("Content-Security-Policy", VIDEO_FRAME_ANCESTORS);
        ModelAndView mv = new ModelAndView("video-player");
        if (StringUtils.isNotBlank(uuid)) {
            FileObject fileObject = context.cached(uuid);
            if (fileObject != null) {
                mv.addObject("uuid", uuid);
                mv.addObject("hls", StringUtils.isNotBlank(fileObject.getHlsAvailable()));
            }
        }
        return mv;
    }

    // Embed image list viewer
    @GetMapping("/album/**")
    public ModelAndView imagePreview() {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("album");
        return mv;
    }

    // Embed image-hosting background random read template
    @GetMapping("/gallery")
    public ModelAndView randomPicture(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        ModelAndView mv = new ModelAndView();
        mv.setViewName("gallery");
        return mv;
    }

    // System dashboard
    @GetMapping("/dashboard")
    public ModelAndView dashboard(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("dashboard");
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }

    // Etcd metadata management
    @GetMapping("/etcd")
    public ModelAndView etcdManage(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("etcd-manage");
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }

    // MeiliSearch metadata management
    @GetMapping("/meilisearch")
    public ModelAndView meilisearchManage(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("meilisearch-manage");
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }
}
