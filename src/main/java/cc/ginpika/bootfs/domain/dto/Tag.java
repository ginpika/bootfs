package cc.ginpika.bootfs.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tag {
    /** 标签名，如 "赛博朋克", "NSFW" */
    private String name;
    /** 命名空间，区分标签维度：manual / ai / style / character / content */
    private String namespace;
    /** 来源：manual（手动）或 ai（AI 自动） */
    private String source;
    /** AI 置信度 0.0~1.0，手动标签默认为 1.0 */
    private Float confidence;

    /**
     * 创建手动标签（默认 namespace=manual, confidence=1.0）
     */
    public static Tag manual(String name) {
        return Tag.builder()
                .name(name)
                .namespace("manual")
                .source("manual")
                .confidence(1.0f)
                .build();
    }

    /**
     * 创建手动标签并指定 namespace
     */
    public static Tag manual(String namespace, String name) {
        return Tag.builder()
                .name(name)
                .namespace(namespace)
                .source("manual")
                .confidence(1.0f)
                .build();
    }

    /**
     * 转为 MeiliSearch 索引格式：namespace:name
     */
    public String toIndexFormat() {
        return namespace + ":" + name;
    }

    /**
     * 从 MeiliSearch 索引格式 "namespace:name" 解析
     */
    public static Tag fromIndexFormat(String indexValue) {
        int idx = indexValue.indexOf(':');
        if (idx <= 0) {
            return Tag.manual(indexValue);
        }
        return Tag.builder()
                .namespace(indexValue.substring(0, idx))
                .name(indexValue.substring(idx + 1))
                .source("manual")
                .confidence(1.0f)
                .build();
    }
}
