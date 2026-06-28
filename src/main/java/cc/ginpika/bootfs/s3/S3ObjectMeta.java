package cc.ginpika.bootfs.s3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S3 对象元数据,作为 etcd /s3/{bucket}/{key} 索引的 value 存储。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class S3ObjectMeta {
    private String uuid;
    private long size;
    private String etag;
    private long lastModified;
    private String key;
    private String contentType;
}
