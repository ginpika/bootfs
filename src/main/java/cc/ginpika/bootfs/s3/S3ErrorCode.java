package cc.ginpika.bootfs.s3;

import lombok.Getter;

/**
 * S3 错误码与 HTTP status 映射。
 */
@Getter
public enum S3ErrorCode {
    NO_SUCH_KEY("NoSuchKey", 404, "The specified key does not exist."),
    NO_SUCH_BUCKET("NoSuchBucket", 404, "The specified bucket does not exist."),
    ACCESS_DENIED("AccessDenied", 403, "Access Denied."),
    SIGNATURE_DOES_NOT_MATCH("SignatureDoesNotMatch", 403, "The request signature we calculated does not match the signature you provided."),
    REQUEST_TIME_TOO_SKEWED("RequestTimeTooSkewed", 403, "The difference between the request time and the server's time is too large."),
    INVALID_REQUEST("InvalidRequest", 400, "Invalid Request."),
    METHOD_NOT_ALLOWED("MethodNotAllowed", 405, "The specified method is not allowed against this resource.");

    private final String xmlCode;
    private final int httpStatus;
    private final String defaultMessage;

    S3ErrorCode(String xmlCode, int httpStatus, String defaultMessage) {
        this.xmlCode = xmlCode;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
