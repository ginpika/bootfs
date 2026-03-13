package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.config.MeiliSearchConfig;
import cc.ginpika.bootfs.config.SsoConfig;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.dto.SsoUser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@Slf4j
@Controller
@CrossOrigin
@SuppressWarnings("all")
public class WebUIController {
    @Resource
    Context context;
    @Autowired
    MeiliSearchConfig meiliSearchConfig;
    @Autowired
    TfsConfig tfsConfig;
    @Autowired
    SsoConfig ssoConfig;

    // Web UI - Old version (keep for compatibility)
    @GetMapping("/index-old")
    public ModelAndView indexOld(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("index");
        if (StringUtils.isNotBlank(meiliSearchConfig.getWebUi())) {
            mv.getModel().put("meilisearchWebUi", meiliSearchConfig.getWebUi());
        }
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
        if (StringUtils.isNotBlank(meiliSearchConfig.getWebUi())) {
            mv.getModel().put("meilisearchWebUi", meiliSearchConfig.getWebUi());
        }
        if (StringUtils.isNotBlank(meiliSearchConfig.getUrl())) {
            mv.getModel().put("meilisearchUrl", meiliSearchConfig.getUrl());
        }
        if (StringUtils.isNotBlank(meiliSearchConfig.getMasterKey())) {
            mv.getModel().put("meilisearchToken", meiliSearchConfig.getMasterKey());
        }
        SsoUser ssoUser = (SsoUser) request.getAttribute("ssoUser");
        if (ssoUser != null) {
            mv.getModel().put("ssoUser", ssoUser);
        }
        if (StringUtils.isNotBlank(ssoConfig.getInfoUrl())) {
            mv.getModel().put("ssoInfoUrl", ssoConfig.getInfoUrl());
        }
        return mv;
    }

    @GetMapping("/old-admin")
    public ModelAndView oldAdmin(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("index");
        if (StringUtils.isNotBlank(meiliSearchConfig.getWebUi())) {
            mv.getModel().put("meilisearchWebUi", meiliSearchConfig.getWebUi());
        }
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
    public ModelAndView hlsPreview(@RequestParam(value = "uuid", required = false) String uuid) {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("video-player");
        Map<String, Object> model = mv.getModel();
        if (StringUtils.isNotBlank(uuid)) {
            FileObject fileObject = context.STORAGE.get(uuid);
            if (Objects.isNull(fileObject)) return mv;
            List<String> resources = new ArrayList<>();
            if (StringUtils.isNotBlank(fileObject.getHlsAvailable())) {
                resources.add(tfsConfig.getWebEntrypoint() + "/hls/" + uuid + "/playlist.m3u8");
                model.put("hls", "1");
            } else {
                resources.add(tfsConfig.getWebEntrypoint() + "/p/" + uuid);
                model.put("hls", "0");
            }
            model.put("resources", resources);
        }
        return mv;
    }

    // Embed image list viewer
    @GetMapping("/album/**")
    public ModelAndView imagePreview() {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("album");
        mv.getModel().put("token", meiliSearchConfig.getMasterKey());
        mv.getModel().put("meilisearchUrl", meiliSearchConfig.getWebUi());
        return mv;
    }

    // Embed image-hosting background random read template
    @GetMapping("/gallery")
    public ModelAndView randomPicture() {
        ModelAndView mv = new ModelAndView();
        mv.setViewName("gallery");
        return mv;
    }
}
