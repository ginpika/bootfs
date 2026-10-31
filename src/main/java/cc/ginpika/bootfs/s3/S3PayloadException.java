package cc.ginpika.bootfs.s3;

import java.io.IOException;

/** Payload validation failed before any catalog or key binding was published. */
public class S3PayloadException extends IOException {
    private final boolean digestMismatch;
    public S3PayloadException(String message, boolean digestMismatch) { super(message); this.digestMismatch = digestMismatch; }
    public boolean isDigestMismatch() { return digestMismatch; }
}
