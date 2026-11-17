package cc.ginpika.bootfs.s3;

import java.io.IOException;

class UnsupportedS3PayloadException extends IOException {
    UnsupportedS3PayloadException(String message) { super(message); }
}
