package cc.ginpika.bootfs.s3;

import java.io.IOException;

/** A competing publication won the key binding; clients may retry the operation. */
public class S3ConcurrentWriteException extends IOException {
    public S3ConcurrentWriteException(String message) { super(message); }
}
