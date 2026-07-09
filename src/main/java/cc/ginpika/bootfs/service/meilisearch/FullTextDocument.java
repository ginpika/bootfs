package cc.ginpika.bootfs.service.meilisearch;

import com.alibaba.fastjson2.JSONArray;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

// basic full-text-document for meilisearch
// if a resources is published for client or consumer, tfs will create index at meilisearch
@Builder
@Data
public class FullTextDocument {
    // same as FileObject uuid
    private String uuid;
    private String title;
    private String author;
    private String description;
    // poster field could be cover for meilisearch`s official dashboard
    private String poster;
    // 缩略图完整 URL（集群下指向上传节点的 /thumb/{uuid}），渲染缩略图时直接使用此字段
    private String thumbUrl;
    private JSONArray tags;
    // means this document is a group index (a parent-level resource), has some children-level resources
    // always be described with FileObject`s parent field
    private JSONArray resources;
    // which node storage this resource
    private String contextUuid;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- FileObject 还原字段（集群列表查询时直接从文档还原 FileObject，无需查本地内存） ----
    // 对应 FileObject.fileName（与 title 同值，冗余存储以便直接映射）
    private String fileName;
    // 对应 FileObject.size
    private Long size;
    // 对应 FileObject.copyOf
    private String copyOf;
    // 对应 FileObject.hlsAvailable
    private String hlsAvailable;
    // 对应 FileObject.thumbAvailable
    private String thumbAvailable;
    // 对应 FileObject.parent
    private String parent;
    // 对应 FileObject.albumAvailable
    private String albumAvailable;
    // 对应 FileObject.nsfw
    private String nsfw;
    // 对应 FileObject.isPublicAccess
    private String isPublicAccess;
    // 对应 FileObject.createdAt（毫秒时间戳）
    private Long fileCreatedAt;
}
