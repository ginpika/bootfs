package cc.ginpika.bootfs.controller;

import cc.ginpika.bootfs.service.etcd.EtcdService;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.options.GetOption;
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
            List<io.etcd.jetcd.KeyValue> kvs;

            if ("exact".equals(mode) && !prefix.isEmpty()) {
                // 精确查询：只查单个 key
                kvs = etcdService.client.getKVClient()
                        .get(ByteSequence.from(prefix.getBytes()))
                        .join()
                        .getKvs();
            } else {
                // 模糊查询：先按前缀从 etcd 拉取，再在服务端做 contains 过滤
                // 提取 search 的前缀部分用于缩小 etcd 查询范围
                String fetchPrefix = extractPrefixForQuery(prefix);
                if (fetchPrefix.isEmpty()) {
                    // 查全量 key：etcd range 查询，从 \0 开始
                    ByteSequence keyStart = ByteSequence.from(new byte[]{0});
                    kvs = etcdService.client.getKVClient()
                            .get(keyStart,
                                    GetOption.builder().withRange(ByteSequence.from(new byte[]{0}))
                                            .withSortField(GetOption.SortTarget.KEY)
                                            .withSortOrder(GetOption.SortOrder.ASCEND)
                                            .build())
                            .join()
                            .getKvs();
                } else {
                    kvs = etcdService.client.getKVClient()
                            .get(ByteSequence.from(fetchPrefix.getBytes()),
                                    GetOption.builder().isPrefix(true)
                                            .withSortField(GetOption.SortTarget.KEY)
                                            .withSortOrder(GetOption.SortOrder.ASCEND)
                                            .build())
                            .join()
                            .getKvs();
                }

                // 服务端 contains 过滤
                if (!prefix.isEmpty()) {
                    String searchTerm = prefix;
                    kvs = kvs.stream()
                            .filter(kv -> kv.getKey().toString().contains(searchTerm))
                            .collect(Collectors.toList());
                }
            }

            List<Map<String, String>> items = kvs.stream().map(kv -> {
                Map<String, String> item = new HashMap<>();
                item.put("key", kv.getKey().toString());
                item.put("value", kv.getValue().toString());
                return item;
            }).collect(Collectors.toList());

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
            result.put("data", Map.of("key", key, "value", value));
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
