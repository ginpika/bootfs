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
    private JSONArray tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
