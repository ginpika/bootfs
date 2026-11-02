package cc.ginpika.bootfs.s3;

/** An S3 operation failure that must retain its protocol error code. */
public class S3OperationException extends RuntimeException {
    private final S3ErrorCode code;

    public S3OperationException(S3ErrorCode code) {
        super(code.getDefaultMessage());
        this.code = code;
    }

    public S3ErrorCode getCode() { return code; }
}
