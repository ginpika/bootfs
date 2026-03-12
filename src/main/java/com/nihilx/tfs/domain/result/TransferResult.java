package com.nihilx.tfs.domain.result;

import com.nihilx.tfs.domain.dto.FileObject;

public interface TransferResult {
    Boolean getSucceed();

    void setSucceed(Boolean succeed);

    String getMessage();

    void setMessage(String message);

    FileObject getFileNode();

    void setFileNode(FileObject fileNode);
}
