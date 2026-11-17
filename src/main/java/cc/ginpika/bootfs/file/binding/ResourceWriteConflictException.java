package cc.ginpika.bootfs.file.binding;

import java.io.IOException;

/** A competing publication won the key binding; clients may retry the operation. */
public class ResourceWriteConflictException extends IOException {
    public ResourceWriteConflictException(String message) { super(message); }
}
