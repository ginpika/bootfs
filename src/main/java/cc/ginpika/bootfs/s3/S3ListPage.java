package cc.ginpika.bootfs.s3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * ListObjectsV2 分页结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class S3ListPage {
    private List<S3ObjectMeta> contents;
    private List<String> commonPrefixes;
    private boolean truncated;
    private String nextContinuationToken;

    public static S3ListPage empty() {
        return S3ListPage.builder()
                .contents(new ArrayList<>())
                .commonPrefixes(new ArrayList<>())
                .truncated(false)
                .build();
    }
}
