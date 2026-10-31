package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.service.etcd.EtcdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/api/etcd")
public class EtcdApiController {

    @Autowired
    private EtcdService etcdService;

    public EtcdApiController(EtcdService etcdService) {
        this.etcdService = etcdService;
    }

    @GetMapping("/keys")
    public Map<String, Object> listKeys(@RequestParam(value = "prefix", defaultValue = "") String prefix,
                                         @RequestParam(value = "mode", defaultValue = "fuzzy") String mode) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String,String> kvs = new TreeMap<>();
            if ("exact".equals(mode) && !prefix.isEmpty()) {
                String value = etcdService.getOne(prefix);
                if (value != null) kvs.put(prefix,value);
            } else {
                etcdService.scan("").forEach((key,value) -> {
                    if (key.contains(prefix)) kvs.put(key,value);
                });
            }
            List<Map<String,String>> items = kvs.entrySet().stream()
                    .map(entry -> Map.of("key",entry.getKey(),"value",entry.getValue()))
                    .collect(Collectors.toList());

            result.put("succeed", true);
            result.put("data", items);
            result.put("total", items.size());
        } catch (Exception e) {
            log.error("查询 etcd keys 失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 从搜索关键词中提取前缀，用于缩小 etcd 查询范围。
     * 取最后一个 '/' 之前的部分作为前缀；若无 '/' 则返回空串（查全部）。
     */
    private String extractPrefixForQuery(String search) {
        if (search == null || search.isEmpty()) {
            return "";
        }
        int lastSlash = search.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return search.substring(0, lastSlash + 1);
    }

    @PostMapping("/key")
    public Map<String, Object> putKey(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String key = body.get("key");
            String value = body.get("value");
            if (key == null || key.isEmpty()) {
                result.put("succeed", false);
                result.put("message", "key 不能为空");
                return result;
            }
            etcdService.put(key, value != null ? value : "");
            result.put("succeed", true);
            result.put("message", "写入成功");
        } catch (Exception e) {
            log.error("写入 etcd key 失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/key")
    public Map<String, Object> getKey(@RequestParam("key") String key) {
        Map<String, Object> result = new HashMap<>();
        try {
            String value = etcdService.getOne(key);
            result.put("succeed", true);
            Map<String,String> item = new HashMap<>();
            item.put("key",key);
            item.put("value",value);
            result.put("data",item);
        } catch (Exception e) {
            log.error("查询 etcd key 失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/key")
    public Map<String, Object> deleteKey(@RequestParam("key") String key) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean deleted = etcdService.delete(key);
            result.put("succeed", true);
            result.put("message", deleted ? "删除成功" : "key 不存在");
        } catch (Exception e) {
            log.error("删除 etcd key 失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/keys/prefix")
    public Map<String, Object> deleteByPrefix(@RequestParam("prefix") String prefix) {
        Map<String, Object> result = new HashMap<>();
        try {
            etcdService.deleteWithPrefix(prefix);
            result.put("succeed", true);
            result.put("message", "删除成功");
        } catch (Exception e) {
            log.error("按前缀删除 etcd keys 失败", e);
            result.put("succeed", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
