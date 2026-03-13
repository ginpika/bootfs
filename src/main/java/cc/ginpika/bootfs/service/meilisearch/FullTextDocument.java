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
    private JSONArray tags;
    // means this document is a group index (a parent-level resource), has some children-level resources
    // always be described with FileObject`s parent field
    private JSONArray resources;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
