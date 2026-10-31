package cc.ginpika.bootfs.kernel.metadata;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/** Durable authoritative KV operations. Successful mutations are persisted before returning. */
public interface MetadataStore extends AutoCloseable {
    String get(String key);
    void put(String key, String value);
    boolean delete(String key);
    NavigableMap<String, String> scan(String prefix);
    void deletePrefix(String prefix);
    void writeBatch(Map<String, String> puts, Set<String> deletes);
    boolean compareAndSet(String key, String expectedValue, String replacementValue);
    /** Atomic conditional batch; absent values in expected are represented by null. */
    boolean compareAndWrite(Map<String,String> expected, Map<String,String> puts, Set<String> deletes);
    long revision();
    @Override void close();
}
