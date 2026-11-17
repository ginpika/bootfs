package cc.ginpika.bootfs.kernel.object;

import java.io.IOException;

/** Bytes read from the input do not match the expected content digest. */
public class PayloadDigestMismatchException extends IOException {
    public PayloadDigestMismatchException() {
        super("The provided SHA-256 does not match the payload");
    }
}
