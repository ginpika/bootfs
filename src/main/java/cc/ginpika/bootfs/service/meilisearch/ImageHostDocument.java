package cc.ginpika.bootfs.service.meilisearch;


import com.alibaba.fastjson2.JSONArray;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Builder
@Data
public class ImageHostDocument {
    // same as FileObject uuid
    private String uuid;
    private String author;
    // poster field could be cover for meilisearch`s official dashboard
    private String poster;
    // 缩略图完整 URL（集群下指向上传节点的 /thumb/{uuid}），渲染缩略图时直接使用此字段
    private String thumbUrl;
    private JSONArray tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- FileObject 还原字段（集群列表查询时直接从文档还原 FileObject，无需查本地内存） ----
    private String fileName;
    private Long size;
    private String copyOf;
    private String hlsAvailable;
    private String thumbAvailable;
    private String parent;
    private String albumAvailable;
    private String nsfw;
    private String isPublicAccess;
    private Long fileCreatedAt;
}
