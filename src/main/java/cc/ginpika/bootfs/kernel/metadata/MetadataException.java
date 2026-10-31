package cc.ginpika.bootfs.kernel.metadata;

/** A failed durable operation; callers must not acknowledge a resource mutation after this error. */
public class MetadataException extends RuntimeException {
    public MetadataException(String message, Throwable cause) { super(message, cause); }
    public MetadataException(String message) { super(message); }
}
