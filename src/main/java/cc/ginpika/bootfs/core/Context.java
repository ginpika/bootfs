package cc.ginpika.bootfs.core;

import com.alibaba.fastjson2.JSONObject;
import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.core.io.JsonFileAppender;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.FileObjectWebVO;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


// Context is main control container for tfs, based on a ConcurrentHashMap
// Global util function or methods should be here
@Slf4j
@Service
public class Context {
    @Autowired
    TfsConfig tfsConfig;
    @Autowired
    ContextIO contextIO;

    @Autowired
    ObjectProvider<EtcdService> etcdServiceObjectProvider;
    EtcdService etcdService;

    public String uuid;
    static boolean loaded = false;
    public final Map<String, FileObject> STORAGE = new ConcurrentHashMap<>(4096000);
    public static final int STORAGE_CAPACITY = 4096000;

    private static final AtomicInteger processCount = new AtomicInteger();

    private static final AtomicInteger MAIN_RESOURCES_COUNT = new AtomicInteger();

    @EventListener
    public void onRefresh(ContextRefreshedEvent event) {
        log.info("[InitializeListener] TFS initialize completed");
        try {
            load();
        } catch (Exception e) {
            log.error("[InitializeListener] JsonStorage initialize failed", e);
        }
    }

    public String buildUrl(String uuid) {
        return tfsConfig.getWebEntrypoint() + "/f/" +  uuid;
    }

    public String buildProxyUrl(String uuid) {
        return tfsConfig.getWebEntrypoint() + "/p/" +  uuid;
    }

    public String buildMetaJson(MultipartFile file) {
        return buildMetaJson(file, Objects.requireNonNull(file.getOriginalFilename()));
    }

    public String buildMetaJson(MultipartFile file, String originalFilename) {
        long size = file.getSize();
        int pointIdx = originalFilename.lastIndexOf(".");
        String ext = pointIdx < 0 ? "" : Objects.requireNonNull(originalFilename).substring(pointIdx);
        com.alibaba.fastjson2.JSONObject metaJson = new com.alibaba.fastjson2.JSONObject();
        metaJson.put("size", size);
        metaJson.put("ext", ext);
        return metaJson.toJSONString();
    }

    public String buildMetaJson4Replica(MultipartFile file, String originalFilename, String originalFileId) {
        long size = file.getSize();
        String ext = Objects.requireNonNull(originalFilename).substring(originalFilename.lastIndexOf("."));
        com.alibaba.fastjson2.JSONObject metaJson = new com.alibaba.fastjson2.JSONObject();
        metaJson.put("size", size);
        metaJson.put("ext", ext);
        metaJson.put("copyOf", originalFileId);
        return metaJson.toJSONString();
    }

    public synchronized void load() throws IOException {
        backup();
        loadV2();
    }

    public synchronized void loadV1() throws IOException {
        if (Context.loaded) return;
        try (FileInputStream fis = new FileInputStream(tfsConfig.getConfig())) {
            BufferedInputStream bis = new BufferedInputStream(fis);
            String jsonStr = new String(bis.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = JSONObject.parseObject(jsonStr);
            if (json == null || StringUtils.isEmpty(jsonStr)) {
                json = new JSONObject();
                JsonFileAppender.writeEmptyJson(Path.of(tfsConfig.getConfig()));
            }
            JSONObject finalJson = json;
            json.keySet().forEach(key -> {
                STORAGE.put(key, ContextIO.jsonObjectToFileObject(finalJson.getJSONObject(key)));
            });
            bis.close();
            Context.loaded = true;
        } catch (FileNotFoundException e) {
            createDbJsonFile();
        }
    }

    // TODO move to JsonFileLoader
    public synchronized void loadV2() throws IOException {
        checkDataDir();
        FileChannel fileChannel;
        try {
            fileChannel = FileChannel.open(Path.of(tfsConfig.getConfig()), StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            createDbJsonFile();
            Context.loaded = true;
            return;
        }
        if (fileChannel.size() <= 4) {
            log.info("使用 loadV1 加载 db.json");
            loadV1();
            return;
        }
        ByteBuffer byteBuffer = ByteBuffer.allocate(32768);
        // 读取数据到缓冲区
        boolean firstLineFlag = false;
        int gcCountLabel = 100000;
        long lastST = System.currentTimeMillis();
        AtomicInteger batchCount = new AtomicInteger();
        byte[] bytes = new byte[byteBuffer.limit()];
        int bytesIdx = 0;
        while (fileChannel.read(byteBuffer) != -1) {
            byteBuffer.flip(); // 切换为读模式
            boolean failed = false;
            while (byteBuffer.hasRemaining()) {
                byte cur = byteBuffer.get();
                bytes[bytesIdx++] = cur;
                // TODO WARN maybe conflict with \r\n
                if (cur == '\n') {
                    if (!firstLineFlag) {
                        firstLineFlag = true;
                        bytes = new byte[byteBuffer.limit()];
                        bytesIdx = 0;
                    } else {
                        try {
                            byte[] curBytes = new byte[bytesIdx];
                            System.arraycopy(bytes, 0, curBytes, 0, bytesIdx);
                            String rawLine = new String(curBytes, StandardCharsets.UTF_8);
                            int idxFrom = rawLine.indexOf(":") + 1;
                            int idxTo = rawLine.length() - idxFrom - 2;
                            String jsonStr = rawLine.substring(idxFrom, idxFrom + idxTo);
                            JSONObject json = JSONObject.parseObject(jsonStr);
                            String uuid = rawLine.substring(1, idxFrom - 2);
                            FileObject loaded = ContextIO.jsonObjectToFileObject(json);
                            STORAGE.put(uuid, loaded);
                            if (processCount.incrementAndGet() == gcCountLabel) {
                                logTest(batchCount, lastST);
                                processCount.set(0);
                            }
                            bytes = new byte[byteBuffer.limit()];
                            bytesIdx = 0;
                        } catch (Exception e) {
                            if (e.getMessage().startsWith("offset")) {
                                byte[] curBytes = new byte[bytesIdx];
                                System.arraycopy(bytes, 0, curBytes, 0, bytesIdx);
                                String rawLine = new String(curBytes, StandardCharsets.UTF_8);
                                int idxFrom = rawLine.indexOf(":") + 1;
                                int idxTo = rawLine.length() - idxFrom - 1;
                                String jsonStr = rawLine.substring(idxFrom, idxFrom + idxTo);
                                JSONObject json = JSONObject.parseObject(jsonStr);
                                String uuid = json.getString("uuid");
                                FileObject loaded = ContextIO.jsonObjectToFileObject(json);
                                STORAGE.put(uuid, loaded);
                                if (processCount.incrementAndGet() == gcCountLabel) {
                                    logTest(batchCount, lastST);
                                    processCount.set(0);
                                }
                                bytes = new byte[byteBuffer.limit()];
                                bytesIdx = 0;
                                break;
                            }
                            failed = true;
                            break;
                        }
                    }
                }
            }
            if (failed) break;
            byteBuffer.clear(); // 清空缓冲区，准备下一次读取
        }
        // 关闭通道
        fileChannel.close();
        Context.loaded = true;
        log.info("Context loadV2 finished, size: {}", STORAGE.size());
    }

    public void checkDataDir() throws IOException {
        Path dataDir = Path.of(tfsConfig.getPathPrefix());
        if (Files.notExists(dataDir)) {
            Files.createDirectory(dataDir);
            log.info("[检测到data目录不存在，已创建]");
        }
        log.info("[目录挂载完毕]");
    }

    public void createDbJsonFile() throws IOException {
        File file = new File(tfsConfig.getConfig());
        if (file.createNewFile()) {
            log.info("配置文件创建成功");
            JsonFileAppender.writeEmptyJson(Path.of(tfsConfig.getConfig()));
            Context.loaded = true;
        } else {
            log.info("配置文件创建失败");
        }
    }

    private void logTest(AtomicInteger batchCount, long lastST) {
        log.info("当前是 {} 次 ( * 100000) batchCount，已耗时 {} ms",
                batchCount.incrementAndGet(),
                System.currentTimeMillis() - lastST);
    }


    public synchronized void backup() throws IOException {
        Path source = Path.of(tfsConfig.getConfig());
        if (!Files.exists(source)) return;
        Path target = Path.of(tfsConfig.getConfig() + ".bak");
        Files.deleteIfExists(target);
        Files.copy(source, target);
    }

    public synchronized void record(FileObject file, String id) {
        // 写入 webUI 索引
        STORAGE.put(id, file);
        contextIO.append(id, file);
        log.info(file.getFileName());
        log.info("{} -> size: {}", id, STORAGE.size());
    }

    public void remove(String id) {
        STORAGE.remove(id);
        contextIO.remove(id);
    }

    public FileObject query(String uuid) {
        return STORAGE.get(uuid);
    }

    public FileObjectWebVO queryByOffset(int offset, int limit, String search) {
        if (etcdService == null) {
            etcdService = etcdServiceObjectProvider.getIfUnique();
            if (etcdService != null) Context.MAIN_RESOURCES_COUNT.set(etcdService.getMainResourceCount());
        }

        int length = STORAGE.size();
        int total = 0;

        Set<Map.Entry<String, FileObject>> sets = STORAGE.entrySet();

        List<FileObject> files = new ArrayList<>();
        int skip = 0;
        for (Map.Entry<String, FileObject> entry : sets) {
            if (StringUtils.isNotBlank(search)) {
                FileObject tFile = entry.getValue();
                if (search.contains("meta:index")) {
                    if (StringUtils.isNotBlank(tFile.getCopyOf()) || StringUtils.isNotBlank(tFile.getParent())) {
                        continue;
                    }
                }
                String[] words = search.split(" ");
                boolean uuidHit = false;
                boolean filenameHit = false;

                int keyCount = 0;

                for (String word : words) {
                    if (word.startsWith("meta:")) continue;
                    if (!uuidHit) {
                        uuidHit = tFile.getUuid().contains(word);
                    }
                    if (!filenameHit) {
                        filenameHit = tFile.getFileName().contains(word);
                    }
                    keyCount++;
                }

                if (!uuidHit && !filenameHit && keyCount != 0) continue;

                total++;
                if (skip++ < offset) continue;
                if (skip <= offset + limit) {
                    tFile.setUrl(buildUrl(tFile.getUuid()));
                    files.add(tFile);
                } else {
                    break;
                }
            } else {
                if (skip++ < offset) continue;
                if (skip <= offset + limit) {
                    FileObject tFile = entry.getValue();
                    tFile.setUrl(buildUrl(tFile.getUuid()));
                    files.add(tFile);
                } else {
                    break;
                }
            }
        }

        // 再一次查询中尽可能把副本显示在后
        files.sort((f1, f2) -> {
            if (StringUtils.isNotBlank(f1.getCopyOf())) {
                return 1;
            }
            if (StringUtils.isNotBlank(f2.getCopyOf())) {
                return -1;
            }
            return 0;
        });

        // update main-resource-count, to support scroll search paged query
        if (search.equals("meta:index")) {
            if (total - offset > 10) {
                total = Math.max(MAIN_RESOURCES_COUNT.get(), total);
            } else {
                if (total > MAIN_RESOURCES_COUNT.get()) {
                    etcdService.updateMainResourceCount(total);
                    MAIN_RESOURCES_COUNT.set(total);
                }
            }
        }

        if (StringUtils.isNotBlank(search)) {
            return FileObjectWebVO.builder().total(total).pageNumber(offset).pageSize(limit)
                    .rows(files)
                    .build();
        }

        return FileObjectWebVO.builder().total(length).pageNumber(offset).pageSize(limit)
                .rows(files)
                .build();
    }
}
