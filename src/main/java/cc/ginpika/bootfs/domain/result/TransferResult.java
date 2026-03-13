package cc.ginpika.bootfs.domain.result;

import cc.ginpika.bootfs.domain.dto.FileObject;

public interface TransferResult {
    Boolean getSucceed();

    void setSucceed(Boolean succeed);

    String getMessage();

    void setMessage(String message);

    FileObject getFileNode();

    void setFileNode(FileObject fileNode);
}
