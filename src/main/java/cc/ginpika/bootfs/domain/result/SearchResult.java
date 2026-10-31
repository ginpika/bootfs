package cc.ginpika.bootfs.domain.result;

import lombok.Getter;
import java.util.List;
import java.util.Map;

/** BootFS search response, independent of any external search service SDK. */
@Getter
public final class SearchResult {
    private final List<Map<String, Object>> hits;
    private final int estimatedTotalHits;
    private final int totalHits;
    private final int offset;
    private final int limit;
    private final String query;
    private final long visibleRevision;

    public SearchResult(List<Map<String, Object>> hits, int totalHits, int offset, int limit,
                        String query, long visibleRevision) {
        this.hits = List.copyOf(hits);
        this.estimatedTotalHits = totalHits;
        this.totalHits = totalHits;
        this.offset = offset;
        this.limit = limit;
        this.query = query;
        this.visibleRevision = visibleRevision;
    }
}
