package cc.ginpika.bootfs.s3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * ListObjects V1/V2 分页结果。
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
    private String nextMarker;

    public S3ListPage(List<S3ObjectMeta> contents, List<String> commonPrefixes,
                      boolean truncated, String nextContinuationToken) {
        this(contents, commonPrefixes, truncated, nextContinuationToken, null);
    }

    public static S3ListPage empty() {
        return S3ListPage.builder()
                .contents(new ArrayList<>())
                .commonPrefixes(new ArrayList<>())
                .truncated(false)
                .build();
    }
}
