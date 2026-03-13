package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.config.TfsConfig;
import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.core.io.ContextIO;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.NodeObject;
import cc.ginpika.bootfs.domain.result.SimpleTransferResult;
import cc.ginpika.bootfs.domain.result.TransferResult;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import cc.ginpika.bootfs.core.io.HttpFileTransferClient;
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
