package cc.ginpika.bootfs.file.binding;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable binding stored under /s3/{bucket}/{key}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectKeyBinding {
    private String uuid;
    private long size;
    private String etag;
    private long lastModified;
    private String key;
    private String contentType;
}
