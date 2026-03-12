package com.nihilx.tfs.service;

import com.nihilx.tfs.config.TfsConfig;
import com.nihilx.tfs.core.Context;
import com.nihilx.tfs.core.io.ContextIO;
import com.nihilx.tfs.domain.dto.FileObject;
import com.nihilx.tfs.domain.dto.NodeObject;
import com.nihilx.tfs.domain.result.SimpleTransferResult;
import com.nihilx.tfs.domain.result.TransferResult;
import com.nihilx.tfs.service.etcd.EtcdService;
import com.nihilx.tfs.core.io.HttpFileTransferClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.*;
import java.nio.file.Path;

@Slf4j
@Service
public class FileTransferService {
    @Resource
    Context context;
    @Resource
    TfsConfig tfsConfig;
    @Resource
    EtcdService etcdService;

    public TransferResult accept(MultipartFile file, String originalFilename, String originalFileUuid) {
        try {
            String uuid = String.valueOf(ContextIO.findHighIndexUUID());
            saveToLocal(uuid, file);
            String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
            FileObject fileObject = new FileObject(filePath, uuid, originalFilename);
            fileObject.setCopyOf(originalFileUuid);
            context.record(fileObject, uuid);
            etcdService.putFileReplica(originalFileUuid, uuid, context.buildUrl(uuid),
                    context.buildMetaJson4Replica(file, originalFilename, originalFileUuid));
        } catch (Exception e) {
            log.error("FileTransferService error", e);
            SimpleTransferResult.builder().succeed(false).build();
        }
        return SimpleTransferResult.builder().succeed(true).build();
    }

    @SuppressWarnings("all")
    private void saveToLocal(String uuid, MultipartFile file) throws IOException {
        String filePath = Path.of(tfsConfig.getPathPrefix(), uuid).toString();
        File dataDir = new File(tfsConfig.getPathPrefix());
        if (!dataDir.exists()) {
            boolean result = dataDir.mkdirs();
            if (result) log.info("创建 dataDir 目录: {}", dataDir.getAbsoluteFile());
        }
        File target = new File(filePath);
        if (!target.createNewFile()) {
            throw new IOException("文件创建异常");
        }
        FileOutputStream fos = new FileOutputStream(target);
        file.getInputStream().transferTo(fos);
        fos.close();
        file.getInputStream().close();
    }


    // 传输文件
    public void put(File rawFile, String fileId, String originFileName, NodeObject server) throws IOException {
        HttpFileTransferClient client = new HttpFileTransferClient();
        client.transfer(rawFile, fileId, originFileName, server);
    }
}
