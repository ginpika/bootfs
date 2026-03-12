package com.nihilx.tfs.core.io;

import com.alibaba.fastjson2.JSONObject;
import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.domain.dto.FileObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ContextIO {
    private final ThreadPoolExecutor jsonIoThreadPool = new JsonIoThreadPool();
    public static ReentrantLock FILE_LOCK = new ReentrantLock();

    @Autowired
    TfsConfig tfsConfig;

    public void append(String uuid, FileObject object) {
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            File file = new File(tfsConfig.getConfig());
            ContextIO.FILE_LOCK.lock();
            try {
                JsonFileAppender.append(file, uuid, fileObjectToJsonObject(object));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                ContextIO.FILE_LOCK.unlock();
            }
            long endTime = System.currentTimeMillis();
            log.info("写 {} 耗时 {} ms", uuid, endTime - startTime);
        }, jsonIoThreadPool);
    }

    public void remove(String uuid) {
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            File file = new File(tfsConfig.getConfig());
            try {
                ContextIO.FILE_LOCK.lock();
                JsonFileLineDeleter.deleteLine(uuid, file);
                long endTime = System.currentTimeMillis();
                log.info("删除 {} 耗时 {} ms", uuid, endTime - startTime);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                ContextIO.FILE_LOCK.unlock();
            }
        }, jsonIoThreadPool);
    }

    public void update(String uuid, FileObject fileObject) {
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            Path file = Path.of(tfsConfig.getConfig());
            try {
                ContextIO.FILE_LOCK.lock();
                JsonFileLineUpdater.updateLine(file, uuid, fileObjectToJsonObject(fileObject));
                long endTime = System.currentTimeMillis();
                log.info("更新 {} 耗时 {} ms", uuid, endTime - startTime);
            } catch (Exception e) {
                log.error("ContextIO update error", e);
            } finally {
                ContextIO.FILE_LOCK.unlock();
            }
        }, jsonIoThreadPool);
    }

    public static JSONObject fileObjectToJsonObject(FileObject object) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("fileName", object.getFileName());
        jsonObject.put("path", object.getPath());
        jsonObject.put("uuid", object.getUuid());
        if (StringUtils.isNotBlank(object.getCopyOf())) {
            jsonObject.put("copyOf", object.getCopyOf());
        }
        if (StringUtils.isNotBlank(object.getHlsAvailable())) {
            jsonObject.put("hlsAvailable", object.getHlsAvailable());
        }
        if (StringUtils.isNotBlank(object.getParent())) {
            jsonObject.put("parent", object.getParent());
        }
        if (StringUtils.isNotBlank(object.getAlbumAvailable())) {
            jsonObject.put("albumAvailable", object.getAlbumAvailable());
        }
        jsonObject = JSONObject.from(object);
        return jsonObject;
    }

    public static FileObject jsonObjectToFileObject(JSONObject jsonObject) {
        return JSONObject.parseObject(jsonObject.toJSONString(), FileObject.class);
    }

    private static UUID normalUUID() {
        return UUID.randomUUID();
    }

    public static UUID findLowIndexUUID() {
        return normalUUID();
    }

    public static UUID findHighIndexUUID() {
        return normalUUID();
//        }
    }
}
