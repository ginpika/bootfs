package cc.ginpika.bootfs.kernel.object;

import java.io.IOException;

/** Bytes and retry intent are retained. This is not an acknowledgement of publication. */
public class PublicationPendingException extends IOException {
    private final String uploadId;
    public PublicationPendingException(String uploadId, String reason, Throwable cause) {
        super(reason, cause); this.uploadId = uploadId;
    }
    public String getUploadId() { return uploadId; }
}
